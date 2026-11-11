package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.model.MarketLocation;
import java.util.Map;
import com.relyon.economizaai.service.geo.NearbyScope;
import com.relyon.economizaai.service.geo.ViewerLocationService.Source;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.MerchantSegment;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationAuditRepository.ProductMarketHouseholdCount;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.service.geo.MarketLocationService;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.CategoryResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommunityPromoServiceTest {

    @Mock private PriceObservationRepository observationRepository;
    @Mock private PriceObservationAuditRepository auditRepository;

    private CollaborativeProperties properties;
    private CommunityPromoService service;
    private final UUID productId = UUID.randomUUID();
    private final String marketCnpj = "93015006005182";

    @Mock private MarketLocationService marketLocationService;
    @Mock private ProductRepository productRepository;
    @Mock private CategoryResolver categoryResolver;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        service = new CommunityPromoService(observationRepository, auditRepository, properties, marketLocationService,
                new PrivacyTierResolver(productRepository, categoryResolver, properties));
    }

    private PriceObservation obs(BigDecimal price, LocalDateTime observedAt) {
        return obs(price, observedAt, false);
    }

    private PriceObservation obs(BigDecimal price, LocalDateTime observedAt, boolean outlier) {
        return PriceObservation.builder()
                .id(UUID.randomUUID())
                .product(Product.builder().id(productId).normalizedName("Arroz").build())
                .marketCnpj(marketCnpj)
                .marketCnpjRoot(marketCnpj.substring(0, 8))
                .marketName("Mercado X")
                .unitPrice(price)
                .quantity(BigDecimal.ONE)
                .observedAt(observedAt)
                .outlier(outlier)
                .build();
    }

    private ProductMarketHouseholdCount groupCount(UUID groupProductId, String groupCnpj, long households) {
        return new ProductMarketHouseholdCount() {
            @Override public UUID getProductId() { return groupProductId; }
            @Override public String getCnpj() { return groupCnpj; }
            @Override public long getHouseholds() { return households; }
        };
    }

    private List<PriceObservation> droppedPriceSeries() {
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        for (var i = 0; i < 8; i++) observations.add(obs(new BigDecimal("28"), now.minusDays(30)));
        for (var i = 0; i < 5; i++) observations.add(obs(new BigDecimal("22"), now.minusDays(2)));
        return observations;
    }

    @Test
    void groceryPromoFromASingleHouseholdIsShown() {
        when(observationRepository.findRecent(any())).thenReturn(droppedPriceSeries());
        when(auditRepository.countDistinctHouseholdsPerProductMarket(any(), eq(1L)))
                .thenReturn(List.of(groupCount(productId, marketCnpj, 1L)));
        when(productRepository.findById(productId))
                .thenReturn(Optional.of(Product.builder().id(productId).category(ProductCategory.GROCERIES).build()));
        when(marketLocationService.findByCnpjs(List.of(marketCnpj)))
                .thenReturn(Map.of(marketCnpj, MarketLocation.builder().cnpj(marketCnpj).segment(MerchantSegment.SUPERMARKET).build()));

        var promos = service.detectAll();

        assertEquals(1, promos.size());
        assertEquals(1L, promos.get(0).distinctHouseholds());
    }

    @Test
    void pharmacyPromoBelowFullKIsHidden() {
        when(observationRepository.findRecent(any())).thenReturn(droppedPriceSeries());
        when(auditRepository.countDistinctHouseholdsPerProductMarket(any(), eq(1L)))
                .thenReturn(List.of(groupCount(productId, marketCnpj, 2L)));
        when(marketLocationService.findByCnpjs(List.of(marketCnpj)))
                .thenReturn(Map.of(marketCnpj, MarketLocation.builder().cnpj(marketCnpj).segment(MerchantSegment.PHARMACY).build()));

        assertTrue(service.detectAll().isEmpty());
    }

    @Test
    void promoAtAnUnclassifiedStoreBelowFullKIsHidden() {
        when(observationRepository.findRecent(any())).thenReturn(droppedPriceSeries());
        when(auditRepository.countDistinctHouseholdsPerProductMarket(any(), eq(1L)))
                .thenReturn(List.of(groupCount(productId, marketCnpj, 2L)));
        when(marketLocationService.findByCnpjs(List.of(marketCnpj)))
                .thenReturn(Map.of(marketCnpj, MarketLocation.builder().cnpj(marketCnpj).segment(MerchantSegment.UNKNOWN).build()));

        assertTrue(service.detectAll().isEmpty());
    }

    @Test
    void healthProductPromoBelowFullKIsHidden() {
        when(observationRepository.findRecent(any())).thenReturn(droppedPriceSeries());
        when(auditRepository.countDistinctHouseholdsPerProductMarket(any(), eq(1L)))
                .thenReturn(List.of(groupCount(productId, marketCnpj, 1L)));
        when(productRepository.findById(productId))
                .thenReturn(Optional.of(Product.builder().id(productId).category(ProductCategory.HEALTH).build()));
        when(marketLocationService.findByCnpjs(List.of(marketCnpj)))
                .thenReturn(Map.of(marketCnpj, MarketLocation.builder().cnpj(marketCnpj).segment(MerchantSegment.SUPERMARKET).build()));

        assertTrue(service.detectAll().isEmpty());
    }

    @Test
    void promoPricesArePackagePricesEvenWhenDetectionRunsPerKilo() {
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        for (var index = 0; index < 8; index++) {
            observations.add(packageObservation(new BigDecimal("1.39"), new BigDecimal("92.67"), now.minusDays(30)));
        }
        for (var index = 0; index < 5; index++) {
            observations.add(packageObservation(new BigDecimal("0.89"), new BigDecimal("59.33"), now.minusDays(2)));
        }
        when(observationRepository.findRecent(any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsPerProductMarket(any(), eq(1L)))
                .thenReturn(List.of(groupCount(productId, marketCnpj, 3L)));

        var promo = service.detectAll().get(0);

        assertEquals(new BigDecimal("0.89"), promo.currentMedianPrice());
        assertEquals(new BigDecimal("1.39"), promo.baselineMedianPrice());
        assertEquals(new BigDecimal("35.98"), promo.dropPct());
    }

    private PriceObservation packageObservation(BigDecimal packagePrice, BigDecimal pricePerKilo, LocalDateTime observedAt) {
        var observation = obs(packagePrice, observedAt);
        observation.setNormalizedUnitPrice(pricePerKilo);
        return observation;
    }

    @Test
    void detectsCommunityPromoWhenRecentMedianFarBelowBaseline() {
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        // Baseline (8-90 days old): median 28
        for (var i = 0; i < 8; i++) observations.add(obs(new BigDecimal("28"), now.minusDays(30)));
        // Recent (last 7 days): median 22 (~22% below baseline) → > 15% threshold → promo
        for (var i = 0; i < 5; i++) observations.add(obs(new BigDecimal("22"), now.minusDays(2)));

        when(observationRepository.findRecent(any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsPerProductMarket(any(), eq(1L)))
                .thenReturn(List.of(groupCount(productId, marketCnpj, 3L)));

        var promos = service.detectAll();

        assertEquals(1, promos.size());
        var p = promos.get(0);
        assertEquals(productId, p.productId());
        assertEquals(marketCnpj, p.marketCnpj());
        assertTrue(p.dropPct().compareTo(new BigDecimal("15")) > 0);
    }

    @Test
    void noPromoWhenDropBelowThreshold() {
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        for (var i = 0; i < 8; i++) observations.add(obs(new BigDecimal("28"), now.minusDays(30)));
        // Recent median 26 → only 7% below → under 15% threshold
        for (var i = 0; i < 5; i++) observations.add(obs(new BigDecimal("26"), now.minusDays(2)));

        when(observationRepository.findRecent(any())).thenReturn(observations);

        assertEquals(0, service.detectAll().size());
    }

    @Test
    void noPromoWhenKAnonBlocks() {
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        for (var i = 0; i < 8; i++) observations.add(obs(new BigDecimal("28"), now.minusDays(30)));
        for (var i = 0; i < 5; i++) observations.add(obs(new BigDecimal("20"), now.minusDays(2)));

        when(observationRepository.findRecent(any())).thenReturn(observations);
        // Below k-anon=3: the HAVING >= K clause filters the group out in the database.
        when(auditRepository.countDistinctHouseholdsPerProductMarket(any(), eq(1L)))
                .thenReturn(List.of());

        assertEquals(0, service.detectAll().size());
    }

    @Test
    void noPromoWhenMasterSwitchOff() {
        properties.getCollaborative().setEnabled(false);
        // Even with abundant data, returns empty without touching repos
        assertEquals(0, service.detectAll().size());
    }

    @Test
    void sharedCacheComputesOnce_perViewerWatchedFlagStillApplied() {
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        for (var index = 0; index < 8; index++) observations.add(obs(new BigDecimal("28"), now.minusDays(30)));
        for (var index = 0; index < 5; index++) observations.add(obs(new BigDecimal("22"), now.minusDays(2)));
        when(observationRepository.findRecent(any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsPerProductMarket(any(), eq(1L)))
                .thenReturn(List.of(groupCount(productId, marketCnpj, 3L)));

        var firstViewer = service.detectAll();
        var secondViewer = service.detectAll(null, null, null, Set.of(marketCnpj));

        // The expensive scan ran ONCE — the second viewer reused the shared cache...
        verify(observationRepository, times(1)).findRecent(any());
        verify(auditRepository, times(1)).countDistinctHouseholdsPerProductMarket(any(), anyLong());
        // ...but still got their own watched flag applied on top of it.
        assertEquals(false, firstViewer.get(0).watching());
        assertTrue(secondViewer.get(0).watching());
    }

    @Test
    void detectInScope_unlocatedViewerOnlySeesPromosFromTheirState() {
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        for (var index = 0; index < 8; index++) observations.add(obs(new BigDecimal("28"), now.minusDays(30)));
        for (var index = 0; index < 5; index++) observations.add(obs(new BigDecimal("22"), now.minusDays(2)));
        when(marketLocationService.findByCnpjs(any())).thenReturn(Map.of(marketCnpj,
                MarketLocation.builder().cnpj(marketCnpj).state("PE").build()));
        when(observationRepository.findRecent(any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsPerProductMarket(any(), eq(1L)))
                .thenReturn(List.of(groupCount(productId, marketCnpj, 3L)));

        var gaucho = new NearbyScope(new ViewerLocation(null, null, Source.NONE, "RS"), Set.of(), 10);
        var pernambucano = new NearbyScope(new ViewerLocation(null, null, Source.NONE, "PE"), Set.of(), 10);

        assertTrue(service.detectInScope(gaucho).isEmpty(), "a Recife promo is noise for Porto Alegre");
        assertEquals(1, service.detectInScope(pernambucano).size());
    }

    @Test
    void loneOutlierRecentObservationDoesNotManufactureAPromo() {
        // The real bug: a SINGLE bogus R$2,98 scan (typical ~R$5,49) fabricated a
        // "~42% mais barato". With the min-recent-cluster gate the lone obs can't
        // drive a promo even though baseline has plenty of rows.
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        for (var i = 0; i < 10; i++) observations.add(obs(new BigDecimal("5.49"), now.minusDays(30)));
        observations.add(obs(new BigDecimal("2.98"), now.minusDays(2))); // lone outlier

        when(observationRepository.findRecent(any())).thenReturn(observations);

        assertTrue(service.detectAll().isEmpty(), "a single outlier must not fabricate a promo");
    }

    @Test
    void outlierWithinAConsistentRecentWindowDoesNotManufactureAPromo() {
        // Recent window is mostly ~5.49 with one 2.98 stray. Both samples are
        // genuinely at the same level — no real drop. The stray must not pull the
        // recent median below the baseline.
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        for (var i = 0; i < 8; i++) observations.add(obs(new BigDecimal("5.49"), now.minusDays(30)));
        observations.add(obs(new BigDecimal("2.98"), now.minusDays(2))); // outlier low
        for (var i = 0; i < 4; i++) observations.add(obs(new BigDecimal("5.49"), now.minusDays(2)));

        when(observationRepository.findRecent(any())).thenReturn(observations);

        assertTrue(service.detectAll().isEmpty(), "stray outlier must not fabricate a promo");
    }

    @Test
    void honorsIsOutlierFlag_excludingFlaggedObservationsFromBaselineAndRecent() {
        // Same as above but the bogus scan is explicitly is_outlier=true: it must be
        // dropped regardless of the MAD trim (future outlier-tagging job compatibility).
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        for (var i = 0; i < 8; i++) observations.add(obs(new BigDecimal("5.49"), now.minusDays(30)));
        observations.add(obs(new BigDecimal("2.98"), now.minusDays(2), true)); // flagged outlier
        for (var i = 0; i < 4; i++) observations.add(obs(new BigDecimal("5.49"), now.minusDays(2)));

        when(observationRepository.findRecent(any())).thenReturn(observations);

        assertTrue(service.detectAll().isEmpty(), "flagged outlier must be excluded");
    }

    @Test
    void realDropStillDetectedWithAnOutlierPresent() {
        // Genuine drop 28 → 22 with one wild 2.00 outlier in the recent window: the
        // outlier is trimmed but the real ~22% drop is still detected.
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        for (var i = 0; i < 8; i++) observations.add(obs(new BigDecimal("28"), now.minusDays(30)));
        observations.add(obs(new BigDecimal("2.00"), now.minusDays(2), true));
        for (var i = 0; i < 5; i++) observations.add(obs(new BigDecimal("22"), now.minusDays(2)));

        when(observationRepository.findRecent(any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsPerProductMarket(any(), eq(1L)))
                .thenReturn(List.of(groupCount(productId, marketCnpj, 3L)));

        var promos = service.detectAll();

        assertEquals(1, promos.size());
        // Drop computed against 22 (not dragged toward 2.00): ~21-22%, not ~90%.
        var dropPct = promos.get(0).dropPct();
        assertTrue(dropPct.compareTo(new BigDecimal("15")) > 0);
        assertTrue(dropPct.compareTo(new BigDecimal("30")) < 0, "drop reflects 22, not the 2.00 outlier");
    }

    @Test
    void cacheTtlZero_recomputesEveryCall() {
        properties.getCollaborative().setCommunityPromoCacheSeconds(0);
        when(observationRepository.findRecent(any())).thenReturn(List.of());

        service.detectAll();
        service.detectAll();

        verify(observationRepository, times(2)).findRecent(any());
    }
}
