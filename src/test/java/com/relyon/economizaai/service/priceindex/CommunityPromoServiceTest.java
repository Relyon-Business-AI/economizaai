package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        service = new CommunityPromoService(observationRepository, auditRepository, properties, marketLocationService);
    }

    private PriceObservation obs(BigDecimal price, LocalDateTime observedAt) {
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

    @Test
    void detectsCommunityPromoWhenRecentMedianFarBelowBaseline() {
        var now = LocalDateTime.now();
        var observations = new ArrayList<PriceObservation>();
        // Baseline (8-90 days old): median 28
        for (var i = 0; i < 8; i++) observations.add(obs(new BigDecimal("28"), now.minusDays(30)));
        // Recent (last 7 days): median 22 (~22% below baseline) → > 15% threshold → promo
        for (var i = 0; i < 5; i++) observations.add(obs(new BigDecimal("22"), now.minusDays(2)));

        when(observationRepository.findRecent(any())).thenReturn(observations);
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq(marketCnpj), any()))
                .thenReturn(3L);

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
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(productId), eq(marketCnpj), any()))
                .thenReturn(2L); // below k-anon=3

        assertEquals(0, service.detectAll().size());
    }

    @Test
    void noPromoWhenMasterSwitchOff() {
        properties.getCollaborative().setEnabled(false);
        // Even with abundant data, returns empty without touching repos
        assertEquals(0, service.detectAll().size());
    }
}
