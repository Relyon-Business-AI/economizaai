package com.relyon.economizaai.service.dashboard;

import com.relyon.economizaai.dto.response.ConsumptionPredictionResponse;
import com.relyon.economizaai.dto.response.ConsumptionPredictionResponse.Confidence;
import com.relyon.economizaai.dto.response.ConsumptionPredictionResponse.Status;
import com.relyon.economizaai.dto.response.SpendInsightsResponse;
import com.relyon.economizaai.dto.response.SpendInsightsResponse.MarketBucket;
import com.relyon.economizaai.dto.response.SuggestedShoppingListResponse;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.ReceiptRepository;
import com.relyon.economizaai.service.InsightsService;
import com.relyon.economizaai.service.consumption.ConsumptionIntelligenceService;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.priceindex.CommunityPromoService;
import com.relyon.economizaai.service.priceindex.CommunityPromoService.CommunityPromo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the dashboard core composition itself (snapshot math,
 * section limits, friendly-name overrides). The caching behavior of
 * {@code @Cacheable} is covered separately by {@link DashboardCacheTest}.
 */
@ExtendWith(MockitoExtension.class)
class DashboardCacheServiceTest {

    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();
    private static final String CNPJ_ZAFFARI = "12345678000199";
    private static final String CNPJ_NACIONAL = "99887766000155";

    @Mock private InsightsService insightsService;
    @Mock private ReceiptRepository receiptRepository;
    @Mock private ConsumptionIntelligenceService consumptionService;
    @Mock private CommunityPromoService communityPromoService;
    @Mock private WatchedMarketService watchedMarketService;
    @Mock private MarketNameService marketNameService;

