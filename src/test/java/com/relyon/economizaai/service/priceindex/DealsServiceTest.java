package com.relyon.economizaai.service.priceindex;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.DealPriceSource;
import com.relyon.economizaai.model.enums.RelevanceMode;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.NearbyScope;
import com.relyon.economizaai.service.geo.NearbyScopeService;
import com.relyon.economizaai.service.geo.ViewerLocationService.Source;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.notifications.DealFeedbackService;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DealsServiceTest {

    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private PriceIndexService priceIndexService;
    @Mock private NearbyScopeService nearbyScopeService;
    @Mock private MarketNameService marketNameService;
    @Mock private DealFeedbackService dealFeedbackService;
    @Mock private HouseholdProductAliasService householdProductAliasService;

    private final CollaborativeProperties properties = new CollaborativeProperties();

    private DealsService dealsService;

    private static final UUID HOUSEHOLD_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final String WATCHED_CNPJ = "12345678000199";
    private static final String NEARBY_CNPJ = "99887766000155";
    /** Where the household's last purchase happened by default — a chain no candidate belongs to. */
    private static final String HOME_CNPJ = "55443322000111";
    private static final LocalDateTime RECENT_DAY = LocalDate.now().minusDays(20).atStartOfDay();

    @BeforeEach
    void setUp() {
        dealsService = new DealsService(receiptItemRepository, priceIndexService, properties,
                nearbyScopeService, marketNameService, dealFeedbackService, householdProductAliasService);
        lenient().when(dealFeedbackService.mode()).thenReturn(RelevanceMode.OFF);
        lenient().when(dealFeedbackService.suppressionsFor(any()))
                .thenReturn(DealFeedbackService.SuppressionSet.empty());
        lenient().when(nearbyScopeService.forUser(any(), any(), any(), any())).thenReturn(new NearbyScope(
                new ViewerLocation(null, null, Source.NONE, "RS"), Set.of(WATCHED_CNPJ), 10));
        lenient().when(marketNameService.resolve(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        // Default: every product has SOME very cheap recent observation, so the pre-check lets it
        // through and each test exercises the full ranking path as before.
        lenient().when(priceIndexService.lowestRecentInStorePrices(any())).thenAnswer(invocation -> {
            Collection<UUID> productIds = invocation.getArgument(0);
            return productIds.stream().collect(Collectors.toMap(productId -> productId, productId -> new BigDecimal("0.01")));
        });
    }

    @Test
    void skipsRankingWhenNoRecentObservationCanBeatLastPaid() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        // Lowest recent price R$9.50 — only 5% below last-paid, short of the ~12% required at R$10.
        doReturn(Map.of(PRODUCT_ID, new BigDecimal("9.50"))).when(priceIndexService).lowestRecentInStorePrices(any());

        assertTrue(dealsService.findDeals(user(), true, null, 20).isEmpty());
        verify(priceIndexService, never()).bestMarketsInScope(any(), anyInt(), any());
    }

    @Test
    void skipsRankingWhenProductHasNoRecentObservation() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        doReturn(Map.of()).when(priceIndexService).lowestRecentInStorePrices(any());

        assertTrue(dealsService.findDeals(user(), false, null, 20).isEmpty());
        verify(priceIndexService, never()).bestMarketsAtWatched(any(), anyInt(), any());
    }

    @Test
    void rankingStillDecidesWhenLowestPriceClearsTheCeiling() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        doReturn(Map.of(PRODUCT_ID, new BigDecimal("7.00"))).when(priceIndexService).lowestRecentInStorePrices(any());
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("7.00"), 3L, null, true)));

        assertEquals(1, dealsService.findDeals(user(), false, null, 20).size());
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

    /** A purchase at a chain that none of the candidate markets belong to. */
    private ReceiptItem purchase(BigDecimal unitPrice, LocalDateTime issuedAt) {
        return purchaseAt(HOME_CNPJ, unitPrice, issuedAt);
    }

    private ReceiptItem purchaseAt(String cnpj, BigDecimal unitPrice, LocalDateTime issuedAt) {
        var receipt = Receipt.builder().issuedAt(issuedAt).cnpjEmitente(cnpj).build();
        return ReceiptItem.builder().product(product()).unitPrice(unitPrice).receipt(receipt).build();
    }

    private PriceIndexService.MarketPriceRow market(String cnpj, BigDecimal price, long households,
                                                    Double distanceKm, boolean watching) {
        return market(cnpj, price, price, RECENT_DAY, households, distanceKm, watching);
    }

    private PriceIndexService.MarketPriceRow market(String cnpj, BigDecimal median, BigDecimal latestPrice,
                                                    LocalDateTime latestObservedAt, long households,
                                                    Double distanceKm, boolean watching) {
        return new PriceIndexService.MarketPriceRow(cnpj, PriceIndexService.cnpjRoot(cnpj), "Mercado",
                median, latestPrice, latestPrice, latestObservedAt, 5, households, distanceKm, watching,
                false, false);
    }

    @Test
    void dealPriceIsTheLatestObservedPriceNotTheMedian() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        // Median R$9.90 would not qualify; the latest observation (R$7.00) does.
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("9.90"), new BigDecimal("7.00"),
                        RECENT_DAY, 3L, null, true)));

        var deals = dealsService.findDeals(user(), false, null, 20);

        assertEquals(1, deals.size());
        assertEquals(0, new BigDecimal("7.00").compareTo(deals.get(0).currentPrice()));
        assertEquals(RECENT_DAY, deals.get(0).observedAt(), "observedAt is the day of the price observation");
    }

    @Test
    void cheapMedianWithAnExpensiveLatestPriceIsNotADeal() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("7.00"), new BigDecimal("9.90"),
                        RECENT_DAY, 3L, null, true)));

        assertTrue(dealsService.findDeals(user(), false, null, 20).isEmpty());
    }

    @Test
    void latestPriceOlderThanSixtyDaysIsNotADeal() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        var staleDay = LocalDate.now().minusDays(75).atStartOfDay();
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("7.00"), new BigDecimal("7.00"),
                        staleDay, 3L, null, true)));

        assertTrue(dealsService.findDeals(user(), false, null, 20).isEmpty());
    }

    @Test
    void priceAgeWindowIsConfigurable() {
        properties.getCollaborative().setDealsMaxPriceAgeDays(90);
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        var olderDay = LocalDate.now().minusDays(75).atStartOfDay();
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("7.00"), new BigDecimal("7.00"),
                        olderDay, 3L, null, true)));

        assertEquals(1, dealsService.findDeals(user(), false, null, 20).size());
    }

    @Test
    void neverSuggestsTheChainOfTheLastPurchase() {
        // Last bought at the WATCHED chain for R$10; another store of that same chain shows R$7.
        var siblingStoreOfSameChain = WATCHED_CNPJ.substring(0, 8) + "000270";
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchaseAt(WATCHED_CNPJ, new BigDecimal("10.00"), LocalDateTime.now())));
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(market(siblingStoreOfSameChain, new BigDecimal("7.00"), 3L, null, true)));

        assertTrue(dealsService.findDeals(user(), false, null, 20).isEmpty());
    }

    @Test
    void skipsTheLastPurchaseChainAndSurfacesTheNextCheapestOne() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchaseAt(WATCHED_CNPJ, new BigDecimal("10.00"), LocalDateTime.now())));
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(
                        market(WATCHED_CNPJ, new BigDecimal("6.00"), 3L, null, true),
                        market(NEARBY_CNPJ, new BigDecimal("7.50"), 3L, null, true)));

        var deals = dealsService.findDeals(user(), false, null, 20);

        assertEquals(1, deals.size());
        assertEquals(NEARBY_CNPJ, deals.get(0).marketCnpj());
        assertEquals(0, new BigDecimal("7.50").compareTo(deals.get(0).currentPrice()));
    }

    @Test
    void ownEarlierPurchaseAtAnotherChainIsLabelledOwnPurchase() {
        // Paid R$7 at NEARBY last month, then R$10 at WATCHED last week → "você pagou menos no NEARBY".
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID)).thenReturn(List.of(
                purchaseAt(NEARBY_CNPJ, new BigDecimal("7.0000"), RECENT_DAY.plusHours(18)),
                purchaseAt(WATCHED_CNPJ, new BigDecimal("10.00"), LocalDateTime.now())));
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(market(NEARBY_CNPJ, new BigDecimal("7.00"), 1L, null, true)));

        var deals = dealsService.findDeals(user(), false, null, 20);

        assertEquals(1, deals.size());
        assertEquals(DealPriceSource.OWN_PURCHASE, deals.get(0).priceSource());
        assertEquals(RECENT_DAY, deals.get(0).observedAt());
        assertEquals(0, new BigDecimal("10.00").compareTo(deals.get(0).lastPaidPrice()));
    }

    @Test
    void observationFromAnotherHouseholdIsLabelledCommunity() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(market(NEARBY_CNPJ, new BigDecimal("7.00"), 1L, null, true)));

        var deals = dealsService.findDeals(user(), false, null, 20);

        assertEquals(DealPriceSource.COMMUNITY, deals.get(0).priceSource());
    }

    @Test
    void ownPurchaseAtTheChainOnAnotherDayOrPriceStaysCommunity() {
        // The household did buy at NEARBY, but the observation pricing the chain is a later,
        // different one — so it is someone else's evidence.
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID)).thenReturn(List.of(
                purchaseAt(NEARBY_CNPJ, new BigDecimal("8.00"), RECENT_DAY.minusDays(10)),
                purchaseAt(NEARBY_CNPJ, new BigDecimal("7.90"), RECENT_DAY.plusHours(9)),
                purchaseAt(WATCHED_CNPJ, new BigDecimal("10.00"), LocalDateTime.now())));
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(market(NEARBY_CNPJ, new BigDecimal("7.00"), 2L, null, true)));

        var deals = dealsService.findDeals(user(), false, null, 20);

        assertEquals(DealPriceSource.COMMUNITY, deals.get(0).priceSource());
    }

    @Test
    void surfacesDealWhenWatchedMarketBeatsThresholdWithCorrectSavings() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        // 30% drop on a R$10 item — well past the ~12% required at that price.
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
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
        verify(priceIndexService, never()).bestMarketsAtWatched(any(), anyInt(), any());
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
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
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
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("7.00"), 3L, null, true)));

        var deals = dealsService.findDeals(user(), false, null, 20);

        assertEquals(1, deals.size(), "SHADOW counts but never hides");
    }

    @Test
    void excludesTinyDropBelowProgressiveThreshold() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        // 1% drop — far below the required drop for a R$10 item.
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("9.90"), 3L, null, true)));

        assertTrue(dealsService.findDeals(user(), false, null, 20).isEmpty());
    }

    @Test
    void excludesMarketBelowKAnonymity() {
        // bestMarkets enforces k-anon itself by dropping sub-K markets, so it
        // returns nothing here — the deal must not surface.
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of());

        assertTrue(dealsService.findDeals(user(), false, null, 20).isEmpty());
    }

    @Test
    void followedOnlyAsksForWatchedMarketsNeverTheNearbyCascade() {
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), eq(Set.of(WATCHED_CNPJ))))
                .thenReturn(List.of());

        var deals = dealsService.findDeals(user(), false, 5.0, 20);

        assertTrue(deals.isEmpty());
        verify(priceIndexService, never()).bestMarketsInScope(any(), anyInt(), any());
    }

    @Test
    void includeNearbyUsesViewerScopeWithRadiusAndSurfacesNearbyDeal() {
        var user = user();
        var scope = new NearbyScope(new ViewerLocation(new BigDecimal("-30"), new BigDecimal("-51"),
                Source.RECEIPTS_MEDIAN, "RS"), Set.of(WATCHED_CNPJ), 5.0);
        when(nearbyScopeService.forUser(user, 5.0, null, null)).thenReturn(scope);
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(purchase(new BigDecimal("10.00"), LocalDateTime.now())));
        when(priceIndexService.bestMarketsInScope(eq(PRODUCT_ID), anyInt(), eq(scope)))
                .thenReturn(List.of(market(NEARBY_CNPJ, new BigDecimal("7.00"), 4L, 2.3, false)));

        var deals = dealsService.findDeals(user, true, 5.0, 20);

        assertEquals(1, deals.size());
        assertEquals(NEARBY_CNPJ, deals.get(0).marketCnpj());
        assertEquals(2.3, deals.get(0).distanceKm());
        verify(priceIndexService, never()).bestMarketsAtWatched(any(), anyInt(), any());
    }

    @Test
    void ranksBySavingsDescending() {
        var cheaperProductId = UUID.randomUUID();
        var pricierProduct = Product.builder().id(cheaperProductId).normalizedName("Café")
                .category(ProductCategory.GROCERIES).build();
        var lowSavingsItem = purchase(new BigDecimal("10.00"), LocalDateTime.now());
        var highSavingsItem = ReceiptItem.builder().product(pricierProduct)
                .unitPrice(new BigDecimal("20.00"))
                .receipt(Receipt.builder().issuedAt(LocalDateTime.now()).cnpjEmitente(HOME_CNPJ).build()).build();
        when(receiptItemRepository.findConfirmedHistoryForHousehold(HOUSEHOLD_ID))
                .thenReturn(List.of(lowSavingsItem, highSavingsItem));

        // PRODUCT_ID: 20% drop (10 -> 8). cheaperProductId: 50% drop (20 -> 10).
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("8.00"), 3L, null, true)));
        when(priceIndexService.bestMarketsAtWatched(eq(cheaperProductId), anyInt(), any()))
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
        when(priceIndexService.bestMarketsAtWatched(eq(PRODUCT_ID), anyInt(), any()))
                .thenReturn(List.of(market(WATCHED_CNPJ, new BigDecimal("7.00"), 3L, null, true)));
        when(householdProductAliasService.friendlyNamesFor(eq(HOUSEHOLD_ID), any()))
                .thenReturn(Map.of(PRODUCT_ID, "Leite das criancas"));

        var deals = dealsService.findDeals(user(), false, null, 20);

        assertEquals("Leite", deals.get(0).productName());
        assertEquals("Leite das criancas", deals.get(0).friendlyDescription());
    }
}
