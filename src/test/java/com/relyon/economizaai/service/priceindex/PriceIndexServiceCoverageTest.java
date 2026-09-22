package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.MarketHouseholdCount;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import com.relyon.economizaai.service.notifications.NotificationRuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch coverage beyond {@link PriceIndexServiceTest}: the no-CNPJ write skip,
 * per-item skip rules (excluded / no product / null unitPrice), city/state
 * location snapshotting, normalized-unit-price population, the disabled and
 * empty read paths, and the radius-filter branches of bestMarkets.
 */
@ExtendWith(MockitoExtension.class)
class PriceIndexServiceCoverageTest {

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

    private Receipt receiptWith(String cnpj, ReceiptItem... items) {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).email("u@e").household(household)
                .contributionOptIn(true).build();
        var receipt = Receipt.builder()
                .id(UUID.randomUUID()).user(user).household(household)
                .cnpjEmitente(cnpj).marketName("Mercado X")
                .issuedAt(LocalDateTime.of(2026, Month.APRIL, 1, 9, 0))
                .build();
        for (var item : items) receipt.addItem(item);
        return receipt;
    }

    private Product product() {
        return Product.builder().id(UUID.randomUUID()).normalizedName("Arroz").build();
    }

    private ReceiptItem item(Product product, BigDecimal unitPrice, BigDecimal totalPrice, String unit) {
        return ReceiptItem.builder()
                .id(UUID.randomUUID()).lineNumber(1).rawDescription("X")
                .quantity(BigDecimal.ONE).unit(unit)
                .unitPrice(unitPrice).totalPrice(totalPrice)
                .product(product).build();
    }

    @Test
    void recordContributions_skipsWhenNoMarketCnpj() {
        var receipt = receiptWith(null, item(product(), new BigDecimal("10"), new BigDecimal("10"), "UN"));

        var written = service.recordContributions(receipt);

        assertEquals(0, written);
        verify(observationRepository, never()).save(any());
        verify(marketLocationService, never()).findByCnpjs(anyList());
    }

    @Test
    void recordContributions_skipsExcludedNullProductAndNullUnitPriceItems() {
        var linked = item(product(), new BigDecimal("10"), new BigDecimal("10"), "UN");
        var excluded = item(product(), new BigDecimal("5"), new BigDecimal("5"), "UN");
        excluded.setExcluded(true);
        var noProduct = ReceiptItem.builder()
                .id(UUID.randomUUID()).lineNumber(2).rawDescription("NP")
                .quantity(BigDecimal.ONE).unitPrice(BigDecimal.ONE).totalPrice(BigDecimal.ONE)
                .build();
        var nullUnitPrice = ReceiptItem.builder()
                .id(UUID.randomUUID()).lineNumber(3).rawDescription("NUP")
                .quantity(BigDecimal.ONE).unitPrice(null).totalPrice(BigDecimal.ONE)
                .product(product()).build();
        var receipt = receiptWith("93015006005182", linked, excluded, noProduct, nullUnitPrice);

        when(marketLocationService.findByCnpjs(anyList())).thenReturn(Map.of());
        when(observationRepository.save(any(PriceObservation.class))).thenAnswer(inv -> {
            var obs = inv.<PriceObservation>getArgument(0);
            obs.setId(UUID.randomUUID());
            return obs;
        });

        var written = service.recordContributions(receipt);

        assertEquals(1, written, "only the single valid linked item contributes");
        verify(notificationRuleEngine).evaluate(anyList(), eq(receipt.getHousehold().getId()));
    }

    @Test
    void recordContributions_snapshotsCityAndStateFromLocation() {
        var receipt = receiptWith("93015006005182",
                item(product(), new BigDecimal("10"), new BigDecimal("10"), "UN"));
        var location = MarketLocation.builder()
                .cnpj("93015006005182").cnpjRoot("93015006")
                .city("Porto Alegre").state("RS").build();
        when(marketLocationService.findByCnpjs(anyList()))
                .thenReturn(Map.of("93015006005182", location));

        var observationCaptor = ArgumentCaptor.forClass(PriceObservation.class);
        when(observationRepository.save(observationCaptor.capture())).thenAnswer(inv -> {
            var obs = inv.<PriceObservation>getArgument(0);
            obs.setId(UUID.randomUUID());
            return obs;
        });

        service.recordContributions(receipt);

        var saved = observationCaptor.getValue();
        assertEquals("Porto Alegre", saved.getCity());
        assertEquals("RS", saved.getState());
    }

    @Test
    void recordContributions_nullLocationLeavesCityAndStateNull() {
        var receipt = receiptWith("93015006005182",
                item(product(), new BigDecimal("10"), new BigDecimal("10"), "UN"));
        when(marketLocationService.findByCnpjs(anyList())).thenReturn(Map.of());

        var observationCaptor = ArgumentCaptor.forClass(PriceObservation.class);
        when(observationRepository.save(observationCaptor.capture())).thenAnswer(inv -> {
            var obs = inv.<PriceObservation>getArgument(0);
            obs.setId(UUID.randomUUID());
            return obs;
        });

        service.recordContributions(receipt);

        assertNull(observationCaptor.getValue().getCity());
        assertNull(observationCaptor.getValue().getState());
    }

    @Test
    void recordContributions_populatesNormalizedUnitPriceWhenItemUnitIsBase() {
        // unit "kg" → normalizable on path 1, no pack info needed.
        var receipt = receiptWith("93015006005182",
                item(product(), new BigDecimal("10"), new BigDecimal("10"), "kg"));
        when(marketLocationService.findByCnpjs(anyList())).thenReturn(Map.of());

        var observationCaptor = ArgumentCaptor.forClass(PriceObservation.class);
        when(observationRepository.save(observationCaptor.capture())).thenAnswer(inv -> {
            var obs = inv.<PriceObservation>getArgument(0);
            obs.setId(UUID.randomUUID());
            return obs;
        });

        service.recordContributions(receipt);

        var saved = observationCaptor.getValue();
        assertNotNull(saved.getNormalizedUnitPrice());
        assertEquals("KG", saved.getNormalizedUnit());
    }

    @Test
    void recordContributions_normalizedFieldsNullWhenNotNormalizable() {
        // unit "UN" with no pack size → path 2 fails → normalized stays null.
        var receipt = receiptWith("93015006005182",
                item(product(), new BigDecimal("10"), new BigDecimal("10"), "UN"));
        when(marketLocationService.findByCnpjs(anyList())).thenReturn(Map.of());

        var observationCaptor = ArgumentCaptor.forClass(PriceObservation.class);
        when(observationRepository.save(observationCaptor.capture())).thenAnswer(inv -> {
            var obs = inv.<PriceObservation>getArgument(0);
            obs.setId(UUID.randomUUID());
            return obs;
        });

        service.recordContributions(receipt);

        assertNull(observationCaptor.getValue().getNormalizedUnitPrice());
        assertNull(observationCaptor.getValue().getNormalizedUnit());
    }

    @Test
    void recordContributions_nullIssuedAtFallsBackToNowForObservedAt() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        var user = User.builder().id(UUID.randomUUID()).email("u@e").household(household)
                .contributionOptIn(true).build();
        var receipt = Receipt.builder()
                .id(UUID.randomUUID()).user(user).household(household)
                .cnpjEmitente("93015006005182").marketName("Mercado X")
                .issuedAt(null).build();
        receipt.addItem(item(product(), new BigDecimal("10"), new BigDecimal("10"), "UN"));
        when(marketLocationService.findByCnpjs(anyList())).thenReturn(Map.of());

        var observationCaptor = ArgumentCaptor.forClass(PriceObservation.class);
        when(observationRepository.save(observationCaptor.capture())).thenAnswer(inv -> {
            var obs = inv.<PriceObservation>getArgument(0);
            obs.setId(UUID.randomUUID());
            return obs;
        });

        service.recordContributions(receipt);

        assertNotNull(observationCaptor.getValue().getObservedAt());
    }

    @Test
    void referencePrice_returnsEmptyWhenCollaborativeDisabled() {
        properties.getCollaborative().setEnabled(false);

        var ref = service.referencePrice(UUID.randomUUID(), "123");

        assertFalse(ref.hasData());
        assertEquals(0, ref.sampleCount());
        verify(observationRepository, never()).findRecentByProductAndMarket(any(), any(), any());
    }

    @Test
    void referencePrice_belowSampleThresholdSurfacesCountsButNullPrice() {
        var productId = UUID.randomUUID();
        // 1 observation < min samples → blocked, but counts surfaced.
        when(observationRepository.findRecentByProductAndMarket(eq(productId), eq("123"), any()))
                .thenReturn(List.of(obs(productId, new BigDecimal("10"))));
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq("123"), any()))
                .thenReturn(1L);

        var ref = service.referencePrice(productId, "123");

        assertFalse(ref.hasData());
        assertTrue(ref.kAnonBlocked());
        assertEquals(1, ref.sampleCount());
        assertNull(ref.medianPrice());
    }

    @Test
    void bestMarkets_returnsEmptyWhenCollaborativeDisabled() {
        properties.getCollaborative().setEnabled(false);

        var rows = service.bestMarkets(UUID.randomUUID(), 5, null, null, null, Set.of());

        assertTrue(rows.isEmpty());
        verify(observationRepository, never()).findRecentByProduct(any(), any());
    }

    @Test
    void bestMarkets_returnsEmptyWhenNoObservations() {
        var productId = UUID.randomUUID();
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(List.of());

        var rows = service.bestMarkets(productId, 5, null, null, null, Set.of());

        assertTrue(rows.isEmpty());
    }

    private MarketHouseholdCount hhCount(String cnpj, long households) {
        return new MarketHouseholdCount() {
            public String getCnpj() { return cnpj; }
            public long getHouseholds() { return households; }
        };
    }

    @Test
    void bestMarkets_radiusExcludesDistantUnwatchedMarket() {
        var productId = UUID.randomUUID();
        var observations = new ArrayList<PriceObservation>();
        for (var index = 0; index < 5; index++) {
            observations.add(obsAt(productId, "DDDDDDDD000111", "Distante", new BigDecimal("9")));
        }
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByMarket(eq(productId), any()))
                .thenReturn(List.of(hhCount("DDDDDDDD000111", 3L)));
        var location = MarketLocation.builder()
                .cnpj("DDDDDDDD000111").cnpjRoot("DDDDDDDD")
                .latitude(new BigDecimal("-30.0500000")).longitude(new BigDecimal("-51.2200000"))
                .build();
        when(marketLocationService.findByCnpjs(anyList())).thenReturn(Map.of("DDDDDDDD000111", location));

        var rows = service.bestMarkets(productId, 10,
                new BigDecimal("-30.0000000"), new BigDecimal("-51.2000000"),
                1.0, Set.of()); // 1km radius, not watched → excluded

        assertTrue(rows.isEmpty());
    }

    @Test
    void bestMarkets_withinRadiusIncludesMarketAndComputesDistance() {
        var productId = UUID.randomUUID();
        var observations = new ArrayList<PriceObservation>();
        for (var index = 0; index < 5; index++) {
            observations.add(obsAt(productId, "EEEEEEEE000111", "Perto", new BigDecimal("7")));
        }
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByMarket(eq(productId), any()))
                .thenReturn(List.of(hhCount("EEEEEEEE000111", 3L)));
        var location = MarketLocation.builder()
                .cnpj("EEEEEEEE000111").cnpjRoot("EEEEEEEE")
                .latitude(new BigDecimal("-30.0001000")).longitude(new BigDecimal("-51.2000100"))
                .build();
        when(marketLocationService.findByCnpjs(anyList())).thenReturn(Map.of("EEEEEEEE000111", location));

        var rows = service.bestMarkets(productId, 10,
                new BigDecimal("-30.0000000"), new BigDecimal("-51.2000000"),
                50.0, Set.of());

        assertEquals(1, rows.size());
        assertNotNull(rows.get(0).distanceKm());
        assertFalse(rows.get(0).watching());
    }

    @Test
    void bestMarkets_radiusWithUserLocationButNoMarketCoordsExcludesUnwatched() {
        var productId = UUID.randomUUID();
        var observations = new ArrayList<PriceObservation>();
        for (var index = 0; index < 5; index++) {
            observations.add(obsAt(productId, "FFFFFFFF000111", "SemCoord", new BigDecimal("6")));
        }
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByMarket(eq(productId), any()))
                .thenReturn(List.of(hhCount("FFFFFFFF000111", 3L)));
        // No location entry → location is null → second else-branch excludes when radius set.
        when(marketLocationService.findByCnpjs(anyList())).thenReturn(Map.of());

        var rows = service.bestMarkets(productId, 10,
                new BigDecimal("-30.0000000"), new BigDecimal("-51.2000000"),
                10.0, Set.of());

        assertTrue(rows.isEmpty());
    }

    @Test
    void bestMarkets_nullWatchedCnpjsTreatedAsEmptySet() {
        var productId = UUID.randomUUID();
        var observations = new ArrayList<PriceObservation>();
        for (var index = 0; index < 5; index++) {
            observations.add(obsAt(productId, "GGGGGGGG000111", "Loja", new BigDecimal("4")));
        }
        when(observationRepository.findRecentByProduct(eq(productId), any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductByMarket(eq(productId), any()))
                .thenReturn(List.of(hhCount("GGGGGGGG000111", 3L)));
        when(marketLocationService.findByCnpjs(anyList())).thenReturn(Map.of());

        var rows = service.bestMarkets(productId, 10, null, null, null, null);

        assertEquals(1, rows.size());
        assertFalse(rows.get(0).watching());
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
