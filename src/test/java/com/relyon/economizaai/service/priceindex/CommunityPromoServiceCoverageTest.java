package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.service.geo.MarketLocationService;
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
 * Branch coverage beyond {@link CommunityPromoServiceTest}: distance/radius
 * filtering, watched-market bypass, normalized-vs-raw price selection,
 * baseline promo-flag exclusion, and the volume/empty gates.
 */
@ExtendWith(MockitoExtension.class)
class CommunityPromoServiceCoverageTest {

    @Mock private PriceObservationRepository observationRepository;
    @Mock private PriceObservationAuditRepository auditRepository;
    @Mock private MarketLocationService marketLocationService;

    private CollaborativeProperties properties;
    private CommunityPromoService service;

    private final UUID productId = UUID.randomUUID();
    private final String marketCnpj = "93015006005182";

    private final BigDecimal portoAlegreLat = new BigDecimal("-30.0331");
    private final BigDecimal portoAlegreLng = new BigDecimal("-51.2300");
    private final BigDecimal saoPauloLat = new BigDecimal("-23.5505");
    private final BigDecimal saoPauloLng = new BigDecimal("-46.6333");

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        // make the volume gate easy to satisfy so we focus on the new branches
        properties.getCollaborative().setMinObservationsForCommunityPromo(5);
        service = new CommunityPromoService(observationRepository, auditRepository, properties, marketLocationService);
    }

    private PriceObservation rawObservation(BigDecimal price, LocalDateTime observedAt) {
        return PriceObservation.builder()
                .id(UUID.randomUUID())
                .product(Product.builder().id(productId).normalizedName("Arroz").build())
                .marketCnpj(marketCnpj)
                .marketCnpjRoot(marketCnpj.substring(0, 8))
                .marketName("Mercado X")
                .unitPrice(price)
                .quantity(BigDecimal.ONE)
                .observedAt(observedAt)
                .build();
    }

    private PriceObservation normalizedObservation(BigDecimal rawPrice, BigDecimal normalizedPrice, LocalDateTime observedAt) {
        return PriceObservation.builder()
                .id(UUID.randomUUID())
                .product(Product.builder().id(productId).normalizedName("Arroz").build())
                .marketCnpj(marketCnpj)
                .marketCnpjRoot(marketCnpj.substring(0, 8))
                .marketName("Mercado X")
                .unitPrice(rawPrice)
                .normalizedUnitPrice(normalizedPrice)
                .quantity(BigDecimal.ONE)
                .observedAt(observedAt)
                .build();
    }

    private MarketLocation marketAt(BigDecimal lat, BigDecimal lng) {
        return MarketLocation.builder().cnpj(marketCnpj).latitude(lat).longitude(lng).build();
    }

    private List<PriceObservation> promoShapedRaw() {
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        for (var index = 0; index < 8; index++) observations.add(rawObservation(new BigDecimal("28"), now.minusDays(30)));
        for (var index = 0; index < 5; index++) observations.add(rawObservation(new BigDecimal("22"), now.minusDays(2)));
        return observations;
    }

    @Test
    void emptyObservations_returnsEmptyWithoutAudit() {
        when(observationRepository.findRecent(any())).thenReturn(List.of());

        assertTrue(service.detectAll().isEmpty());
        verify(auditRepository, never()).countDistinctHouseholdsForProductMarket(any(), any(), any());
    }

    @Test
    void belowMinObservationsVolumeGate_skipsGroup() {
        properties.getCollaborative().setMinObservationsForCommunityPromo(100);
        when(observationRepository.findRecent(any())).thenReturn(promoShapedRaw());

        assertTrue(service.detectAll().isEmpty());
        verify(auditRepository, never()).countDistinctHouseholdsForProductMarket(any(), any(), any());
    }

    @Test
    void baselineTooSmall_skipsGroup() {
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        // only 2 baseline rows (< 3) but enough recent rows to pass the volume gate
        for (var index = 0; index < 2; index++) observations.add(rawObservation(new BigDecimal("28"), now.minusDays(30)));
        for (var index = 0; index < 5; index++) observations.add(rawObservation(new BigDecimal("22"), now.minusDays(2)));

        when(observationRepository.findRecent(any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq(marketCnpj), any()))
                .thenReturn(5L);

        assertTrue(service.detectAll().isEmpty());
    }

    @Test
    void recentEmpty_skipsGroup() {
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        // all baseline, none in the recent window
        for (var index = 0; index < 6; index++) observations.add(rawObservation(new BigDecimal("28"), now.minusDays(30)));

        when(observationRepository.findRecent(any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq(marketCnpj), any()))
                .thenReturn(5L);

        assertTrue(service.detectAll().isEmpty());
    }

    @Test
    void promoFlaggedBaselineRowsExcluded_dropsBaselineBelowThree() {
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        // 5 baseline rows but only 2 unflagged → after exclusion baseline < 3 → skip
        for (var index = 0; index < 2; index++) observations.add(rawObservation(new BigDecimal("28"), now.minusDays(30)));
        for (var index = 0; index < 3; index++) {
            var flagged = rawObservation(new BigDecimal("28"), now.minusDays(30));
            flagged.setPromoFlag(true);
            observations.add(flagged);
        }
        for (var index = 0; index < 5; index++) observations.add(rawObservation(new BigDecimal("22"), now.minusDays(2)));

        when(observationRepository.findRecent(any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq(marketCnpj), any()))
                .thenReturn(5L);

        assertTrue(service.detectAll().isEmpty());
    }

    @Test
    void usesNormalizedPriceWhenAllRowsCarryIt() {
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        // raw unit price is flat (no promo by raw), but normalized shows a deep drop
        for (var index = 0; index < 8; index++) {
            observations.add(normalizedObservation(new BigDecimal("28"), new BigDecimal("28"), now.minusDays(30)));
        }
        for (var index = 0; index < 5; index++) {
            observations.add(normalizedObservation(new BigDecimal("28"), new BigDecimal("20"), now.minusDays(2)));
        }

        when(observationRepository.findRecent(any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq(marketCnpj), any()))
                .thenReturn(5L);

        var promos = service.detectAll();

        assertEquals(1, promos.size());
        var promo = promos.get(0);
        // medians come from the normalized field, not the raw 28
        assertEquals(0, promo.currentMedianPrice().compareTo(new BigDecimal("20")));
        assertEquals(0, promo.baselineMedianPrice().compareTo(new BigDecimal("28")));
    }

    @Test
    void withinRadius_promoCarriesDistance() {
        when(observationRepository.findRecent(any())).thenReturn(promoShapedRaw());
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq(marketCnpj), any()))
                .thenReturn(5L);
        // market essentially at the user location → ~0 km, inside any radius
        when(marketLocationService.findByCnpjs(anyList()))
                .thenReturn(Map.of(marketCnpj, marketAt(portoAlegreLat, portoAlegreLng)));

        var promos = service.detectAll(portoAlegreLat, portoAlegreLng, 50.0, Set.of());

        assertEquals(1, promos.size());
        assertNotNull(promos.get(0).distanceKm());
        assertTrue(promos.get(0).distanceKm() < 1.0);
        assertEquals(false, promos.get(0).watching());
    }

    @Test
    void outsideRadiusAndNotWatched_filteredOut() {
        when(observationRepository.findRecent(any())).thenReturn(promoShapedRaw());
        lenient().when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq(marketCnpj), any()))
                .thenReturn(5L);
        when(marketLocationService.findByCnpjs(anyList()))
                .thenReturn(Map.of(marketCnpj, marketAt(saoPauloLat, saoPauloLng)));

        var promos = service.detectAll(portoAlegreLat, portoAlegreLng, 5.0, Set.of());

        assertTrue(promos.isEmpty());
    }

    @Test
    void outsideRadiusButWatched_keptAndMarkedWatching() {
        when(observationRepository.findRecent(any())).thenReturn(promoShapedRaw());
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq(marketCnpj), any()))
                .thenReturn(5L);
        when(marketLocationService.findByCnpjs(anyList()))
                .thenReturn(Map.of(marketCnpj, marketAt(saoPauloLat, saoPauloLng)));

        var promos = service.detectAll(portoAlegreLat, portoAlegreLng, 5.0, Set.of(marketCnpj));

        assertEquals(1, promos.size());
        assertTrue(promos.get(0).watching());
        assertNotNull(promos.get(0).distanceKm());
    }

    @Test
    void noCoordinatesWithRadius_filteredOutUnlessWatched() {
        when(observationRepository.findRecent(any())).thenReturn(promoShapedRaw());
        lenient().when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq(marketCnpj), any()))
                .thenReturn(5L);
        when(marketLocationService.findByCnpjs(anyList())).thenReturn(Map.of()); // unknown location

        var promos = service.detectAll(portoAlegreLat, portoAlegreLng, 5.0, Set.of());

        assertTrue(promos.isEmpty());
    }

    @Test
    void noCoordinatesNoRadiusFilter_keptWithNullDistance() {
        when(observationRepository.findRecent(any())).thenReturn(promoShapedRaw());
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq(marketCnpj), any()))
                .thenReturn(5L);
        when(marketLocationService.findByCnpjs(anyList())).thenReturn(Map.of()); // unknown location

        var promos = service.detectAll(portoAlegreLat, portoAlegreLng, null, Set.of());

        assertEquals(1, promos.size());
        assertNull(promos.get(0).distanceKm());
    }

    @Test
    void nullWatchedCnpjs_treatedAsEmptySet() {
        when(observationRepository.findRecent(any())).thenReturn(promoShapedRaw());
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq(marketCnpj), any()))
                .thenReturn(5L);
        // no user coordinates → no location lookup, distance stays null
        var promos = service.detectAll(null, null, null, null);

        assertEquals(1, promos.size());
        assertEquals(false, promos.get(0).watching());
        assertNull(promos.get(0).distanceKm());
    }
}
