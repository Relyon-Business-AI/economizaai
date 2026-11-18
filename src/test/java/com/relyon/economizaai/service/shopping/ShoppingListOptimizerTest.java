package com.relyon.economizaai.service.shopping;

import com.relyon.economizaai.config.CollaborativeProperties;
import com.relyon.economizaai.dto.request.OptimizeShoppingListRequest;
import com.relyon.economizaai.dto.response.ShoppingPlanResponse.PlanItem;
import com.relyon.economizaai.exception.PaywallException;
import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.PriceObservation;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.Receipt;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.PriceObservationAuditRepository;
import com.relyon.economizaai.repository.PriceObservationRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.model.MarketLocation;
import com.relyon.economizaai.repository.MarketLocationRepository;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.geo.ViewerLocationService;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import com.relyon.economizaai.service.subscription.Feature;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class ShoppingListOptimizerTest {

    @Mock private ReceiptItemRepository receiptItemRepository;
    @Mock private PriceObservationRepository observationRepository;
    @Mock private PriceObservationAuditRepository auditRepository;
    @Mock private ProductRepository productRepository;
    @Mock private HouseholdProductAliasService householdProductAliasService;
    @Mock private MarketNameService marketNameService;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private MarketLocationRepository marketLocationRepository;
    @Mock private ViewerLocationService viewerLocationService;

    private CollaborativeProperties properties;
    private ShoppingListOptimizer optimizer;
    private User user;
    private Household household;

    @BeforeEach
    void setUp() {
        properties = new CollaborativeProperties();
        optimizer = new ShoppingListOptimizer(receiptItemRepository, observationRepository,
                auditRepository, productRepository, properties, marketNameService, subscriptionGate,
                householdProductAliasService, marketLocationRepository, viewerLocationService);
        household = Household.builder().id(UUID.randomUUID()).inviteCode("ABC123").build();
        user = User.builder().id(UUID.randomUUID()).email("buyer@economizai").household(household).build();
        lenient().when(observationRepository.findRecentByProduct(any(), any())).thenReturn(List.of());
        lenient().when(receiptItemRepository.findHouseholdHistoryForProduct(any(), any())).thenReturn(List.of());
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketLocationRepository.findAllByCnpjIn(any())).thenReturn(List.of());
        lenient().when(viewerLocationService.resolve(any(), any(), any()))
                .thenReturn(new ViewerLocation(null, null, ViewerLocationService.Source.NONE, null));
        lenient().when(marketNameService.applyOverride(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    @Test
    void optimize_picksCheapestLocalHistoryMarket() {
        var product = product("Arroz");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(receiptItemRepository.findHouseholdHistoryForProduct(eq(product.getId()), any())).thenReturn(List.of(
                historyItem(product, "11111111111111", "Mercado Caro", new BigDecimal("9.00")),
                historyItem(product, "22222222222222", "Mercado Barato", new BigDecimal("6.00"))
        ));

        var plan = optimizer.optimize(user, request(product.getId(), new BigDecimal("2")));

        assertEquals(1, plan.marketPlans().size());
        var marketPlan = plan.marketPlans().get(0);
        assertEquals("22222222222222", marketPlan.marketCnpj());
        assertEquals("Mercado Barato", marketPlan.marketName());
        assertEquals(1, marketPlan.itemCount());
        var planItem = marketPlan.items().get(0);
        assertEquals(PlanItem.PriceSource.LOCAL_HISTORY, planItem.priceSource());
        // 6.00 * 2 = 12.00
        assertEquals(0, planItem.estimatedSubtotal().compareTo(new BigDecimal("12.00")));
        assertEquals(0, plan.estimatedTotal().compareTo(new BigDecimal("12.00")));
        assertTrue(plan.unpriced().isEmpty());
        // community fallback never queried because local history covered the markets it found,
        // but it IS still queried since the loop runs regardless — verify it ran with no rows.
        verify(auditRepository, never()).countDistinctHouseholdsForProductMarket(any(), any(), any());
    }

    @Test
    void optimize_throwsPaywallForFreeTier() {
        doThrow(new PaywallException(Feature.BASKET_OPTIMIZATION.name()))
                .when(subscriptionGate).require(user, Feature.BASKET_OPTIMIZATION);

        var request = request(UUID.randomUUID(), BigDecimal.ONE);
        assertThrows(PaywallException.class,
                () -> optimizer.optimize(user, request));
        verify(productRepository, never()).findById(any());
    }

    @Test
    void optimize_keepsMostRecentLocalRowPerMarketViaMergeFunction() {
        var product = product("Feijao");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        // Two rows same CNPJ: findHouseholdHistory returns oldest-first, so the LAST row is the
        // most recent purchase and the one the plan must price with.
        when(receiptItemRepository.findHouseholdHistoryForProduct(eq(product.getId()), any())).thenReturn(List.of(
                historyItem(product, "11111111111111", "Mercado A", new BigDecimal("4.50")),
                historyItem(product, "11111111111111", "Mercado A", new BigDecimal("5.50"))
        ));

        var plan = optimizer.optimize(user, request(product.getId(), BigDecimal.ONE));

        assertEquals(1, plan.marketPlans().size());
        assertEquals(0, plan.marketPlans().get(0).items().get(0).estimatedUnitPrice()
                .compareTo(new BigDecimal("5.50")));
    }

    @Test
    void optimize_skipsLocalRowsWithNullCnpjOrNullPrice() {
        var product = product("Cafe");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(receiptItemRepository.findHouseholdHistoryForProduct(eq(product.getId()), any())).thenReturn(List.of(
                historyItem(product, null, "Sem CNPJ", new BigDecimal("3.00")),
                historyItem(product, "33333333333333", "Sem Preco", null),
                historyItem(product, "44444444444444", "Valido", new BigDecimal("7.00"))
        ));

        var plan = optimizer.optimize(user, request(product.getId(), BigDecimal.ONE));

        assertEquals(1, plan.marketPlans().size());
        assertEquals("44444444444444", plan.marketPlans().get(0).marketCnpj());
    }

    @Test
    void optimize_movesItemToUnpricedWhenNoSource() {
        var product = product("Quinoa");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        var plan = optimizer.optimize(user, request(product.getId(), new BigDecimal("3")));

        assertTrue(plan.marketPlans().isEmpty());
        assertEquals(1, plan.unpriced().size());
        var unpriced = plan.unpriced().get(0);
        assertEquals(product.getId(), unpriced.productId());
        assertEquals("Quinoa", unpriced.productName());
        assertEquals(0, unpriced.quantity().compareTo(new BigDecimal("3")));
        assertEquals(0, plan.estimatedTotal().compareTo(BigDecimal.ZERO));
    }

    @Test
    void optimize_usesCommunityFallbackWhenLocalHistoryAbsent() {
        var product = product("Azeite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        properties.getCollaborative().setMinObservationsPerProductMarket(2);
        properties.getCollaborative().setMinHouseholdsForPublic(3);
        when(observationRepository.findRecentByProduct(eq(product.getId()), any())).thenReturn(List.of(
                observation(product, "55555555555555", "Comunitario", new BigDecimal("20.00")),
                observation(product, "55555555555555", "Comunitario", new BigDecimal("18.00")),
                observation(product, "55555555555555", "Comunitario", new BigDecimal("22.00"))
        ));
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(product.getId()), eq("55555555555555"), any()))
                .thenReturn(4L);

        var plan = optimizer.optimize(user, request(product.getId(), new BigDecimal("2")));

        assertEquals(1, plan.marketPlans().size());
        var planItem = plan.marketPlans().get(0).items().get(0);
        assertEquals(PlanItem.PriceSource.COMMUNITY_INDEX, planItem.priceSource());
        // sorted prices [18,20,22], median = index 3/2=1 → 20.00
        assertEquals(0, planItem.estimatedUnitPrice().compareTo(new BigDecimal("20.00")));
        assertEquals(0, planItem.estimatedSubtotal().compareTo(new BigDecimal("40.00")));
    }

    @Test
    void optimize_communitySkippedWhenCollaborativeDisabled() {
        var product = product("Manteiga");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        properties.getCollaborative().setEnabled(false);
        // even if observations existed, disabled flag short-circuits the panel
        var plan = optimizer.optimize(user, request(product.getId(), BigDecimal.ONE));

        assertTrue(plan.marketPlans().isEmpty());
        assertEquals(1, plan.unpriced().size());
        verify(observationRepository, never()).findRecentByProduct(any(), any());
    }

    @Test
    void optimize_communitySkippedWhenBelowMinObservations() {
        var product = product("Acucar");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        properties.getCollaborative().setMinObservationsPerProductMarket(5);
        when(observationRepository.findRecentByProduct(eq(product.getId()), any())).thenReturn(List.of(
                observation(product, "66666666666666", "Poucas Obs", new BigDecimal("3.00")),
                observation(product, "66666666666666", "Poucas Obs", new BigDecimal("3.50"))
        ));

        var plan = optimizer.optimize(user, request(product.getId(), BigDecimal.ONE));

        assertEquals(1, plan.unpriced().size());
        verify(auditRepository, never()).countDistinctHouseholdsForProductMarket(any(), any(), any());
    }

    @Test
    void optimize_communitySkippedWhenBelowKAnonymityHouseholdFloor() {
        var product = product("Leite");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        properties.getCollaborative().setMinObservationsPerProductMarket(2);
        properties.getCollaborative().setMinHouseholdsForPublic(3);
        when(observationRepository.findRecentByProduct(eq(product.getId()), any())).thenReturn(List.of(
                observation(product, "77777777777777", "Sub K", new BigDecimal("4.00")),
                observation(product, "77777777777777", "Sub K", new BigDecimal("4.20"))
        ));
        when(auditRepository.countDistinctHouseholdsForProductMarket(eq(product.getId()), eq("77777777777777"), any()))
                .thenReturn(2L);

        var plan = optimizer.optimize(user, request(product.getId(), BigDecimal.ONE));

        assertEquals(1, plan.unpriced().size());
        assertTrue(plan.marketPlans().isEmpty());
    }

    @Test
    void optimize_communityDoesNotOverrideMarketAlreadyCoveredLocally() {
        var product = product("Pao");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        properties.getCollaborative().setMinObservationsPerProductMarket(1);
        properties.getCollaborative().setMinHouseholdsForPublic(1);
        when(receiptItemRepository.findHouseholdHistoryForProduct(eq(product.getId()), any())).thenReturn(List.of(
                historyItem(product, "88888888888888", "Local Cnpj", new BigDecimal("5.00"))
        ));
        when(observationRepository.findRecentByProduct(eq(product.getId()), any())).thenReturn(List.of(
                observation(product, "88888888888888", "Community Cnpj", new BigDecimal("1.00"))
        ));

        var plan = optimizer.optimize(user, request(product.getId(), BigDecimal.ONE));

        var planItem = plan.marketPlans().get(0).items().get(0);
        // local history wins for that CNPJ — community row for same CNPJ skipped
        assertEquals(PlanItem.PriceSource.LOCAL_HISTORY, planItem.priceSource());
        assertEquals(0, planItem.estimatedUnitPrice().compareTo(new BigDecimal("5.00")));
        verify(auditRepository, never()).countDistinctHouseholdsForProductMarket(any(), any(), any());
    }

    @Test
    void optimize_groupsItemsPerMarketAndSortsBySubtotalDescending() {
        var cheapItem = product("Sal");
        var pricyItem = product("Whisky");
        when(productRepository.findById(cheapItem.getId())).thenReturn(Optional.of(cheapItem));
        when(productRepository.findById(pricyItem.getId())).thenReturn(Optional.of(pricyItem));
        when(receiptItemRepository.findHouseholdHistoryForProduct(eq(cheapItem.getId()), any())).thenReturn(List.of(
                historyItem(cheapItem, "AAAAAAAAAAAAAA", "Mercado Pequeno", new BigDecimal("2.00"))
        ));
        when(receiptItemRepository.findHouseholdHistoryForProduct(eq(pricyItem.getId()), any())).thenReturn(List.of(
                historyItem(pricyItem, "BBBBBBBBBBBBBB", "Mercado Grande", new BigDecimal("120.00"))
        ));

        var request = new OptimizeShoppingListRequest(List.of(
                new OptimizeShoppingListRequest.Item(cheapItem.getId(), BigDecimal.ONE),
                new OptimizeShoppingListRequest.Item(pricyItem.getId(), BigDecimal.ONE)
        ));

        var plan = optimizer.optimize(user, request);

        assertEquals(2, plan.marketPlans().size());
        // sorted by subtotal reversed → highest subtotal market first
        assertEquals("BBBBBBBBBBBBBB", plan.marketPlans().get(0).marketCnpj());
        assertEquals("AAAAAAAAAAAAAA", plan.marketPlans().get(1).marketCnpj());
        assertEquals(0, plan.estimatedTotal().compareTo(new BigDecimal("122.00")));
    }

    @Test
    void optimize_throwsWhenProductMissing() {
        var missingId = UUID.randomUUID();
        when(productRepository.findById(missingId)).thenReturn(Optional.empty());
        var request = request(missingId, BigDecimal.ONE);

        assertThrows(ProductNotFoundException.class,
                () -> optimizer.optimize(user, request));
    }

    private OptimizeShoppingListRequest request(UUID productId, BigDecimal quantity) {
        return new OptimizeShoppingListRequest(List.of(
                new OptimizeShoppingListRequest.Item(productId, quantity)));
    }

    private Product product(String name) {
        return Product.builder().id(UUID.randomUUID()).normalizedName(name).build();
    }

    private ReceiptItem historyItem(Product product, String cnpj, String marketName, BigDecimal unitPrice) {
        var receipt = Receipt.builder()
                .id(UUID.randomUUID())
                .household(household)
                .user(user)
                .cnpjEmitente(cnpj)
                .marketName(marketName)
                .issuedAt(LocalDateTime.now().minusDays(3))
                .build();
        return ReceiptItem.builder()
                .id(UUID.randomUUID())
                .receipt(receipt)
                .product(product)
                .lineNumber(1)
                .rawDescription(product.getNormalizedName())
                .quantity(BigDecimal.ONE)
                .unitPrice(unitPrice)
                .totalPrice(unitPrice == null ? BigDecimal.ZERO : unitPrice)
                .build();
    }

    private PriceObservation observation(Product product, String cnpj, String marketName, BigDecimal unitPrice) {
        return PriceObservation.builder()
                .id(UUID.randomUUID())
                .product(product)
                .marketCnpj(cnpj)
                .marketCnpjRoot(cnpj.substring(0, 8))
                .marketName(marketName)
                .unitPrice(unitPrice)
                .quantity(BigDecimal.ONE)
                .observedAt(LocalDateTime.now().minusDays(2))
                .build();
    }

    @Test
    void optimize_carriesHouseholdFriendlyNameOnPlanAndUnpricedItems() {
        var priced = product("Arroz");
        var unpriced = product("Sabao");
        when(productRepository.findById(priced.getId())).thenReturn(Optional.of(priced));
        when(productRepository.findById(unpriced.getId())).thenReturn(Optional.of(unpriced));
        when(receiptItemRepository.findHouseholdHistoryForProduct(eq(priced.getId()), any())).thenReturn(List.of(
                historyItem(priced, "11111111111111", "Mercado X", new BigDecimal("6.00"))
        ));
        when(householdProductAliasService.friendlyNamesFor(eq(household.getId()), any()))
                .thenReturn(Map.of(priced.getId(), "Arroz do mes", unpriced.getId(), "Sabao da maquina"));

        var request = new OptimizeShoppingListRequest(List.of(
                new OptimizeShoppingListRequest.Item(priced.getId(), BigDecimal.ONE),
                new OptimizeShoppingListRequest.Item(unpriced.getId(), BigDecimal.ONE)));
        var plan = optimizer.optimize(user, request);

        assertEquals("Arroz do mes", plan.marketPlans().get(0).items().get(0).friendlyDescription());
        assertEquals("Sabao da maquina", plan.unpriced().get(0).friendlyDescription());
    }

    private static final String NEAR_MARKET = "11111111000111";
    private static final String MID_MARKET = "22222222000122";
    private static final String FAR_MARKET = "33333333000133";

    /** Shopper at the origin; near ≈ 1.1 km, mid ≈ 3.3 km, far ≈ 33 km away. */
    private void placeShopperAndMarkets() {
        when(viewerLocationService.resolve(any(), any(), any())).thenReturn(new ViewerLocation(
                BigDecimal.ZERO, BigDecimal.ZERO, ViewerLocationService.Source.DEVICE, "RS"));
        when(marketLocationRepository.findAllByCnpjIn(any())).thenReturn(List.of(
                marketAt(NEAR_MARKET, "0.01"), marketAt(MID_MARKET, "0.03"), marketAt(FAR_MARKET, "0.3")));
    }

    private MarketLocation marketAt(String cnpj, String latitude) {
        return MarketLocation.builder().cnpj(cnpj).cnpjRoot(cnpj.substring(0, 8))
                .latitude(new BigDecimal(latitude)).longitude(BigDecimal.ZERO).build();
    }

    private Product pricedProduct(String name, Map<String, String> priceByMarket) {
        var product = product(name);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(receiptItemRepository.findHouseholdHistoryForProduct(eq(product.getId()), any())).thenReturn(
                priceByMarket.entrySet().stream()
                        .map(price -> historyItem(product, price.getKey(), "Mercado " + price.getKey(),
                                new BigDecimal(price.getValue())))
                        .toList());
        return product;
    }

    private OptimizeShoppingListRequest requestWithRules(BigDecimal maxDistanceKm, Integer maxStops,
                                                         BigDecimal minSavingPerStop, Product... products) {
        var items = List.of(products).stream()
                .map(product -> new OptimizeShoppingListRequest.Item(product.getId(), BigDecimal.ONE))
                .toList();
        return new OptimizeShoppingListRequest(items, maxDistanceKm, maxStops, minSavingPerStop, null, null);
    }

    @Test
    void optimize_maxDistanceLeavesOutFarMarketAndReportsSkippedSaving() {
        placeShopperAndMarkets();
        var coffee = pricedProduct("Cafe", Map.of(NEAR_MARKET, "20.00", FAR_MARKET, "10.00"));

        var plan = optimizer.optimize(user, requestWithRules(new BigDecimal("10"), null, null, coffee));

        assertEquals(1, plan.marketPlans().size());
        assertEquals(NEAR_MARKET, plan.marketPlans().get(0).marketCnpj());
        assertEquals(0, plan.estimatedTotal().compareTo(new BigDecimal("20.00")));
        // The far market is outside the rules entirely, so it is not offered as a skipped saving either.
        assertTrue(plan.skippedSavings().isEmpty());
    }

    @Test
    void optimize_maxDistanceMakesItemUnpricedWhenOnlyFarMarketSellsIt() {
        placeShopperAndMarkets();
        var coffee = pricedProduct("Cafe", Map.of(FAR_MARKET, "10.00"));

        var plan = optimizer.optimize(user, requestWithRules(new BigDecimal("10"), null, null, coffee));

        assertTrue(plan.marketPlans().isEmpty());
        assertEquals(1, plan.unpriced().size());
        assertEquals("no observed price within the route rules", plan.unpriced().get(0).reason());
    }

    @Test
    void optimize_minSavingDropsStopThatSavesTooLittle() {
        placeShopperAndMarkets();
        var coffee = pricedProduct("Cafe", Map.of(NEAR_MARKET, "30.00", MID_MARKET, "40.00"));
        var oil = pricedProduct("Oleo", Map.of(NEAR_MARKET, "9.00", MID_MARKET, "7.80"));

        var plan = optimizer.optimize(user, requestWithRules(null, null, new BigDecimal("10"), coffee, oil));

        assertEquals(1, plan.marketPlans().size());
        assertEquals(NEAR_MARKET, plan.marketPlans().get(0).marketCnpj());
        assertEquals(0, plan.estimatedTotal().compareTo(new BigDecimal("39.00")));
        assertEquals(1, plan.skippedSavings().size());
        var skipped = plan.skippedSavings().get(0);
        assertEquals(oil.getId(), skipped.productId());
        assertEquals(MID_MARKET, skipped.marketCnpj());
        assertEquals(0, skipped.saving().compareTo(new BigDecimal("1.20")));
    }

    @Test
    void optimize_minSavingKeepsStopThatPaysOff() {
        placeShopperAndMarkets();
        var coffee = pricedProduct("Cafe", Map.of(NEAR_MARKET, "30.00", MID_MARKET, "45.00"));
        var meat = pricedProduct("Carne", Map.of(NEAR_MARKET, "60.00", MID_MARKET, "40.00"));

        var plan = optimizer.optimize(user, requestWithRules(null, null, new BigDecimal("10"), coffee, meat));

        assertEquals(2, plan.marketPlans().size());
        assertTrue(plan.skippedSavings().isEmpty());
    }

    @Test
    void optimize_minSavingKeepsStopHoldingItemSoldNowhereElse() {
        placeShopperAndMarkets();
        var coffee = pricedProduct("Cafe", Map.of(NEAR_MARKET, "30.00"));
        var salt = pricedProduct("Sal", Map.of(MID_MARKET, "2.00"));

        var plan = optimizer.optimize(user, requestWithRules(null, null, new BigDecimal("10"), coffee, salt));

        assertEquals(2, plan.marketPlans().size());
        assertTrue(plan.unpriced().isEmpty());
    }

    @Test
    void optimize_maxStopsDropsTheStopThatCostsLeastToGiveUp() {
        placeShopperAndMarkets();
        var coffee = pricedProduct("Cafe", Map.of(NEAR_MARKET, "30.00", MID_MARKET, "50.00", FAR_MARKET, "55.00"));
        var meat = pricedProduct("Carne", Map.of(NEAR_MARKET, "70.00", MID_MARKET, "40.00", FAR_MARKET, "75.00"));
        var oil = pricedProduct("Oleo", Map.of(NEAR_MARKET, "9.00", MID_MARKET, "9.50", FAR_MARKET, "7.00"));

        var plan = optimizer.optimize(user, requestWithRules(null, 2, null, coffee, meat, oil));

        assertEquals(2, plan.marketPlans().size());
        assertEquals(List.of(NEAR_MARKET, MID_MARKET),
                plan.marketPlans().stream().map(marketPlan -> marketPlan.marketCnpj()).toList());
        // 30 (cafe) + 40 (carne) + 9 (oleo moves to the near market)
        assertEquals(0, plan.estimatedTotal().compareTo(new BigDecimal("79.00")));
        assertEquals(FAR_MARKET, plan.skippedSavings().get(0).marketCnpj());
    }

    @Test
    void optimize_maxStopsSendsStrandedItemToUnpriced() {
        placeShopperAndMarkets();
        var coffee = pricedProduct("Cafe", Map.of(NEAR_MARKET, "30.00"));
        var meat = pricedProduct("Carne", Map.of(NEAR_MARKET, "70.00"));
        var salt = pricedProduct("Sal", Map.of(MID_MARKET, "2.00"));

        var plan = optimizer.optimize(user, requestWithRules(null, 1, null, coffee, meat, salt));

        assertEquals(1, plan.marketPlans().size());
        assertEquals(NEAR_MARKET, plan.marketPlans().get(0).marketCnpj());
        assertEquals(1, plan.unpriced().size());
        assertEquals(salt.getId(), plan.unpriced().get(0).productId());
    }

    @Test
    void optimize_ordersStopsNearestFirstAndCarriesPosition() {
        placeShopperAndMarkets();
        var coffee = pricedProduct("Cafe", Map.of(MID_MARKET, "30.00"));
        var salt = pricedProduct("Sal", Map.of(NEAR_MARKET, "2.00"));

        var plan = optimizer.optimize(user, requestWithRules(null, null, null, coffee, salt));

        assertEquals(List.of(NEAR_MARKET, MID_MARKET),
                plan.marketPlans().stream().map(marketPlan -> marketPlan.marketCnpj()).toList());
        var nearest = plan.marketPlans().get(0);
        assertEquals(0, nearest.latitude().compareTo(new BigDecimal("0.01")));
        assertTrue(nearest.distanceKm() > 1.0 && nearest.distanceKm() < 1.2);
    }
}
