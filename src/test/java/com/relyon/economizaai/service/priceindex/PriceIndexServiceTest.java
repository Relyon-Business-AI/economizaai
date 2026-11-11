package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.service.geo.NearbyScope;
import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.PriceObservationAudit;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.ReceiptChannel;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import org.mockito.ArgumentCaptor;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.ChainHouseholdCount;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    @Mock private ReceiptRepository receiptRepository;
    @Mock private PrivacyTierResolver privacyTierResolver;

    private CollaborativeProperties properties;
    private PriceIndexService service;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        service = new PriceIndexService(observationRepository, auditRepository, properties,
                marketLocationService, merchantSupportGate, notificationRuleEngine, receiptRepository,
                privacyTierResolver);
        lenient().when(merchantSupportGate.contributesToIndex(any())).thenReturn(true);
        // Default tier for these tests: the full K (3 households / 3 observations), i.e. the
        // sensitive thresholds — the grocery-tier tests override it explicitly.
        lenient().when(privacyTierResolver.thresholdsFor(anyBoolean()))
                .thenReturn(new PrivacyTierResolver.Thresholds(3, 3));
    }

    @Test
    void recordContributions_truncatesObservedAtToTheDay() {
        var product = product();
        var receipt = buildConfirmedReceipt(true, itemWithProduct(product, new BigDecimal("10")));
        receipt.setIssuedAt(LocalDateTime.of(2026, 10, 30, 18, 42, 17));
        var captor = ArgumentCaptor.forClass(PriceObservation.class);
        when(observationRepository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        service.recordContributions(receipt);

        assertEquals(LocalDateTime.of(2026, 10, 30, 0, 0), captor.getValue().getObservedAt(),
                "observedAt must carry day precision only — minute-exact stamps re-group a receipt");
    }

    @Test
    void bestMarkets_groceryTierShowsASingleHouseholdChain() {
        var productId = UUID.randomUUID();
        var observations = List.of(obsAt(productId, "AAAAAAAA000111", "Loja", new BigDecimal("4.99")));
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByChain(eq(productId), any()))
                .thenReturn(List.of(chainCount("AAAAAAAA", 1L)));
        when(marketLocationService.findByCnpjs(any()))
                .thenReturn(Map.of("AAAAAAAA000111", supermarket("AAAAAAAA000111")));
        when(privacyTierResolver.isSensitiveProduct(productId)).thenReturn(false);
        when(privacyTierResolver.thresholdsFor(false)).thenReturn(new PrivacyTierResolver.Thresholds(1, 1));

        var rows = service.bestMarkets(productId, 10, null, null, null, Set.of());

        assertEquals(1, rows.size(), "grocery tier: n=1 is disclosable");
        assertEquals(1L, rows.get(0).distinctHouseholds());
        assertEquals(0, new BigDecimal("4.99").compareTo(rows.get(0).latestPrice()));
    }

    @Test
    void bestMarkets_chainWithAnUnclassifiedStoreKeepsTheFullK() {
        var productId = UUID.randomUUID();
        var observations = List.of(obsAt(productId, "UUUUUUUU000111", "Loja nova", new BigDecimal("4.99")));
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByChain(eq(productId), any()))
                .thenReturn(List.of(chainCount("UUUUUUUU", 2L)));
        // No location row for the store: its segment is unknown.
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(privacyTierResolver.isSensitiveProduct(productId)).thenReturn(false);

        var rows = service.bestMarkets(productId, 10, null, null, null, Set.of());

        assertTrue(rows.isEmpty(), "an unclassified store must stay under the full K (2 households < 3)");
        verify(privacyTierResolver).thresholdsFor(true);
        verify(privacyTierResolver, never()).thresholdsFor(false);
    }

    @Test
    void referencePrice_unclassifiedStoreUsesTheFullK() {
        var productId = UUID.randomUUID();
        var cnpj = "UUUUUUUU000111";
        when(observationRepository.findRecentByProductAndMarket(eq(productId), eq(cnpj), any()))
                .thenReturn(List.of(obsAt(productId, cnpj, "Loja nova", new BigDecimal("4.99"))));
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq(cnpj), any())).thenReturn(1L);
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());
        when(privacyTierResolver.isSensitiveProduct(productId)).thenReturn(false);

        var reference = service.referencePrice(productId, cnpj);

        assertTrue(reference.kAnonBlocked());
        assertNull(reference.medianPrice());
        verify(privacyTierResolver).thresholdsFor(true);
    }

    @Test
    void bestMarkets_pharmacyChainKeepsTheFullKEvenForAGroceryProduct() {
        var productId = UUID.randomUUID();
        var observations = List.of(obsAt(productId, "PPPPPPPP000111", "Farmacia", new BigDecimal("4.99")));
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByChain(eq(productId), any()))
                .thenReturn(List.of(chainCount("PPPPPPPP", 1L)));
        var pharmacy = MarketLocation.builder().cnpj("PPPPPPPP000111").cnpjRoot("PPPPPPPP")
                .segment(MerchantSegment.PHARMACY).build();
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of("PPPPPPPP000111", pharmacy));
        when(privacyTierResolver.isSensitiveProduct(productId)).thenReturn(false);
        when(privacyTierResolver.thresholdsFor(true)).thenReturn(new PrivacyTierResolver.Thresholds(3, 3));

        var rows = service.bestMarkets(productId, 10, null, null, null, Set.of());

        assertTrue(rows.isEmpty(), "a pharmacy chain must stay under the full K");
        verify(privacyTierResolver).thresholdsFor(true);
        verify(privacyTierResolver, never()).thresholdsFor(false);
    }

    // Porto Alegre viewer; one chain in POA (~2 km), one in Santo Augusto (~400 km), one in PR.
    private static final BigDecimal POA_LAT = new BigDecimal("-30.0346");
    private static final BigDecimal POA_LNG = new BigDecimal("-51.2177");

    private void stubThreeChainsAcrossRegions(UUID productId) {
        var poa = obsAt(productId, "AAAAAAAA000111", "Mercado POA", new BigDecimal("5.49"));
        poa.setState("RS");
        var santoAugusto = obsAt(productId, "BBBBBBBB000111", "Super Santi", new BigDecimal("4.99"));
        santoAugusto.setState("RS");
        var parana = obsAt(productId, "CCCCCCCC000111", "Muffato", new BigDecimal("4.49"));
        parana.setState("PR");
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(List.of(poa, santoAugusto, parana));
        when(auditRepository.countDistinctHouseholdsForProductByChain(eq(productId), any()))
                .thenReturn(List.of(chainCount("AAAAAAAA", 1L), chainCount("BBBBBBBB", 1L), chainCount("CCCCCCCC", 1L)));
        lenient().when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of(
                "AAAAAAAA000111", located("AAAAAAAA000111", "-30.0500", "-51.2000"),
                "BBBBBBBB000111", located("BBBBBBBB000111", "-27.8500", "-53.7800"),
                "CCCCCCCC000111", located("CCCCCCCC000111", "-25.4300", "-49.2700")));
        when(privacyTierResolver.thresholdsFor(false)).thenReturn(new PrivacyTierResolver.Thresholds(1, 1));
    }

    private MarketLocation located(String cnpj, String latitude, String longitude) {
        return MarketLocation.builder().cnpj(cnpj).cnpjRoot(cnpj.substring(0, 8))
                .latitude(new BigDecimal(latitude)).longitude(new BigDecimal(longitude))
                .segment(MerchantSegment.SUPERMARKET).build();
    }

    private MarketLocation supermarket(String cnpj) {
        return MarketLocation.builder().cnpj(cnpj).cnpjRoot(cnpj.substring(0, 8))
                .segment(MerchantSegment.SUPERMARKET).build();
    }

    private static List<String> chainNames(List<PriceIndexService.MarketPriceRow> rows) {
        return rows.stream().map(PriceIndexService.MarketPriceRow::marketName).toList();
    }

    @Test
    void bestMarketsForViewer_withLocationKeepsOnlyChainsWithinTheNearbyRadius() {
        var productId = UUID.randomUUID();
        stubThreeChainsAcrossRegions(productId);
        var location = new ViewerLocation(POA_LAT, POA_LNG, ViewerLocationService.Source.RECEIPTS_MEDIAN, "RS");

        var rows = service.bestMarketsForViewer(productId, 10, location, Set.of());

        assertEquals(List.of("Mercado POA"), chainNames(rows),
                "a Porto Alegre shopper must not see a chain 400 km away, even if cheaper and same state");
    }

    @Test
    void bestMarketsForViewer_withLocationStillShowsAWatchedFarChain() {
        var productId = UUID.randomUUID();
        stubThreeChainsAcrossRegions(productId);
        var location = new ViewerLocation(POA_LAT, POA_LNG, ViewerLocationService.Source.PROFILE, "RS");

        var rows = service.bestMarketsForViewer(productId, 10, location, Set.of("BBBBBBBB000111"));

        assertEquals(List.of("Super Santi", "Mercado POA"), chainNames(rows), "watched first, then nearby");
    }

    @Test
    void bestMarketsInScope_honoursTheScopeRadius() {
        var productId = UUID.randomUUID();
        stubThreeChainsAcrossRegions(productId);
        var location = new ViewerLocation(POA_LAT, POA_LNG, ViewerLocationService.Source.PROFILE, "RS");

        var rows = service.bestMarketsInScope(productId, 10, new NearbyScope(location, Set.of(), 500));

        assertEquals(List.of("Super Santi", "Mercado POA"), chainNames(rows), "500 km reaches Santo Augusto, not Curitiba");
    }

    @Test
    void bestMarketsAtWatched_onlyWatchedChains_emptyWhenNoneWatched() {
        var productId = UUID.randomUUID();
        stubThreeChainsAcrossRegions(productId);

        assertEquals(List.of("Muffato"), chainNames(service.bestMarketsAtWatched(productId, 10, Set.of("CCCCCCCC000111"))));
        assertTrue(service.bestMarketsAtWatched(productId, 10, Set.of()).isEmpty());
    }

    @Test
    void bestMarketsForViewer_withoutLocationFallsBackToTheViewerState() {
        var productId = UUID.randomUUID();
        stubThreeChainsAcrossRegions(productId);
        var location = new ViewerLocation(null, null, ViewerLocationService.Source.NONE, "RS");

        var rows = service.bestMarketsForViewer(productId, 10, location, Set.of());

        assertEquals(List.of("Super Santi", "Mercado POA"), chainNames(rows), "RS chains only, cheapest first");
    }

    @Test
    void bestMarketsForViewer_withoutLocationButWatchingShowsOnlyWatchedChains() {
        var productId = UUID.randomUUID();
        stubThreeChainsAcrossRegions(productId);
        var location = new ViewerLocation(null, null, ViewerLocationService.Source.NONE, "RS");

        var rows = service.bestMarketsForViewer(productId, 10, location, Set.of("AAAAAAAA000111"));

        assertEquals(List.of("Mercado POA"), chainNames(rows));
    }

    @Test
    void bestMarketsForViewer_withNothingKnownIsNational() {
        var productId = UUID.randomUUID();
        stubThreeChainsAcrossRegions(productId);
        var location = new ViewerLocation(null, null, ViewerLocationService.Source.NONE, null);

        var rows = service.bestMarketsForViewer(productId, 10, location, Set.of());

        assertEquals(3, rows.size());
    }

    @Test
    void bestMarkets_nearestSortReordersButBadgesStayPersistent() {
        var productId = UUID.randomUUID();
        stubThreeChainsAcrossRegions(productId);
        // National scope (explicit position, no radius) so all three chains appear.
        var cheapest = service.bestMarkets(productId, 10, POA_LAT, POA_LNG, null, Set.of(), ComparisonSort.CHEAPEST);
        // CHEAPEST: price asc → Muffato 4.49, Super Santi 4.99, Mercado POA 5.49.
        assertEquals(List.of("Muffato", "Super Santi", "Mercado POA"), chainNames(cheapest));
        var poaRow = cheapest.stream().filter(row -> row.cnpjRoot().equals("AAAAAAAA")).findFirst().orElseThrow();
        var muffatoRow = cheapest.stream().filter(row -> row.cnpjRoot().equals("CCCCCCCC")).findFirst().orElseThrow();
        // Badges over the whole list: Muffato cheapest; Mercado POA nearest to the POA viewer.
        assertTrue(muffatoRow.isCheapest());
        assertFalse(muffatoRow.isNearest());
        assertTrue(poaRow.isNearest());
        assertFalse(poaRow.isCheapest());

        var nearest = service.bestMarkets(productId, 10, POA_LAT, POA_LNG, null, Set.of(), ComparisonSort.NEAREST);
        // NEAREST: Mercado POA (~2 km) first; badges unchanged.
        assertEquals("Mercado POA", nearest.get(0).marketName());
        assertTrue(nearest.get(0).isNearest());
        assertTrue(nearest.stream().filter(row -> row.cnpjRoot().equals("CCCCCCCC")).findFirst().orElseThrow().isCheapest());
    }

    @Test
    void bestMarketsForViewer_stateScopePicksTheInStateStoreButPricesTheWholeChain() {
        var productId = UUID.randomUUID();
        var rsStore = obsAt(productId, "DDDDDDDD000111", "Rede RS", new BigDecimal("6.00"));
        rsStore.setState("RS");
        var scStore = obsAt(productId, "DDDDDDDD000222", "Rede SC", new BigDecimal("4.00"));
        scStore.setState("SC");
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(List.of(rsStore, scStore));
        when(auditRepository.countDistinctHouseholdsForProductByChain(eq(productId), any()))
                .thenReturn(List.of(chainCount("DDDDDDDD", 2L)));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of(
                "DDDDDDDD000111", supermarket("DDDDDDDD000111"), "DDDDDDDD000222", supermarket("DDDDDDDD000222")));
        when(privacyTierResolver.thresholdsFor(false)).thenReturn(new PrivacyTierResolver.Thresholds(1, 1));
        var location = new ViewerLocation(null, null, ViewerLocationService.Source.NONE, "RS");

        var rows = service.bestMarketsForViewer(productId, 10, location, Set.of());

        assertEquals(1, rows.size());
        assertEquals("DDDDDDDD000111", rows.get(0).cnpj(), "represented by its in-state store");
        assertEquals(2, rows.get(0).sampleCount(),
                "price stays chain-wide: the k-anon household count is chain-wide, never a filtered subset");
    }

    @Test
    void inStoreReferencePrice_sensitiveProductUsesTheFullK() {
        var productId = UUID.randomUUID();
        var householdId = UUID.randomUUID();
        when(receiptRepository.findConfirmedUfsForHousehold(householdId)).thenReturn(List.of());
        when(observationRepository.findRecentByProduct(eq(productId), any()))
                .thenReturn(List.of(obs(productId, new BigDecimal("8"))));
        when(auditRepository.countDistinctInStoreHouseholdsForProduct(eq(productId), any())).thenReturn(1L);
        when(privacyTierResolver.isSensitiveProduct(productId)).thenReturn(true);

        var result = service.inStoreReferencePrice(productId, householdId);

        assertTrue(result.reference().kAnonBlocked());
        verify(privacyTierResolver).thresholdsFor(true);
        // A sensitive product short-circuits the tier decision — no market lookup needed.
        verify(marketLocationService, never()).findByCnpjs(any());
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
    void referencePrice_evenSampleMedianComesBackAtScale2() {
        var productId = UUID.randomUUID();
        // Even count: median = (10.05 + 10.10) / 2 = 10.075 — the API must round
        // to 2 decimals (R$), never leak a scale-4 half-cent value.
        var observations = List.of(
                obs(productId, new BigDecimal("10.00")), obs(productId, new BigDecimal("10.05")),
                obs(productId, new BigDecimal("10.10")), obs(productId, new BigDecimal("10.20"))
        );
        when(observationRepository.findRecentByProductAndMarket(eq(productId), eq("123"), any()))
                .thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq("123"), any()))
                .thenReturn(3L);

        var ref = service.referencePrice(productId, "123");

        assertEquals(new BigDecimal("10.08"), ref.medianPrice());
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
        when(auditRepository.countDistinctHouseholdsForProductByChain(eq(productId), any()))
                .thenReturn(List.of(chainCount("AAAAAAAA", 3L), chainCount("BBBBBBBB", 1L)));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        var rows = service.bestMarkets(productId, 10, null, null, null, Set.of());

        assertEquals(1, rows.size(), "k-anon must hide market B");
        assertEquals("AAAAAAAA000111", rows.get(0).cnpj());
    }

    @Test
    void bestMarkets_poolsStoresOfTheSameChainIntoOneRow() {
        var productId = UUID.randomUUID();
        // Two stores of chain AAAAAAAA with 3 obs each: per store neither reaches the
        // 5-observation floor nor K; pooled at chain level they do (K comes from the chain query).
        var observations = new ArrayList<PriceObservation>();
        for (var index = 0; index < 3; index++) observations.add(obsAt(productId, "AAAAAAAA000111", "Loja Centro", new BigDecimal("10")));
        for (var index = 0; index < 3; index++) observations.add(obsAt(productId, "AAAAAAAA000222", "Loja Norte", new BigDecimal("12")));
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByChain(eq(productId), any()))
                .thenReturn(List.of(chainCount("AAAAAAAA", 3L)));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        var rows = service.bestMarkets(productId, 10, null, null, null, Set.of());

        assertEquals(1, rows.size(), "stores of one chain must collapse into a single row");
        assertEquals("AAAAAAAA", rows.get(0).cnpjRoot());
        assertEquals(6, rows.get(0).sampleCount());
        assertEquals(3L, rows.get(0).distinctHouseholds());
        assertEquals(0, new BigDecimal("11.00").compareTo(rows.get(0).medianPrice()));
    }

    @Test
    void bestMarkets_chainRowShowsClosestStoreToViewer() {
        var productId = UUID.randomUUID();
        var observations = new ArrayList<PriceObservation>();
        for (var index = 0; index < 3; index++) observations.add(obsAt(productId, "AAAAAAAA000111", "Loja Longe", new BigDecimal("10")));
        for (var index = 0; index < 3; index++) observations.add(obsAt(productId, "AAAAAAAA000222", "Loja Perto", new BigDecimal("10")));
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByChain(eq(productId), any()))
                .thenReturn(List.of(chainCount("AAAAAAAA", 3L)));
        var farStore = MarketLocation.builder().cnpj("AAAAAAAA000111").cnpjRoot("AAAAAAAA")
                .latitude(new BigDecimal("-30.0500000")).longitude(new BigDecimal("-51.2200000")).build();
        var nearStore = MarketLocation.builder().cnpj("AAAAAAAA000222").cnpjRoot("AAAAAAAA")
                .latitude(new BigDecimal("-30.0001000")).longitude(new BigDecimal("-51.2000100")).build();
        when(marketLocationService.findByCnpjs(any()))
                .thenReturn(Map.of("AAAAAAAA000111", farStore, "AAAAAAAA000222", nearStore));

        var rows = service.bestMarkets(productId, 10,
                new BigDecimal("-30.0000000"), new BigDecimal("-51.2000000"), null, Set.of());

        assertEquals(1, rows.size());
        assertEquals("AAAAAAAA000222", rows.get(0).cnpj(), "the chain row must be shown as its closest store");
        assertEquals("Loja Perto", rows.get(0).marketName());
        assertTrue(rows.get(0).distanceKm() < 1.0);
    }

    @Test
    void bestMarkets_chainRowPrefersWatchedStoreOverCloserOne() {
        var productId = UUID.randomUUID();
        var observations = new ArrayList<PriceObservation>();
        for (var index = 0; index < 3; index++) observations.add(obsAt(productId, "AAAAAAAA000111", "Loja Acompanhada", new BigDecimal("10")));
        for (var index = 0; index < 3; index++) observations.add(obsAt(productId, "AAAAAAAA000222", "Loja Perto", new BigDecimal("10")));
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByChain(eq(productId), any()))
                .thenReturn(List.of(chainCount("AAAAAAAA", 3L)));
        var farStore = MarketLocation.builder().cnpj("AAAAAAAA000111").cnpjRoot("AAAAAAAA")
                .latitude(new BigDecimal("-30.0500000")).longitude(new BigDecimal("-51.2200000")).build();
        var nearStore = MarketLocation.builder().cnpj("AAAAAAAA000222").cnpjRoot("AAAAAAAA")
                .latitude(new BigDecimal("-30.0001000")).longitude(new BigDecimal("-51.2000100")).build();
        when(marketLocationService.findByCnpjs(any()))
                .thenReturn(Map.of("AAAAAAAA000111", farStore, "AAAAAAAA000222", nearStore));

        var rows = service.bestMarkets(productId, 10,
                new BigDecimal("-30.0000000"), new BigDecimal("-51.2000000"), 1.0, Set.of("AAAAAAAA000111"));

        assertEquals(1, rows.size(), "a watched store keeps the chain visible even outside the radius");
        assertEquals("AAAAAAAA000111", rows.get(0).cnpj());
        assertTrue(rows.get(0).watching());
    }

    @Test
    void inStoreReferencePrice_usesHouseholdStatesWhenThatSliceReachesK() {
        var productId = UUID.randomUUID();
        var householdId = UUID.randomUUID();
        var regional = List.of(obs(productId, new BigDecimal("10")), obs(productId, new BigDecimal("12")),
                obs(productId, new BigDecimal("11")));
        when(receiptRepository.findConfirmedUfsForHousehold(householdId))
                .thenReturn(List.of(UnidadeFederativa.RS, UnidadeFederativa.SC));
        when(observationRepository.findRecentByProductAndStates(eq(productId), eq(List.of("RS", "SC")), any()))
                .thenReturn(regional);
        when(auditRepository.countDistinctInStoreHouseholdsForProductInStates(eq(productId), eq(List.of("RS", "SC")), any()))
                .thenReturn(3L);

        var result = service.inStoreReferencePrice(productId, householdId);

        assertEquals(PriceIndexService.ReferenceScope.STATE, result.scope());
        assertEquals(List.of("RS", "SC"), result.states());
        assertTrue(result.reference().hasData());
        assertEquals(new BigDecimal("11.00"), result.reference().medianPrice());
        verify(observationRepository, never()).findRecentByProduct(any(), any());
    }

    @Test
    void inStoreReferencePrice_fallsBackToNationalWhenStateSliceIsBelowK() {
        var productId = UUID.randomUUID();
        var householdId = UUID.randomUUID();
        var regional = List.of(obs(productId, new BigDecimal("10")), obs(productId, new BigDecimal("12")),
                obs(productId, new BigDecimal("11")));
        var national = List.of(obs(productId, new BigDecimal("8")), obs(productId, new BigDecimal("9")),
                obs(productId, new BigDecimal("10")), obs(productId, new BigDecimal("11")));
        when(receiptRepository.findConfirmedUfsForHousehold(householdId)).thenReturn(List.of(UnidadeFederativa.RS));
        when(observationRepository.findRecentByProductAndStates(eq(productId), eq(List.of("RS")), any()))
                .thenReturn(regional);
        when(auditRepository.countDistinctInStoreHouseholdsForProductInStates(eq(productId), eq(List.of("RS")), any()))
                .thenReturn(2L);
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(national);
        when(auditRepository.countDistinctInStoreHouseholdsForProduct(eq(productId), any())).thenReturn(4L);

        var result = service.inStoreReferencePrice(productId, householdId);

        assertEquals(PriceIndexService.ReferenceScope.NATIONAL, result.scope());
        assertTrue(result.states().isEmpty());
        assertTrue(result.reference().hasData());
        assertEquals(4, result.reference().sampleCount());
    }

    @Test
    void inStoreReferencePrice_householdWithoutReceiptsGoesStraightToNationalAndStaysBlockedBelowK() {
        var productId = UUID.randomUUID();
        var householdId = UUID.randomUUID();
        when(receiptRepository.findConfirmedUfsForHousehold(householdId)).thenReturn(List.of());
        when(observationRepository.findRecentByProduct(eq(productId), any()))
                .thenReturn(List.of(obs(productId, new BigDecimal("8")), obs(productId, new BigDecimal("9")),
                        obs(productId, new BigDecimal("10"))));
        when(auditRepository.countDistinctInStoreHouseholdsForProduct(eq(productId), any())).thenReturn(1L);

        var result = service.inStoreReferencePrice(productId, householdId);

        assertEquals(PriceIndexService.ReferenceScope.NATIONAL, result.scope());
        assertFalse(result.reference().hasData());
        assertTrue(result.reference().kAnonBlocked());
        assertEquals(3, result.reference().sampleCount());
        assertEquals(1L, result.reference().distinctHouseholds());
        verify(observationRepository, never()).findRecentByProductAndStates(any(), any(), any());
    }

    @Test
    void inStoreReferencePrice_returnsEmptyWhenCollaborativeDisabled() {
        properties.getCollaborative().setEnabled(false);

        var result = service.inStoreReferencePrice(UUID.randomUUID(), UUID.randomUUID());

        assertFalse(result.reference().hasData());
        verify(receiptRepository, never()).findConfirmedUfsForHousehold(any());
    }

    @Test
    void bestMarkets_watchedMarketBypassesRadiusFilter() {
        var productId = UUID.randomUUID();
        var observations = new ArrayList<PriceObservation>();
        // distant market — would be excluded by 1km radius — but is in the watchlist
        for (var i = 0; i < 5; i++) observations.add(obsAt(productId, "DDDDDDDD000111", "Distante", new BigDecimal("9")));

        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByChain(eq(productId), any()))
                .thenReturn(List.of(chainCount("DDDDDDDD", 3L)));
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
        when(auditRepository.countDistinctHouseholdsForProductByChain(eq(productId), any()))
                .thenReturn(List.of(chainCount("AAAAAAAA", 3L)));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of());

        // limit=-1 (e.g. ?limit=-1 on the endpoint) must not reach Stream.limit(-1),
        // which throws IllegalArgumentException: -1.
        var rows = service.bestMarkets(productId, -1, null, null, null, Set.of());

        assertTrue(rows.isEmpty(), "non-positive limit must return no rows, not throw");
    }

    private ChainHouseholdCount chainCount(String cnpjRoot, long households) {
        return new ChainHouseholdCount() {
            public String getCnpjRoot() { return cnpjRoot; }
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
