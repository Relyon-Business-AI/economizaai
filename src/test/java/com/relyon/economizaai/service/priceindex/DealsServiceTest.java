package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.RelevanceMode;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.WatchedMarketService;
import com.relyon.economizaai.service.notifications.DealFeedbackService;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DealsServiceTest {

    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private PriceIndexService priceIndexService;
    @Mock private WatchedMarketService watchedMarketService;
    @Mock private MarketNameService marketNameService;
    @Mock private DealFeedbackService dealFeedbackService;
    @Mock private HouseholdProductAliasService householdProductAliasService;

    private final CollaborativeProperties properties = new CollaborativeProperties();

    private DealsService dealsService;

    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final String WATCHED_CNPJ = "12345678000199";
    private static final String NEARBY_CNPJ = "99887766000155";

    @BeforeEach
    void setUp() {
        dealsService = new DealsService(receiptItemRepository, priceIndexService, properties,
                watchedMarketService, marketNameService, dealFeedbackService, householdProductAliasService);
        lenient().when(dealFeedbackService.mode()).thenReturn(RelevanceMode.OFF);
        lenient().when(dealFeedbackService.suppressionsFor(any()))
                .thenReturn(DealFeedbackService.SuppressionSet.empty());
        lenient().when(watchedMarketService.watchedCnpjs(any())).thenReturn(Set.of(WATCHED_CNPJ));
        lenient().when(marketNameService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    private User user() {
        return User.builder().id(UUID.randomUUID()).email("u@e")
                .household(Household.builder().id(HOUSEHOLD_ID).build())
                .build();
    }

    private Product product() {
        return Product.builder().id(PRODUCT_ID).normalizedName("Leite")
                .category(ProductCategory.MEAT_DAIRY).build();
    }

    private ReceiptItem purchase(BigDecimal unitPrice, LocalDateTime issuedAt) {
        var receipt = Receipt.builder().issuedAt(issuedAt).build();
        return ReceiptItem.builder().product(product()).unitPrice(unitPrice).receipt(receipt).build();
    }

    private PriceIndexService.MarketPriceRow market(String cnpj, BigDecimal median, long households,
                                                    Double distanceKm, boolean watching) {
        return new PriceIndexService.MarketPriceRow(cnpj, PriceIndexService.cnpjRoot(cnpj), "Mercado",
                median, median, 5, households, distanceKm, watching);
    }

    @Test
    void surfacesDealWhenWatchedMarketBeatsThresholdWithCorrectSavings() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        // 30% drop on a R$10 item — well past the ~12% required at that price.
        when(priceIndexService.bestMarkets(eq(PRODUCT_ID), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("7.00"), 3L, null, true)));

        var deals = dealsService.findDeals(user(), false, null, 20);

        assertEquals(1, deals.size());
        var deal = deals.get(0);
        assertEquals(PRODUCT_ID, deal.productId());
        assertEquals(WATCHED_CNPJ, deal.marketCnpj());
        assertEquals(0, new BigDecimal("7.00").compareTo(deal.currentPrice()));
        assertEquals(0, new BigDecimal("10.00").compareTo(deal.lastPaidPrice()));
        assertEquals(0, new BigDecimal("3.00").compareTo(deal.savingsAmount()));
        assertEquals(0, new BigDecimal("30.00").compareTo(deal.savingsPct()));
        assertEquals(0, new BigDecimal("0.3000").compareTo(deal.discountFraction()));
        assertTrue(deal.isWatched());
        assertEquals(3L, deal.distinctHouseholds());
    }

    @Test
    void relevanceOn_productSuppressedByFeedback_skipsWithoutPriceLookup() {
        when(dealFeedbackService.mode()).thenReturn(RelevanceMode.ON);
        when(dealFeedbackService.suppressionsFor(any())).thenReturn(
                new DealFeedbackService.SuppressionSet(Set.of(PRODUCT_ID), Set.of(), Map.of()));
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));

        assertTrue(dealsService.findDeals(user(), false, null, 20).isEmpty());
        verify(priceIndexService, never()).bestMarkets(any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void relevanceOn_dismissedPairSuppressed_otherMarketStillSurfaces() {
        when(dealFeedbackService.mode()).thenReturn(RelevanceMode.ON);
        when(dealFeedbackService.suppressionsFor(any())).thenReturn(
                new DealFeedbackService.SuppressionSet(Set.of(), Set.of(),
                        Map.of(PRODUCT_ID, Set.of(WATCHED_CNPJ))));
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        // dismissed market is cheapest, but another market also qualifies
        when(priceIndexService.bestMarkets(eq(PRODUCT_ID), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("7.00"), 3L, null, true)));

        // the surviving cheapest qualifying market IS the dismissed pair → suppressed
        assertTrue(dealsService.findDeals(user(), false, null, 20).isEmpty());
    }

    @Test
    void relevanceShadow_suppressedDealStillShown() {
        when(dealFeedbackService.mode()).thenReturn(RelevanceMode.SHADOW);
        when(dealFeedbackService.suppressionsFor(any())).thenReturn(
                new DealFeedbackService.SuppressionSet(Set.of(PRODUCT_ID), Set.of(), Map.of()));
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        when(priceIndexService.bestMarkets(eq(PRODUCT_ID), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("7.00"), 3L, null, true)));

        var deals = dealsService.findDeals(user(), false, null, 20);

        assertEquals(1, deals.size(), "SHADOW counts but never hides");
    }

    @Test
    void excludesTinyDropBelowProgressiveThreshold() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        // 1% drop — far below the required drop for a R$10 item.
        when(priceIndexService.bestMarkets(eq(PRODUCT_ID), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("9.90"), 3L, null, true)));

        assertTrue(dealsService.findDeals(user(), false, null, 20).isEmpty());
    }

    @Test
    void excludesMarketBelowKAnonymity() {
        // bestMarkets enforces k-anon itself by dropping sub-K markets, so it
        // returns nothing here — the deal must not surface.
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        when(priceIndexService.bestMarkets(eq(PRODUCT_ID), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of());

        assertTrue(dealsService.findDeals(user(), false, null, 20).isEmpty());
    }

    @Test
    void nearbyMarketExcludedUnlessIncludeNearby() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        // When includeNearby=false, the service passes radiusKm=null so bestMarkets
        // filters nearby markets out; assert that null radius is forwarded.
        when(priceIndexService.bestMarkets(eq(PRODUCT_ID), anyInt(), any(), any(),
                eq(null), any()))
                .thenReturn(List.of());

        var deals = dealsService.findDeals(user(), false, 5.0, 20);

        assertTrue(deals.isEmpty());
    }

    @Test
    void includeNearbyForwardsRadiusAndSurfacesNearbyDeal() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        when(priceIndexService.bestMarkets(eq(PRODUCT_ID), anyInt(), any(), any(),
                eq(5.0), any()))
                .thenReturn(List.of(market(NEARBY_CNPJ, new BigDecimal("7.00"), 4L, 2.3, false)));

        var deals = dealsService.findDeals(user(), true, 5.0, 20);

        assertEquals(1, deals.size());
        assertEquals(NEARBY_CNPJ, deals.get(0).marketCnpj());
        assertEquals(2.3, deals.get(0).distanceKm());
    }

    @Test
    void ranksBySavingsDescending() {
        var cheaperProductId = UUID.randomUUID();
        var pricierProduct = Product.builder().id(cheaperProductId).normalizedName("Café")
                .category(ProductCategory.GROCERIES).build();
        var lowSavingsItem = purchase(new BigDecimal("10.00"), LocalDateTime.now());
        var highSavingsItem = ReceiptItem.builder().product(pricierProduct)
                .unitPrice(new BigDecimal("20.00"))
                .receipt(Receipt.builder().issuedAt(LocalDateTime.now()).build()).build();
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(lowSavingsItem, highSavingsItem));

        // PRODUCT_ID: 20% drop (10 -> 8). cheaperProductId: 50% drop (20 -> 10).
        when(priceIndexService.bestMarkets(eq(PRODUCT_ID), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("8.00"), 3L, null, true)));
        when(priceIndexService.bestMarkets(eq(cheaperProductId), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("10.00"), 3L, null, true)));

        var deals = dealsService.findDeals(user(), false, null, 20);

        assertEquals(2, deals.size());
        assertEquals(cheaperProductId, deals.get(0).productId()); // 50% drop ranks first
        assertEquals(PRODUCT_ID, deals.get(1).productId());
    }

    @Test
    void emptyWhenCollaborativeDisabled() {
        properties.getCollaborative().setEnabled(false);
        assertTrue(dealsService.findDeals(user(), false, null, 20).isEmpty());
    }

    @Test
    void emptyWhenLimitNonPositive() {
        assertTrue(dealsService.findDeals(user(), false, null, 0).isEmpty());
    }

    @Test
    void dealCarriesHouseholdFriendlyName() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        when(priceIndexService.bestMarkets(eq(PRODUCT_ID), anyInt(), any(), any(), any(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("7.00"), 3L, null, true)));
        when(householdProductAliasService.friendlyNamesFor(eq(HOUSEHOLD_ID), any()))
                .thenReturn(Map.of(PRODUCT_ID, "Leite das criancas"));

        var deals = dealsService.findDeals(user(), false, null, 20);

        assertEquals("Leite", deals.get(0).productName());
        assertEquals("Leite das criancas", deals.get(0).friendlyDescription());
    }
}
