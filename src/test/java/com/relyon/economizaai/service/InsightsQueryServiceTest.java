package com.relyon.economizaai.service;
import com.relyon.economizaai.model.Household;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategoryView;
import com.relyon.economizaai.model.enums.InsightsGroupBy;
import com.relyon.economizaai.model.enums.MarketScope;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.service.InsightsQueryService.QueryFilters;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsightsQueryServiceTest {

    @Mock private EntityManager entityManager;
    @Mock private TypedQuery<Object[]> summaryQuery;
    @Mock private TypedQuery<Object[]> bucketQuery;
    @Mock private TypedQuery<Object[]> totalDiscountQuery;
    @Mock private TypedQuery<Object[]> bucketDiscountQuery;
    @Mock private HouseholdProductCategoryOverrideService categoryOverrideService;
    @Mock private SubscriptionGateService subscriptionGate;

    @InjectMocks private InsightsQueryService insightsQueryService;

    private User user;
    private UUID householdId;

    @BeforeEach
    void setUp() {
        // The service now takes a constructor dependency, so @InjectMocks uses
        // constructor injection and won't populate the @PersistenceContext field;
        // wire the EntityManager mock in by reflection.
        ReflectionTestUtils.setField(insightsQueryService, "entityManager", entityManager);
        // Route each created query to the right mock by JPQL shape, so tests only
        // stub the result rows and don't depend on the internal call order:
        // receipt-level discount queries (total + per-bucket) carry "discountTotal";
        // bucket queries carry "GROUP BY"; everything else is the summary scalar.
        lenient().when(entityManager.createQuery(anyString(), eq(Object[].class)))
                .thenAnswer(invocation -> {
                    String jpql = invocation.getArgument(0);
                    if (jpql.contains("r.id, r.discountTotal")) return totalDiscountQuery;
                    if (jpql.contains("discountTotal")) return bucketDiscountQuery;
                    if (jpql.contains("GROUP BY")) return bucketQuery;
                    return summaryQuery;
                });
        // Discount queries default to "no discount" unless a test says otherwise.
        lenient().when(totalDiscountQuery.getResultList()).thenReturn(List.<Object[]>of());
        lenient().when(bucketDiscountQuery.getResultList()).thenReturn(List.<Object[]>of());
        // Default: PRO passthrough (no history clamping).
        lenient().when(subscriptionGate.clampFrom(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        householdId = UUID.randomUUID();
        var household = Household.builder().id(householdId).inviteCode("HH0001").build();
        user = User.builder().id(UUID.randomUUID()).email("maria@test.com").household(household).build();
    }

    private QueryFilters filtersWith(InsightsGroupBy groupBy) {
        return QueryFilters.fromRequest(null, null, null, null, null, null, null, null, null, groupBy, null);
    }

    private QueryFilters filtersWith(InsightsGroupBy groupBy, CategoryView categoryView) {
        return QueryFilters.fromRequest(null, null, null, null, null, null, null, null, null,
                groupBy, null, categoryView);
    }

    @Test
    void query_groupByNone_returnsSummaryOnlyNoBuckets() {
        // groupBy NONE: only the summary scalar + the receipt-level discount total
        // (no bucket query).
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("100.00"), 4L, 12L});

        var response = insightsQueryService.query(user, filtersWith(InsightsGroupBy.NONE));

        assertEquals(InsightsGroupBy.NONE, response.groupBy());
        assertTrue(response.buckets().isEmpty());
        var summary = response.summary();
        assertEquals(new BigDecimal("100.00"), summary.total());
        assertEquals(4L, summary.receiptCount());
        assertEquals(12L, summary.itemCount());
        assertEquals(new BigDecimal("25.00"), summary.averageTicket());
        verify(entityManager, times(2)).createQuery(anyString(), eq(Object[].class));
    }

    @Test
    void query_averageTicketIsZeroWhenNoReceipts() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{BigDecimal.ZERO, 0L, 0L});

        var response = insightsQueryService.query(user, filtersWith(InsightsGroupBy.NONE));

        assertEquals(BigDecimal.ZERO, response.summary().averageTicket());
    }

    @Test
    void query_groupByCategory_globalLens_buildsBucketsAndAppliesLimit() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("60.00"), 3L, 9L});
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{ProductCategory.GROCERIES, new BigDecimal("40.00"), 2L, 5L},
                new Object[]{null, new BigDecimal("20.00"), 1L, 4L}));

        var response = insightsQueryService.query(user,
                filtersWith(InsightsGroupBy.CATEGORY, CategoryView.GLOBAL));

        assertEquals(InsightsGroupBy.CATEGORY, response.groupBy());
        assertEquals(2, response.buckets().size());
        var first = response.buckets().get(0);
        assertEquals("GROCERIES", first.key());
        assertEquals("GROCERIES", first.label());
        assertEquals(new BigDecimal("40.00"), first.total());
        assertEquals(2L, first.receiptCount());
        assertEquals(5L, first.itemCount());
        assertEquals(new BigDecimal("20.00"), first.averageTicket());
        // null category falls back to OTHER
        assertEquals(ProductCategory.OTHER.name(), response.buckets().get(1).key());
        verify(bucketQuery).setMaxResults(100);
        // GLOBAL lens never consults the override service.
        verify(categoryOverrideService, never()).overrideKeysByProduct(any(), any());
    }

    @Test
    void query_groupByCategory_householdLens_reaggregatesByEffectiveCategory() {
        var leite = UUID.randomUUID();   // global MEAT_DAIRY, overridden to GROCERIES
        var arroz = UUID.randomUUID();   // global GROCERIES, no override
        var receiptOne = UUID.randomUUID();
        var receiptTwo = UUID.randomUUID();
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("40.00"), 2L, 3L});
        // (productId, category, receiptId, total, itemCount) — leite + arroz share receiptOne.
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{leite, ProductCategory.MEAT_DAIRY, receiptOne, new BigDecimal("10.00"), 1L},
                new Object[]{arroz, ProductCategory.GROCERIES, receiptOne, new BigDecimal("20.00"), 1L},
                new Object[]{arroz, ProductCategory.GROCERIES, receiptTwo, new BigDecimal("10.00"), 1L}));
        when(categoryOverrideService.overrideKeysByProduct(eq(householdId), any()))
                .thenReturn(Map.of(leite,
                        new HouseholdProductCategoryOverrideService.OverrideKey("enum:GROCERIES", "GROCERIES")));

        var response = insightsQueryService.query(user, filtersWith(InsightsGroupBy.CATEGORY));

        // Single effective GROCERIES bucket: 10 (leite override) + 30 (arroz) = 40,
        // distinct receipts = {receiptOne, receiptTwo} = 2 (no double count).
        assertEquals(1, response.buckets().size());
        var bucket = response.buckets().get(0);
        assertEquals("GROCERIES", bucket.key());
        assertEquals("GROCERIES", bucket.label());
        assertEquals(0, new BigDecimal("40.00").compareTo(bucket.total()));
        assertEquals(2L, bucket.receiptCount());
        assertEquals(3L, bucket.itemCount());
    }

    @Test
    void query_groupByCategory_householdLens_customNamedLikeEnum_doesNotMerge() {
        var arroz = UUID.randomUUID();   // global OTHER, no override
        var sabao = UUID.randomUUID();   // moved into a custom category literally named "OTHER"
        var customId = UUID.randomUUID();
        var receiptOne = UUID.randomUUID();
        var receiptTwo = UUID.randomUUID();
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("19.00"), 2L, 2L});
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{arroz, ProductCategory.OTHER, receiptOne, new BigDecimal("12.00"), 1L},
                new Object[]{sabao, ProductCategory.CLEANING, receiptTwo, new BigDecimal("7.00"), 1L}));
        when(categoryOverrideService.overrideKeysByProduct(eq(householdId), any()))
                .thenReturn(Map.of(sabao,
                        new HouseholdProductCategoryOverrideService.OverrideKey("custom:" + customId, "OTHER")));

        var response = insightsQueryService.query(user, filtersWith(InsightsGroupBy.CATEGORY));

        // Two buckets despite both labeled "OTHER": discriminated keys keep them separate.
        var labeledOther = response.buckets().stream().filter(b -> "OTHER".equals(b.label())).toList();
        assertEquals(2, labeledOther.size());
        assertTrue(labeledOther.stream().anyMatch(b -> new BigDecimal("12.00").compareTo(b.total()) == 0));
        assertTrue(labeledOther.stream().anyMatch(b -> new BigDecimal("7.00").compareTo(b.total()) == 0));
    }

    @Test
    void query_groupByMonth_formatsTemporalKey() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("10.00"), 1L, 1L});
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{2026, 4, new BigDecimal("10.00"), 1L, 1L}));

        var response = insightsQueryService.query(user, filtersWith(InsightsGroupBy.MONTH));

        var bucket = response.buckets().get(0);
        assertEquals("2026-04", bucket.key());
        assertEquals("2026-04", bucket.label());
    }

    @Test
    void query_groupByDay_formatsTemporalKey() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("10.00"), 1L, 1L});
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{2026, 4, 7, new BigDecimal("10.00"), 1L, 1L}));

        var response = insightsQueryService.query(user, filtersWith(InsightsGroupBy.DAY));

        assertEquals("2026-04-07", response.buckets().get(0).key());
    }

    @Test
    void query_groupByWeek_formatsTemporalKey() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("10.00"), 1L, 1L});
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{2026, 17, new BigDecimal("10.00"), 1L, 1L}));

        var response = insightsQueryService.query(user, filtersWith(InsightsGroupBy.WEEK));

        assertEquals("2026-W17", response.buckets().get(0).key());
    }

    @Test
    void query_groupByYear_formatsTemporalKey() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("10.00"), 1L, 1L});
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{2026, new BigDecimal("10.00"), 1L, 1L}));

        var response = insightsQueryService.query(user, filtersWith(InsightsGroupBy.YEAR));

        assertEquals("2026", response.buckets().get(0).key());
    }

    @Test
    void query_groupByMarket_usesMarketNameAsLabelAndCnpjFallback() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("30.00"), 2L, 4L});
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{"12345678000190", "Zaffari", new BigDecimal("20.00"), 1L, 2L},
                new Object[]{"99999999000100", null, new BigDecimal("10.00"), 1L, 2L}));

        var response = insightsQueryService.query(user, filtersWith(InsightsGroupBy.MARKET));

        assertEquals("Zaffari", response.buckets().get(0).label());
        // null market name falls back to the CNPJ key
        assertEquals("99999999000100", response.buckets().get(1).label());
    }

    @Test
    void query_groupByChain_fallsBackToCnpjRoot() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("10.00"), 1L, 2L});
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{"12345678", null, new BigDecimal("10.00"), 1L, 2L}));

        var response = insightsQueryService.query(user, filtersWith(InsightsGroupBy.CHAIN));

        assertEquals("12345678", response.buckets().get(0).key());
        assertEquals("12345678", response.buckets().get(0).label());
    }

    @Test
    void query_groupByProduct_usesNameLabelAndUnknownFallback() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("30.00"), 2L, 4L});
        var productId = UUID.randomUUID();
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{productId, "Leite Italac 1L", new BigDecimal("20.00"), 1L, 2L},
                new Object[]{null, null, new BigDecimal("10.00"), 1L, 2L}));

        var response = insightsQueryService.query(user, filtersWith(InsightsGroupBy.PRODUCT));

        assertEquals(productId.toString(), response.buckets().get(0).key());
        assertEquals("Leite Italac 1L", response.buckets().get(0).label());
        // null product id falls back to "unknown"
        assertEquals("unknown", response.buckets().get(1).key());
    }

    @Test
    void query_echoesFiltersAndClampsLimit() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("5.00"), 1L, 1L});
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{ProductCategory.GROCERIES, new BigDecimal("5.00"), 1L, 1L}));

        var from = LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0);
        var to = LocalDateTime.of(2026, Month.JUNE, 30, 23, 59);
        var input = QueryFilters.fromRequest(
                from, to,
                List.of(" 12345678000190 ", "  "),
                List.of("12345678"),
                List.of(ProductCategory.GROCERIES),
                List.of(),
                List.of("7891234567890"),
                new BigDecimal("10.00"),
                new BigDecimal("500.00"),
                InsightsGroupBy.CATEGORY,
                9999,
                CategoryView.GLOBAL);

        var response = insightsQueryService.query(user, input);

        var echoed = response.filters();
        assertEquals(from, echoed.from());
        assertEquals(to, echoed.to());
        // trimAll trims and drops the blank entry
        assertEquals(List.of("12345678000190"), echoed.marketCnpjs());
        assertEquals(List.of("12345678"), echoed.marketCnpjRoots());
        assertEquals(List.of(ProductCategory.GROCERIES), echoed.categories());
        // empty productIds list normalizes to null
        assertEquals(null, echoed.productIds());
        assertEquals(List.of("7891234567890"), echoed.eans());
        assertEquals(new BigDecimal("10.00"), echoed.minReceiptTotal());
        assertEquals(new BigDecimal("500.00"), echoed.maxReceiptTotal());
        // limit clamped to MAX_LIMIT = 500
        verify(bucketQuery).setMaxResults(500);
    }

    @Test
    void fromRequest_defaultsNullGroupByToNoneAndNullLimitToDefault() {
        var input = QueryFilters.fromRequest(null, null, null, null, null, null, null, null, null, null, null);
        assertEquals(InsightsGroupBy.NONE, input.groupBy());
        assertEquals(100, input.limit());
    }

    @Test
    void fromRequest_clampsNonPositiveLimitToDefault() {
        var input = QueryFilters.fromRequest(null, null, null, null, null, null, null, null, null, InsightsGroupBy.MONTH, 0);
        assertEquals(100, input.limit());
    }

    @Test
    void query_householdCategoryLens_negativeLimit_doesNotThrow() {
        var arroz = UUID.randomUUID();
        var receiptOne = UUID.randomUUID();
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("20.00"), 1L, 1L});
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{arroz, ProductCategory.GROCERIES, receiptOne, new BigDecimal("20.00"), 1L}));
        when(categoryOverrideService.overrideKeysByProduct(eq(householdId), any()))
                .thenReturn(Map.of());

        // A QueryFilters carrying a negative limit reaches the service via the
        // canonical constructor (which, unlike fromRequest, doesn't clamp). The
        // HOUSEHOLD category lens streams .limit(f.limit()), which threw
        // IllegalArgumentException: -1 until normalize() began clamping the limit.
        var input = new QueryFilters(null, null, null, null, null,
                List.of(ProductCategory.GROCERIES), null, null, null, null,
                InsightsGroupBy.CATEGORY, -1, CategoryView.HOUSEHOLD, MarketScope.ALL, null);

        var response = insightsQueryService.query(user, input);

        assertEquals(1, response.buckets().size());
        assertEquals("GROCERIES", response.buckets().get(0).key());
    }

    @Test
    void query_bindsAllOptionalFilterParameters() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("5.00"), 1L, 1L});

        var input = QueryFilters.fromRequest(
                LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0),
                LocalDateTime.of(2026, Month.JUNE, 1, 0, 0),
                List.of("12345678000190"),
                List.of("12345678"),
                List.of(ProductCategory.BEVERAGES),
                List.of(UUID.randomUUID()),
                List.of("7891234567890"),
                new BigDecimal("1.00"),
                new BigDecimal("2.00"),
                InsightsGroupBy.NONE,
                50);

        insightsQueryService.query(user, input);

        var nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(summaryQuery, times(11)).setParameter(nameCaptor.capture(), any());
        var boundNames = nameCaptor.getAllValues();
        assertTrue(boundNames.containsAll(List.of(
                "householdId", "status", "from", "to",
                "marketCnpjs", "marketCnpjRoots", "categories",
                "productIds", "eans", "minReceiptTotal", "maxReceiptTotal")));
    }

    @Test
    void query_categoryFilter_isSnapshotFirstAndOverrideAware() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("5.00"), 1L, 1L});
        var input = QueryFilters.fromRequest(
                LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0),
                LocalDateTime.of(2026, Month.JUNE, 30, 23, 59),
                null, null, List.of(ProductCategory.GROCERIES), null, null, null, null,
                InsightsGroupBy.NONE, 50, CategoryView.HOUSEHOLD);

        insightsQueryService.query(user, input);

        var jpqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(entityManager, atLeastOnce()).createQuery(jpqlCaptor.capture(), eq(Object[].class));
        var jpql = jpqlCaptor.getAllValues().get(0);
        assertTrue(jpql.contains("COALESCE(ri.categoryAtConfirmation, p.category)"),
                "category filter must be snapshot-first, not live p.category");
        assertTrue(jpql.contains("HouseholdProductCategoryOverride o"),
                "household lens must join the override so effective category is filtered");
    }

    @Test
    void query_bindsOnlyMandatoryParametersWhenNoFilters() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("5.00"), 1L, 1L});

        insightsQueryService.query(user, filtersWith(InsightsGroupBy.NONE));

        // householdId, status, from, to only — no optional filters bound
        verify(summaryQuery, times(4)).setParameter(anyString(), any());
        verify(summaryQuery, never()).setMaxResults(anyInt());
    }

    @Test
    void query_receiptLevelDimension_attachesPerBucketDiscountAndTotal() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("100.00"), 2L, 5L});
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{2026, 4, new BigDecimal("60.00"), 1L, 3L},
                new Object[]{2026, 5, new BigDecimal("40.00"), 1L, 2L}));
        // totalDiscount: DISTINCT (receiptId, discountTotal)
        when(totalDiscountQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{UUID.randomUUID(), new BigDecimal("5.00")},
                new Object[]{UUID.randomUUID(), new BigDecimal("3.00")}));
        // per-bucket discount: DISTINCT (receiptId, year, month, discountTotal)
        when(bucketDiscountQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{UUID.randomUUID(), 2026, 4, new BigDecimal("5.00")},
                new Object[]{UUID.randomUUID(), 2026, 5, new BigDecimal("3.00")}));

        var response = insightsQueryService.query(user, filtersWith(InsightsGroupBy.MONTH));

        assertEquals(0, new BigDecimal("8.00").compareTo(response.summary().totalDiscount()));
        var april = response.buckets().get(0);
        assertEquals("2026-04", april.key());
        assertEquals(0, new BigDecimal("5.00").compareTo(april.discount()));
        var may = response.buckets().get(1);
        assertEquals("2026-05", may.key());
        assertEquals(0, new BigDecimal("3.00").compareTo(may.discount()));
    }

    @Test
    void query_itemLevelDimension_hasNullBucketDiscountButStillReportsTotal() {
        when(summaryQuery.getSingleResult())
                .thenReturn(new Object[]{new BigDecimal("60.00"), 3L, 9L});
        when(bucketQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{ProductCategory.GROCERIES, new BigDecimal("40.00"), 2L, 5L}));
        when(totalDiscountQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{UUID.randomUUID(), new BigDecimal("4.00")}));

        var response = insightsQueryService.query(user,
                filtersWith(InsightsGroupBy.CATEGORY, CategoryView.GLOBAL));

        // period-level discount is still reported...
        assertEquals(0, new BigDecimal("4.00").compareTo(response.summary().totalDiscount()));
        // ...but a receipt-level discount can't be attributed to a category bucket
        assertEquals(null, response.buckets().get(0).discount());
    }
}