    @InjectMocks private DashboardCacheService service;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(UUID.randomUUID())
                .email("u@e")
                .household(Household.builder().id(HOUSEHOLD_ID).build())
                .homeLatitude(new BigDecimal("-30"))
                .homeLongitude(new BigDecimal("-51"))
                .build();
        stubEmptySections();
    }

    /** Baseline: everything empty; individual tests override what they exercise. */
    private void stubEmptySections() {
        lenient().when(insightsService.spend(eq(user), any(), any())).thenReturn(spend(BigDecimal.ZERO, BigDecimal.ZERO, List.of()));
        lenient().when(receiptRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());
        lenient().when(consumptionService.suggestedList(user, false, 0))
                .thenReturn(new SuggestedShoppingListResponse(List.of(), null));
        lenient().when(communityPromoService.detectAll(any(), any(), any(), any())).thenReturn(List.of());
        lenient().when(watchedMarketService.watchedCnpjs(user)).thenReturn(Set.of());
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketNameService.applyOverride(any(), any(), any())).thenAnswer(invocation -> {
            Map<String, String> overrides = invocation.getArgument(0);
            String cnpj = invocation.getArgument(1);
            String fallback = invocation.getArgument(2);
            return overrides != null && cnpj != null ? overrides.getOrDefault(cnpj, fallback) : fallback;
        });
    }

    private SpendInsightsResponse spend(BigDecimal total, BigDecimal discount, List<MarketBucket> byMarket) {
        return new SpendInsightsResponse(null, null, total, discount, List.of(), List.of(), byMarket, List.of());
    }

    // ---------------------------------------------------------- empty household

    @Test
    void core_emptyHousehold_returnsZeroSnapshotAndEmptySections() {
        var response = service.buildCachedDashboard(user);

        var snapshot = response.currentMonth();
        var now = YearMonth.now();
        assertEquals(now.getYear(), snapshot.year());
        assertEquals(now.getMonthValue(), snapshot.month());
        assertEquals(0, snapshot.total().compareTo(BigDecimal.ZERO));
        assertEquals(0L, snapshot.receiptCount());
        assertEquals(0, snapshot.averageTicket().compareTo(BigDecimal.ZERO), "no division by zero on empty month");
        assertEquals(List.of(), response.recentReceipts());
        assertEquals(List.of(), response.suggestedShoppingList());
        assertEquals(List.of(), response.communityPromosNearby());
        assertEquals(0L, response.unreadNotificationCount(), "unread is a placeholder in the cached core");
        assertNotNull(response.generatedAt());
    }

    // ---------------------------------------------------------- spend snapshot

    @Test
    void core_sumsReceiptCountAcrossMarketsAndComputesAverageTicket() {
        when(insightsService.spend(eq(user), any(), any())).thenReturn(spend(
                new BigDecimal("100.00"), new BigDecimal("4.00"),
                List.of(new MarketBucket(CNPJ_ZAFFARI, "Zaffari", "Zaffari", new BigDecimal("70.00"), BigDecimal.ZERO, 2),
                        new MarketBucket(CNPJ_NACIONAL, "Nacional", "Nacional", new BigDecimal("30.00"), BigDecimal.ZERO, 1))));

        var snapshot = service.buildCachedDashboard(user).currentMonth();

        assertEquals(3L, snapshot.receiptCount());
        assertEquals(0, snapshot.total().compareTo(new BigDecimal("100.00")));
        assertEquals(0, snapshot.discount().compareTo(new BigDecimal("4.00")));
        // 100.00 / 3 receipts, HALF_UP to 2 decimals
        assertEquals(new BigDecimal("33.33"), snapshot.averageTicket());
    }

    // ---------------------------------------------------------- recent receipts

    @Test
    void core_mapsRecentReceiptsAndAppliesFriendlyNameOverride() {
        var zaffariReceipt = receipt(CNPJ_ZAFFARI, "Zaffari",
                LocalDateTime.of(2026, Month.JUNE, 10, 18, 0), new BigDecimal("70.00"));
        var nacionalReceipt = receipt(CNPJ_NACIONAL, "Nacional",
                LocalDateTime.of(2026, Month.JUNE, 8, 18, 0), new BigDecimal("30.00"));
        when(receiptRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(zaffariReceipt, nacionalReceipt)));
        when(marketNameService.resolveNames(eq(HOUSEHOLD_ID), any()))
                .thenReturn(Map.of(CNPJ_ZAFFARI, "Zaffari de casa"));

        var recent = service.buildCachedDashboard(user).recentReceipts();

        assertEquals(2, recent.size());
        assertEquals("Zaffari", recent.get(0).marketName());
        assertEquals("Zaffari de casa", recent.get(0).marketFriendlyName());
        assertEquals("Nacional", recent.get(1).marketFriendlyName(), "no override falls back to the market name");
    }

    // ---------------------------------------------------------- suggested list

    @Test
    void core_limitsSuggestedListToTopFive() {
        var predictions = IntStream.range(0, 7)
                .mapToObj(index -> prediction("Produto " + index))
                .toList();
        when(consumptionService.suggestedList(user, false, 0))
                .thenReturn(new SuggestedShoppingListResponse(predictions, null));

        var suggested = service.buildCachedDashboard(user).suggestedShoppingList();

        assertEquals(5, suggested.size());
        assertEquals("Produto 0", suggested.get(0).productName(), "priority order is preserved");
        assertEquals("Produto 4", suggested.get(4).productName());
    }

    // ---------------------------------------------------------- community promos

    @Test
    void core_limitsPromosToFiveAndAppliesFriendlyNameOverride() {
        var promos = IntStream.range(0, 7)
                .mapToObj(index -> promo("Produto " + index, CNPJ_ZAFFARI, "Zaffari"))
                .toList();
        when(communityPromoService.detectAll(any(), any(), any(), any())).thenReturn(promos);
        when(marketNameService.resolveNames(eq(HOUSEHOLD_ID), any()))
                .thenReturn(Map.of(CNPJ_ZAFFARI, "Zaffari de casa"));

        var nearby = service.buildCachedDashboard(user).communityPromosNearby();

        assertEquals(5, nearby.size());
        assertTrue(nearby.stream().allMatch(row -> "Zaffari de casa".equals(row.marketFriendlyName())));
        assertTrue(nearby.stream().allMatch(row -> "Zaffari".equals(row.marketName())), "original name stays intact");
    }

    @Test
    void core_passesHomeCoordinatesAndWatchedCnpjsToPromoDetection() {
        var watched = Set.of(CNPJ_NACIONAL);
        when(watchedMarketService.watchedCnpjs(user)).thenReturn(watched);

        service.buildCachedDashboard(user);

        verify(communityPromoService).detectAll(
                eq(new BigDecimal("-30")), eq(new BigDecimal("-51")), isNull(), eq(watched));
    }

    // ---------------------------------------------------------- helpers

    private Receipt receipt(String cnpj, String marketName, LocalDateTime issuedAt, BigDecimal total) {
        return Receipt.builder()
                .id(UUID.randomUUID())
                .cnpjEmitente(cnpj)
                .marketName(marketName)
                .issuedAt(issuedAt)
                .totalAmount(total)
                .status(ReceiptStatus.CONFIRMED)
                .build();
    }

    private ConsumptionPredictionResponse prediction(String productName) {
        return new ConsumptionPredictionResponse(
                UUID.randomUUID(), productName, null, null,
                LocalDate.of(2026, Month.MAY, 1), LocalDate.of(2026, Month.JUNE, 15),
                3L, new BigDecimal("15.0"), BigDecimal.ONE, 4,
                Confidence.MEDIUM, Status.RUNNING_LOW);
    }

    private CommunityPromo promo(String productName, String cnpj, String marketName) {
        return new CommunityPromo(
                UUID.randomUUID(), productName, cnpj, cnpj.substring(0, 8), marketName, marketName,
                new BigDecimal("4.00"), new BigDecimal("5.00"), new BigDecimal("20.0"),
                5, 3L, 2.0, false);
    }
}
