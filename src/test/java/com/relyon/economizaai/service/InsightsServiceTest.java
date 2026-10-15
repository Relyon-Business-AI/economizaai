package com.relyon.economizaai.service;

import com.relyon.economizaai.exception.ProductNotFoundException;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.geo.MarketNameService;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsightsServiceTest {

    private static final LocalDateTime EPOCH_FLOOR = LocalDateTime.of(1900, Month.JANUARY, 1, 0, 0);
    private static final LocalDateTime EPOCH_CEIL = LocalDateTime.of(2999, Month.DECEMBER, 31, 23, 59, 59);

    @Mock private InsightsRepository insightsRepository;
    @Mock private ProductRepository productRepository;
    @Mock private MarketNameService marketNameService;
    @Mock private HouseholdProductCategoryOverrideService categoryOverrideService;
    @Mock private CategoryResolver categoryResolver;
    @Mock private SubscriptionGateService subscriptionGate;
    @Mock private HouseholdProductAliasService householdProductAliasService;

    @InjectMocks private InsightsService insightsService;

    @BeforeEach
    void stubMarketNames() {
        lenient().when(marketNameService.resolveNames(any(), any())).thenReturn(Map.of());
        lenient().when(marketNameService.applyOverride(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        // Default: PRO passthrough (no history clamping).
        lenient().when(subscriptionGate.clampFrom(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private User buildUser() {
        var household = Household.builder().id(UUID.randomUUID()).inviteCode("HH0001").build();
        return User.builder().id(UUID.randomUUID()).email("maria@test.com").household(household).build();
    }

    private static final String ALL_SCOPE = "ALL";
    private static final List<String> ALL_CNPJS = List.of("__none__");

    private void stubEmptyAggregates(UUID householdId, LocalDateTime fromBound, LocalDateTime toBound) {
        lenient().when(insightsRepository.totalSpend(householdId, fromBound, toBound, ALL_SCOPE, ALL_CNPJS)).thenReturn(BigDecimal.ZERO);
        lenient().when(insightsRepository.spendByMonth(householdId, fromBound, toBound, ALL_SCOPE, ALL_CNPJS)).thenReturn(List.of());
        lenient().when(insightsRepository.spendByWeek(householdId, fromBound, toBound, ALL_SCOPE, ALL_CNPJS)).thenReturn(List.of());
        lenient().when(insightsRepository.spendByMarket(householdId, fromBound, toBound, ALL_SCOPE, ALL_CNPJS)).thenReturn(List.of());
        lenient().when(insightsRepository.spendByProductNode(householdId, fromBound, toBound, ALL_SCOPE, ALL_CNPJS)).thenReturn(List.of());
    }

    @Test
    void spend_mapsAllBucketsFromRepositoryRows() {
        var user = buildUser();
        var householdId = user.getHousehold().getId();
        var from = LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0);
        var to = LocalDateTime.of(2026, Month.JUNE, 30, 23, 59);

        when(insightsRepository.totalSpend(householdId, from, to, ALL_SCOPE, ALL_CNPJS)).thenReturn(new BigDecimal("123.45"));
        when(insightsRepository.spendByMonth(householdId, from, to, ALL_SCOPE, ALL_CNPJS))
                .thenReturn(List.<Object[]>of(new Object[]{2026, 4, new BigDecimal("50.00"), 2L}));
        when(insightsRepository.spendByWeek(householdId, from, to, ALL_SCOPE, ALL_CNPJS))
                .thenReturn(List.<Object[]>of(new Object[]{2026, 17, new BigDecimal("25.00"), 1L}));
        when(insightsRepository.spendByMarket(householdId, from, to, ALL_SCOPE, ALL_CNPJS))
                .thenReturn(List.<Object[]>of(new Object[]{"12345678000190", "Zaffari", new BigDecimal("73.45"), 3L}));
        var productId = UUID.randomUUID();
        var categoryId = UUID.randomUUID();
        when(insightsRepository.spendByProductNode(householdId, from, to, ALL_SCOPE, ALL_CNPJS))
                .thenReturn(List.<Object[]>of(new Object[]{productId, categoryId, new BigDecimal("40.00"), 7L}));
        when(categoryOverrideService.effectiveCategoryByProduct(eq(householdId), any())).thenReturn(Map.of());
        when(categoryResolver.slugForId(categoryId)).thenReturn("mercearia");
        when(categoryResolver.labelFor(eq(categoryId), any())).thenReturn("Mercearia");

        var response = insightsService.spend(user, from, to);

        assertEquals(new BigDecimal("123.45"), response.total());
        assertEquals(from, response.from());
        assertEquals(to, response.to());

        assertEquals(1, response.byMonth().size());
        var monthBucket = response.byMonth().get(0);
        assertEquals(2026, monthBucket.year());
        assertEquals(4, monthBucket.month());
        assertEquals(new BigDecimal("50.00"), monthBucket.total());
        assertEquals(2L, monthBucket.receiptCount());

        var weekBucket = response.byWeek().get(0);
        assertEquals(2026, weekBucket.year());
        assertEquals(17, weekBucket.week());
        assertEquals(1L, weekBucket.receiptCount());

        var marketBucket = response.byMarket().get(0);
        assertEquals("12345678000190", marketBucket.cnpj());
        assertEquals("Zaffari", marketBucket.marketName());
        assertEquals(new BigDecimal("73.45"), marketBucket.total());
        assertEquals(3L, marketBucket.receiptCount());

        var categoryBucket = response.byCategory().get(0);
        assertNull(categoryBucket.category());
        assertEquals("mercearia", categoryBucket.slug());
        assertEquals("Mercearia", categoryBucket.label());
        assertEquals(new BigDecimal("40.00"), categoryBucket.total());
        assertEquals(7L, categoryBucket.itemCount());
    }

    @Test
    void spend_usesEpochBoundsWhenDatesNull() {
        var user = buildUser();
        var householdId = user.getHousehold().getId();
        stubEmptyAggregates(householdId, EPOCH_FLOOR, EPOCH_CEIL);

        var response = insightsService.spend(user, null, null);

        assertEquals(null, response.from());
        assertEquals(null, response.to());
        assertTrue(response.byMonth().isEmpty());
        verify(insightsRepository).totalSpend(householdId, EPOCH_FLOOR, EPOCH_CEIL, ALL_SCOPE, ALL_CNPJS);
        verify(insightsRepository).spendByMonth(householdId, EPOCH_FLOOR, EPOCH_CEIL, ALL_SCOPE, ALL_CNPJS);
        verify(insightsRepository).spendByWeek(householdId, EPOCH_FLOOR, EPOCH_CEIL, ALL_SCOPE, ALL_CNPJS);
        verify(insightsRepository).spendByMarket(householdId, EPOCH_FLOOR, EPOCH_CEIL, ALL_SCOPE, ALL_CNPJS);
        verify(insightsRepository).spendByProductNode(householdId, EPOCH_FLOOR, EPOCH_CEIL, ALL_SCOPE, ALL_CNPJS);
    }

    @Test
    void spend_coercesDoubleAndNullAggregatesToBigDecimal() {
        var user = buildUser();
        var householdId = user.getHousehold().getId();
        stubEmptyAggregates(householdId, EPOCH_FLOOR, EPOCH_CEIL);
        // double total + null category total exercise the toBigDecimal Number and null branches
        when(insightsRepository.spendByMonth(householdId, EPOCH_FLOOR, EPOCH_CEIL, ALL_SCOPE, ALL_CNPJS))
                .thenReturn(List.<Object[]>of(new Object[]{2026, 3, 12.5d, 1L}));
        when(insightsRepository.spendByProductNode(householdId, EPOCH_FLOOR, EPOCH_CEIL, ALL_SCOPE, ALL_CNPJS))
                .thenReturn(List.<Object[]>of(new Object[]{UUID.randomUUID(), null, null, 4L}));
        when(categoryOverrideService.effectiveCategoryByProduct(eq(householdId), any())).thenReturn(Map.of());

        var response = insightsService.spend(user, null, null);

        assertEquals(BigDecimal.valueOf(12.5d), response.byMonth().get(0).total());
        assertEquals(BigDecimal.ZERO, response.byCategory().get(0).total());
    }

    @Test
    void topMarkets_appliesLimit() {
        var user = buildUser();
        var householdId = user.getHousehold().getId();
        stubEmptyAggregates(householdId, EPOCH_FLOOR, EPOCH_CEIL);
        when(insightsRepository.spendByMarket(householdId, EPOCH_FLOOR, EPOCH_CEIL, ALL_SCOPE, ALL_CNPJS))
                .thenReturn(List.of(
                        new Object[]{"111", "A", new BigDecimal("30.00"), 3L},
                        new Object[]{"222", "B", new BigDecimal("20.00"), 2L},
                        new Object[]{"333", "C", new BigDecimal("10.00"), 1L}));

        var top = insightsService.topMarkets(user, null, null, 2);

        assertEquals(2, top.size());
        assertEquals("A", top.get(0).marketName());
        assertEquals("B", top.get(1).marketName());
    }

    @Test
    void topMarketsByDiscount_ranksByDiscountDescExcludesZeroAndComputesRate() {
        var user = buildUser();
        var householdId = user.getHousehold().getId();
        stubEmptyAggregates(householdId, EPOCH_FLOOR, EPOCH_CEIL);
        when(insightsRepository.spendByMarket(householdId, EPOCH_FLOOR, EPOCH_CEIL, ALL_SCOPE, ALL_CNPJS))
                .thenReturn(List.of(
                        new Object[]{"111", "A", new BigDecimal("30.00"), 3L},
                        new Object[]{"222", "B", new BigDecimal("20.00"), 2L},
                        new Object[]{"333", "C", new BigDecimal("10.00"), 1L}));
        when(insightsRepository.discountByMarket(householdId, EPOCH_FLOOR, EPOCH_CEIL, ALL_SCOPE, ALL_CNPJS))
                .thenReturn(List.of(
                        new Object[]{"111", new BigDecimal("3.00")},
                        new Object[]{"222", new BigDecimal("9.00")}));
        // market "333" gave no discount → excluded entirely.

        var top = insightsService.topMarketsByDiscount(user, null, null, 5);

        assertEquals(2, top.size());
        // ranked by discount desc: B (9.00) before A (3.00)
        assertEquals("222", top.get(0).cnpj());
        assertEquals(0, new BigDecimal("9.00").compareTo(top.get(0).discount()));
        assertEquals(0, new BigDecimal("0.4500").compareTo(top.get(0).discountRate()));
        assertEquals("111", top.get(1).cnpj());
        assertEquals(0, new BigDecimal("0.1000").compareTo(top.get(1).discountRate()));
    }

    @Test
    void topMarkets_negativeLimit_returnsEmptyInsteadOfThrowing() {
        var user = buildUser();
        var householdId = user.getHousehold().getId();
        stubEmptyAggregates(householdId, EPOCH_FLOOR, EPOCH_CEIL);
        when(insightsRepository.spendByMarket(householdId, EPOCH_FLOOR, EPOCH_CEIL, ALL_SCOPE, ALL_CNPJS))
                .thenReturn(List.of(
                        new Object[]{"111", "A", new BigDecimal("30.00"), 3L},
                        new Object[]{"222", "B", new BigDecimal("20.00"), 2L}));

        // A negative limit reaches the controller unvalidated (?limit=-1) and would
        // otherwise hit Stream.limit(-1), which throws IllegalArgumentException: -1.
        var top = insightsService.topMarkets(user, null, null, -1);

        assertTrue(top.isEmpty());
    }

    @Test
    void priceHistory_mapsPointsForExistingProduct() {
        var user = buildUser();
        var householdId = user.getHousehold().getId();
        var productId = UUID.randomUUID();
        var product = Product.builder().id(productId).normalizedName("Leite Italac 1L").build();
        var issuedAt = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(insightsRepository.priceHistoryForProduct(householdId, productId, EPOCH_FLOOR, EPOCH_CEIL))
                .thenReturn(List.<Object[]>of(new Object[]{
                        issuedAt, "12345678000190", "Zaffari",
                        new BigDecimal("4.99"), new BigDecimal("2.000")}));

        var response = insightsService.priceHistory(user, productId, null, null);

        assertEquals(productId, response.productId());
        assertEquals("Leite Italac 1L", response.productName());
        assertEquals(1, response.points().size());
        var point = response.points().get(0);
        assertEquals(issuedAt, point.issuedAt());
        assertEquals("12345678000190", point.marketCnpj());
        assertEquals("Zaffari", point.marketName());
        assertEquals(new BigDecimal("4.99"), point.unitPrice());
        assertEquals(new BigDecimal("2.000"), point.quantity());
    }

    @Test
    void priceHistory_honoursExplicitDateBounds() {
        var user = buildUser();
        var householdId = user.getHousehold().getId();
        var productId = UUID.randomUUID();
        var product = Product.builder().id(productId).normalizedName("Arroz").build();
        var from = LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0);
        var to = LocalDateTime.of(2026, Month.MARCH, 1, 0, 0);

        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(insightsRepository.priceHistoryForProduct(householdId, productId, from, to))
                .thenReturn(List.of());

        var response = insightsService.priceHistory(user, productId, from, to);

        assertTrue(response.points().isEmpty());
        var fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        var toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(insightsRepository).priceHistoryForProduct(eq(householdId), eq(productId), fromCaptor.capture(), toCaptor.capture());
        assertEquals(from, fromCaptor.getValue());
        assertEquals(to, toCaptor.getValue());
    }

    @Test
    void priceHistory_throwsWhenProductMissing() {
        var user = buildUser();
        var productId = UUID.randomUUID();
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        assertThrows(ProductNotFoundException.class,
                () -> insightsService.priceHistory(user, productId, null, null));
    }

    @Test
    void priceHistory_carriesHouseholdFriendlyName() {
        var user = buildUser();
        var productId = UUID.randomUUID();
        var product = Product.builder().id(productId).normalizedName("LEITE ITALAC 1L").build();
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(insightsRepository.priceHistoryForProduct(any(), any(), any(), any())).thenReturn(List.of());
        when(householdProductAliasService.findFor(user.getHousehold(), product)).thenReturn("Leite da semana");

        var response = insightsService.priceHistory(user, productId, null, null);

        assertEquals("LEITE ITALAC 1L", response.productName());
        assertEquals("Leite da semana", response.friendlyDescription());
    }
}
