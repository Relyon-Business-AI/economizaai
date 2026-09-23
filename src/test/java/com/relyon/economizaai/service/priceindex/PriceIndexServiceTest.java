package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.PriceObservationAudit;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptChannel;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.MarketHouseholdCount;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import com.relyon.economizaai.service.notifications.NotificationRuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceIndexServiceTest {

    @Mock private PriceObservationRepository observationRepository;
    @Mock private PriceObservationAuditRepository auditRepository;
    @Mock private MarketLocationService marketLocationService;
    @Mock private MerchantSupportGate merchantSupportGate;
    @Mock private NotificationRuleEngine notificationRuleEngine;

    private CollaborativeProperties properties;
    private PriceIndexService service;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        service = new PriceIndexService(observationRepository, auditRepository, properties,
                marketLocationService, merchantSupportGate, notificationRuleEngine);
        lenient().when(merchantSupportGate.contributesToIndex(any())).thenReturn(true);
    }

    private Receipt buildConfirmedReceipt(boolean optIn, ReceiptItem... items) {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).email("u@e").household(household)
                .contributionOptIn(optIn).build();
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .user(user)
                .household(household)
                .cnpjEmitente("93015006005182")
                .marketName("Mercado X")
                .issuedAt(LocalDateTime.now())
                .build();
        for (var item : items) receipt.addItem(item);
        return receipt;
    }

    private ReceiptItem itemWithProduct(Product product, BigDecimal unitPrice) {
        return ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(1)
                .rawDescription(product.getNormalizedName())
                .quantity(BigDecimal.ONE)
                .unitPrice(unitPrice)
                .totalPrice(unitPrice)
                .product(product)
                .build();
    }

    private Product product() {
        return Product.builder().id(UUID.randomUUID()).normalizedName("Arroz").build();
    }

    private ReceiptItem itemWithProductAndEan(Product product, BigDecimal unitPrice, String ean) {
        var item = itemWithProduct(product, unitPrice);
        item.setEan(ean);
        return item;
    }

    private void stubObservationSaveEchoing() {
        when(observationRepository.save(any(PriceObservation.class))).thenAnswer(inv -> {
            var obs = inv.<PriceObservation>getArgument(0);
            obs.setId(UUID.randomUUID());
            return obs;
        });
    }

    @Test
    void recordContributions_onlineReceiptContributesEanItemsIgnoringMerchantSupport() {
        // Amazon (marketplace, segment OTHER) selling a real grocery EAN → still contributes.
        // The online path never consults merchantSupportGate (the lenient default stub is unused).
        var receipt = buildConfirmedReceipt(true,
                itemWithProductAndEan(product(), new BigDecimal("12.90"), "7898912704016"));
        receipt.setChannel(ReceiptChannel.ONLINE);
        stubObservationSaveEchoing();

        var written = service.recordContributions(receipt);

        assertEquals(1, written);
        verify(observationRepository).save(argThat(obs -> obs.getChannel() == ReceiptChannel.ONLINE));
    }

    @Test
    void recordContributions_onlineReceiptSkipsItemsWithoutEan() {
        var withEan = itemWithProductAndEan(product(), new BigDecimal("12.90"), "7898912704016");
        var noEan = itemWithProduct(Product.builder().id(UUID.randomUUID()).normalizedName("Livro").build(),
                new BigDecimal("59.90")); // e.g. a book on the marketplace — no grocery GTIN match
        var receipt = buildConfirmedReceipt(true, withEan, noEan);
        receipt.setChannel(ReceiptChannel.ONLINE);
        stubObservationSaveEchoing();

        var written = service.recordContributions(receipt);

        assertEquals(1, written, "online index gates by EAN — the no-EAN line is skipped");
        verify(observationRepository, times(1)).save(any(PriceObservation.class));
    }

    @Test
    void recordContributions_onlineObservationsDoNotFeedLocalWatchEngine() {
        var receipt = buildConfirmedReceipt(true,
                itemWithProductAndEan(product(), new BigDecimal("12.90"), "7898912704016"));
        receipt.setChannel(ReceiptChannel.ONLINE);
        stubObservationSaveEchoing();

        service.recordContributions(receipt);

        // "avise-me quando" rules are location-based → online prices must not trigger them.
        verify(notificationRuleEngine).evaluate(argThat(List::isEmpty), any());
    }

    @Test
    void recordContributions_inStoreObservationsFeedWatchEngine() {
        var receipt = buildConfirmedReceipt(true, itemWithProduct(product(), new BigDecimal("10")));
        stubObservationSaveEchoing();

        service.recordContributions(receipt);

        verify(notificationRuleEngine).evaluate(argThat(list -> list.size() == 1), any());
    }

    @Test
    void onlineReferencePrice_returnsDataWhenThresholdsMet() {
        var productId = UUID.randomUUID();
        var observations = List.of(
                obs(productId, new BigDecimal("10")), obs(productId, new BigDecimal("11")),
                obs(productId, new BigDecimal("12")), obs(productId, new BigDecimal("9")),
                obs(productId, new BigDecimal("13"))
        );
        when(observationRepository.findRecentOnlineByProduct(eq(productId), any()))
                .thenReturn(observations);
        when(auditRepository.countDistinctOnlineHouseholdsForProduct(eq(productId), any()))
                .thenReturn(3L);

        var ref = service.onlineReferencePrice(productId);

        assertTrue(ref.hasData());
        assertEquals(0, ref.medianPrice().compareTo(new BigDecimal("11")));
        assertEquals(5, ref.sampleCount());
    }

    @Test
    void onlineReferencePrice_blocksBelowKAnon() {
        var productId = UUID.randomUUID();
        var observations = List.of(
                obs(productId, new BigDecimal("10")), obs(productId, new BigDecimal("11")),
                obs(productId, new BigDecimal("12")), obs(productId, new BigDecimal("10")),
                obs(productId, new BigDecimal("11"))
        );
        when(observationRepository.findRecentOnlineByProduct(eq(productId), any()))
                .thenReturn(observations);
        when(auditRepository.countDistinctOnlineHouseholdsForProduct(eq(productId), any()))
                .thenReturn(1L);

        var ref = service.onlineReferencePrice(productId);

        assertFalse(ref.hasData());
        assertNull(ref.medianPrice());
        assertTrue(ref.kAnonBlocked());
    }

    @Test
    void recordContributions_skipsWhenUserOptedOut() {
        var receipt = buildConfirmedReceipt(false, itemWithProduct(product(), new BigDecimal("10")));

        var written = service.recordContributions(receipt);

        assertEquals(0, written);
        verify(observationRepository, never()).save(any());
        verify(auditRepository, never()).save(any());
    }

    @Test
    void recordContributions_skipsWhenMasterSwitchOff() {
        properties.getCollaborative().setEnabled(false);
        var receipt = buildConfirmedReceipt(true, itemWithProduct(product(), new BigDecimal("10")));

        var written = service.recordContributions(receipt);

        assertEquals(0, written);
        verify(observationRepository, never()).save(any());
    }

    @Test
    void recordContributions_writesOneObservationAndAuditPerLinkedItem() {
        var p1 = product();
        var p2 = Product.builder().id(UUID.randomUUID()).normalizedName("Leite").build();
        var item1 = itemWithProduct(p1, new BigDecimal("10"));
        var item2 = itemWithProduct(p2, new BigDecimal("5"));
        var unlinked = ReceiptItem.builder()
                .id(UUID.randomUUID())
                .lineNumber(3)
                .rawDescription("UNKNOWN")
                .quantity(BigDecimal.ONE)
                .unitPrice(BigDecimal.ONE)
                .totalPrice(BigDecimal.ONE)
                .build(); // no product linked → must be skipped
        var receipt = buildConfirmedReceipt(true, item1, item2, unlinked);

        when(observationRepository.save(any(PriceObservation.class))).thenAnswer(inv -> {
            var obs = inv.<PriceObservation>getArgument(0);
            obs.setId(UUID.randomUUID());
            return obs;
        });

        var written = service.recordContributions(receipt);

        assertEquals(2, written);
        verify(observationRepository, times(2)).save(any(PriceObservation.class));
        verify(auditRepository, times(2)).save(any(PriceObservationAudit.class));
    }

    @Test
    void recordContributions_includesNotMineItemButSkipsFullyExcluded() {
        var notMine = itemWithProduct(product(), new BigDecimal("10"));
        notMine.setExcludedFromPersonal(true); // "not mine" → out of personal, but the price is real → index
        var junk = itemWithProduct(Product.builder().id(UUID.randomUUID()).normalizedName("Leite").build(),
                new BigDecimal("5"));
        junk.setExcluded(true); // fully excluded → out of the index
        var receipt = buildConfirmedReceipt(true, notMine, junk);

        when(observationRepository.save(any(PriceObservation.class))).thenAnswer(inv -> {
            var obs = inv.<PriceObservation>getArgument(0);
            obs.setId(UUID.randomUUID());
            return obs;
        });

        var written = service.recordContributions(receipt);

        assertEquals(1, written); // only the "not mine" line contributes
        verify(observationRepository, times(1)).save(any(PriceObservation.class));
    }

    @Test
    void recordContributions_skipsWhenSameChaveAlreadyContributedByOtherHousehold() {
        var receipt = buildConfirmedReceipt(true, itemWithProduct(product(), new BigDecimal("10")));
        receipt.setChaveAcesso("43260412345678000190650010000123451123456780");
        when(auditRepository.existsContributionForChaveFromOtherHousehold(
                receipt.getChaveAcesso(), receipt.getHousehold().getId())).thenReturn(true);

        var written = service.recordContributions(receipt);

        assertEquals(0, written);
        verify(observationRepository, never()).save(any());
        verify(auditRepository, never()).save(any());
    }

    @Test
    void recordContributions_setsCnpjRoot() {
        var receipt = buildConfirmedReceipt(true, itemWithProduct(product(), new BigDecimal("10")));
        when(observationRepository.save(any(PriceObservation.class))).thenAnswer(inv -> {
            var obs = inv.<PriceObservation>getArgument(0);
            assertEquals("93015006", obs.getMarketCnpjRoot());
            obs.setId(UUID.randomUUID());
            return obs;
        });

        service.recordContributions(receipt);

        verify(observationRepository).save(any(PriceObservation.class));
    }

    @Test
    void referencePrice_returnsEmptyWhenInsufficientObservations() {
        var productId = UUID.randomUUID();
        when(observationRepository.findRecentByProductAndMarket(eq(productId), eq("123"), any()))
                .thenReturn(List.of()); // 0 observations

        var ref = service.referencePrice(productId, "123");

        assertFalse(ref.hasData());
    }

    @Test
    void referencePrice_returnsEmptyWhenKAnonBlocks() {
        var productId = UUID.randomUUID();
        // 6 observations from same household → enough samples but only 1 household → blocked
        var observations = List.of(
                obs(productId, new BigDecimal("10")), obs(productId, new BigDecimal("11")),
                obs(productId, new BigDecimal("12")), obs(productId, new BigDecimal("10")),
                obs(productId, new BigDecimal("11")), obs(productId, new BigDecimal("10"))
        );
        when(observationRepository.findRecentByProductAndMarket(eq(productId), eq("123"), any()))
                .thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq("123"), any()))
                .thenReturn(1L); // only 1 distinct household

        var ref = service.referencePrice(productId, "123");

        assertFalse(ref.hasData(), "k-anon must block when fewer than min households");
    }

    @Test
    void referencePrice_returnsDataWhenAllThresholdsMet() {
        var productId = UUID.randomUUID();
        var observations = List.of(
                obs(productId, new BigDecimal("10")), obs(productId, new BigDecimal("11")),
                obs(productId, new BigDecimal("12")), obs(productId, new BigDecimal("9")),
                obs(productId, new BigDecimal("13"))
        );
        when(observationRepository.findRecentByProductAndMarket(eq(productId), eq("123"), any()))
                .thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq("123"), any()))
                .thenReturn(3L);

        var ref = service.referencePrice(productId, "123");

        assertTrue(ref.hasData());
        assertEquals(0, ref.medianPrice().compareTo(new BigDecimal("11")));
        assertEquals(5, ref.sampleCount());
        assertEquals(3, ref.distinctHouseholds());
    }

    @Test
    void bestMarkets_filtersOutMarketsBelowThresholds() {
        var productId = UUID.randomUUID();
        // market A: 5 obs from 3 households (passes) median 10
        // market B: 5 obs from 1 household (k-anon blocks) median 8
        var observations = new ArrayList<PriceObservation>();
        for (var i = 0; i < 5; i++) observations.add(obsAt(productId, "AAAAAAAA000111", "A", new BigDecimal("10")));
        for (var i = 0; i < 5; i++) observations.add(obsAt(productId, "BBBBBBBB000111", "B", new BigDecimal("8")));

        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByMarket(eq(productId), any()))
                .thenReturn(List.of(hhCount("AAAAAAAA000111", 3L), hhCount("BBBBBBBB000111", 1L)));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        var rows = service.bestMarkets(productId, 10, null, null, null, Set.of());

        assertEquals(1, rows.size(), "k-anon must hide market B");
        assertEquals("AAAAAAAA000111", rows.get(0).cnpj());
    }

    @Test
    void bestMarkets_watchedMarketBypassesRadiusFilter() {
        var productId = UUID.randomUUID();
        var observations = new ArrayList<PriceObservation>();
        // distant market — would be excluded by 1km radius — but is in the watchlist
        for (var i = 0; i < 5; i++) observations.add(obsAt(productId, "DDDDDDDD000111", "Distante", new BigDecimal("9")));

        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByMarket(eq(productId), any()))
                .thenReturn(List.of(hhCount("DDDDDDDD000111", 3L)));
        var loc = MarketLocation.builder()
                .cnpj("DDDDDDDD000111").cnpjRoot("DDDDDDDD")
                .latitude(new BigDecimal("-30.0500000")).longitude(new BigDecimal("-51.2200000")) // ~10km away
                .build();
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of("DDDDDDDD000111", loc));

        var rows = service.bestMarkets(productId, 10,
                new BigDecimal("-30.0000000"), new BigDecimal("-51.2000000"),
                1.0, // 1km radius — would normally exclude
                Set.of("DDDDDDDD000111"));

        assertEquals(1, rows.size(), "watched market must bypass radius filter");
        assertTrue(rows.get(0).watching());
    }

    @Test
    void bestMarkets_negativeLimitDoesNotThrow() {
        var productId = UUID.randomUUID();
        var observations = new ArrayList<PriceObservation>();
        for (var i = 0; i < 5; i++) observations.add(obsAt(productId, "AAAAAAAA000111", "A", new BigDecimal("10")));

        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByMarket(eq(productId), any()))
                .thenReturn(List.of(hhCount("AAAAAAAA000111", 3L)));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        // limit=-1 (e.g. ?limit=-1 on the endpoint) must not reach Stream.limit(-1),
        // which throws IllegalArgumentException: -1.
        var rows = service.bestMarkets(productId, -1, null, null, null, Set.of());

        assertTrue(rows.isEmpty(), "non-positive limit must return no rows, not throw");
    }

    private MarketHouseholdCount hhCount(String cnpj, long households) {
        return new MarketHouseholdCount() {
            public String getCnpj() { return cnpj; }
            public long getHouseholds() { return households; }
        };
    }

    private PriceObservation obs(UUID productId, BigDecimal price) {
        return obsAt(productId, "93015006005182", "Mercado X", price);
    }

    private PriceObservation obsAt(UUID productId, String cnpj, String name, BigDecimal price) {
        return PriceObservation.builder()
                .id(UUID.randomUUID())
                .product(Product.builder().id(productId).normalizedName("X").build())
                .marketCnpj(cnpj)
                .marketCnpjRoot(cnpj.substring(0, 8))
                .marketName(name)
                .unitPrice(price)
                .quantity(BigDecimal.ONE)
                .observedAt(LocalDateTime.now())
                .build();
    }
}
