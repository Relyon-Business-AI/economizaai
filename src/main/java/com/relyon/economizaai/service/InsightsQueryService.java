package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.response.InsightsQueryResponse;
import com.relyon.economizaai.dto.response.InsightsQueryResponse.Bucket;
import com.relyon.economizaai.dto.response.InsightsQueryResponse.Filters;
import com.relyon.economizaai.dto.response.InsightsQueryResponse.Summary;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.CategoryView;
import com.relyon.economizaai.model.enums.InsightsGroupBy;
import com.relyon.economizaai.model.enums.MarketScope;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.model.enums.ReceiptStatus;
import com.relyon.economizaai.repository.InsightsRepository;
import com.relyon.economizaai.service.geo.MerchantSupportGate;
import com.relyon.economizaai.service.subscription.SubscriptionGateService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Flexible spend-analytics query service.
 *
 * <p>One endpoint, many slices: combine date / market / category / product /
 * EAN / receipt-total filters with any single {@link InsightsGroupBy}
 * dimension. Filters that accept multiple values (categories, marketCnpjs,
 * productIds, eans, marketCnpjRoots) take a list — every list-typed filter
 * is OR'd internally and AND'd with the others.
 *
 * <p>Single responsibility: assemble + run the dynamic JPQL and shape it into
 * the response. The fixed-shape {@link InsightsService} stays untouched for
 * backwards compatibility with the existing dashboards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightsQueryService {

    private static final LocalDateTime EPOCH_FLOOR = LocalDateTime.of(1900, Month.JANUARY, 1, 0, 0);
    private static final LocalDateTime EPOCH_CEIL = LocalDateTime.of(2999, Month.DECEMBER, 31, 23, 59, 59);
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final HouseholdProductCategoryOverrideService categoryOverrideService;
    private final SubscriptionGateService subscriptionGate;
    private final InsightsRepository insightsRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public InsightsQueryResponse query(User user, QueryFilters input) {
        var clampedInput = clampToAllowedHistory(user, input);
        var filters = QueryFilters.normalize(clampedInput, user.getHousehold().getId());
        // Scope by merchant segment (same lens as /items and the other insights endpoints): resolve
        // the household's supported CNPJs once and bind them into the filters for the clause builder.
        if (filters.scope() != MarketScope.ALL) {
            filters = filters.withSupportedCnpjs(
                    insightsRepository.supportedCnpjs(filters.householdId(), MerchantSupportGate.supportedSegments()));
        }
        var groupBy = filters.groupBy();
        var summary = computeSummary(filters);
        List<Bucket> buckets;
        if (groupBy == InsightsGroupBy.NONE) {
            buckets = List.of();
        } else if (groupBy == InsightsGroupBy.CATEGORY && filters.categoryView() == CategoryView.HOUSEHOLD) {
            buckets = computeHouseholdCategoryBuckets(filters);
        } else {
            buckets = computeBuckets(filters);
        }

        log.info("insights.query household={} groupBy={} buckets={} total={} receipts={}",
                filters.householdId(), groupBy, buckets.size(),
                summary.total(), summary.receiptCount());

        return new InsightsQueryResponse(
                new Filters(
                        clampedInput.from(), clampedInput.to(),
                        filters.marketCnpjs(), filters.marketCnpjRoots(),
                        filters.categories(), filters.productIds(), filters.eans(),
                        filters.minReceiptTotal(), filters.maxReceiptTotal()),
                summary,
                groupBy,
                buckets);
    }

    /**
     * FREE plan only sees a limited history window; clamp the requested lower
     * bound ({@code from}) up to that window. Returns the input unchanged for PRO
     * (or when already within the window). The echoed Filters reflect the clamped
     * value so the client sees the window that was actually applied.
     */
    private QueryFilters clampToAllowedHistory(User user, QueryFilters input) {
        var clampedFrom = subscriptionGate.clampFrom(user, input.from());
        return new QueryFilters(input.householdId(), clampedFrom, input.to(),
                input.marketCnpjs(), input.marketCnpjRoots(), input.categories(),
                input.productIds(), input.eans(), input.minReceiptTotal(),
                input.maxReceiptTotal(), input.groupBy(), input.limit(), input.categoryView(),
                input.scope(), input.supportedCnpjs());
    }

    private Summary computeSummary(QueryFilters filters) {
        var clauses = buildClauses(filters);
        var jpql = """
                SELECT COALESCE(SUM(ri.totalPrice), 0),
                       COUNT(DISTINCT r.id),
                       COUNT(ri)
                FROM ReceiptItem ri
                JOIN ri.receipt r
                LEFT JOIN ri.product p%s
                WHERE %s
                """.formatted(clauses.join(), clauses.where());
        var query = entityManager.createQuery(jpql, Object[].class);
        clauses.bind(query);
        var row = query.getSingleResult();
        var total = (BigDecimal) row[0];
        var receiptCount = ((Number) row[1]).longValue();
        var itemCount = ((Number) row[2]).longValue();
        var totalDiscount = computeTotalDiscount(filters);
        return new Summary(total, totalDiscount, receiptCount, itemCount, InsightsDimension.averageTicket(total, receiptCount));
    }

    /**
     * Receipt-level discount across the filtered slice. We select DISTINCT
     * (receipt id, discountTotal) over the item-join so each receipt's discount
     * is counted exactly once (the join would otherwise multiply it by the line
     * count), then sum in code. Item prices stay gross; this is reported aside.
     */
    private BigDecimal computeTotalDiscount(QueryFilters filters) {
        var clauses = buildClauses(filters);
        var jpql = """
                SELECT DISTINCT r.id, r.discountTotal
                FROM ReceiptItem ri
                JOIN ri.receipt r
                LEFT JOIN ri.product p%s
                WHERE %s
                """.formatted(clauses.join(), clauses.where());
        var query = entityManager.createQuery(jpql, Object[].class);
        clauses.bind(query);
        var sum = BigDecimal.ZERO;
        for (var row : query.getResultList()) {
            if (row[1] != null) sum = sum.add((BigDecimal) row[1]);
        }
        return sum;
    }

    /**
     * Per-bucket discount for receipt-level dimensions only. Same DISTINCT-receipt
     * technique as {@link #computeTotalDiscount}, keyed by the dimension's bucket
     * key. Empty for category/product (a receipt's discount can't be attributed to
     * a single category/product bucket without distribution).
     */
    private Map<String, BigDecimal> bucketDiscounts(QueryFilters filters, InsightsDimension dimension) {
        if (!dimension.attributesDiscount()) return Map.of();
        var clauses = buildClauses(filters);
        var jpql = """
                SELECT DISTINCT r.id, %s, r.discountTotal
                FROM ReceiptItem ri
                JOIN ri.receipt r
                LEFT JOIN ri.product p%s
                WHERE %s
                """.formatted(dimension.groupByKeys(), clauses.join(), clauses.where());
        var query = entityManager.createQuery(jpql, Object[].class);
        clauses.bind(query);
        var discounts = new LinkedHashMap<String, BigDecimal>();
        for (var row : query.getResultList()) {
            var key = dimension.discountKey(row, 1);
            var discount = row[row.length - 1] == null ? BigDecimal.ZERO : (BigDecimal) row[row.length - 1];
            discounts.merge(key, discount, BigDecimal::add);
        }
        return discounts;
    }

    private List<Bucket> computeBuckets(QueryFilters filters) {
        var dimension = InsightsDimension.forGroupBy(filters.groupBy());
        var clauses = buildClauses(filters);
        var jpql = """
                SELECT %s,
                       COALESCE(SUM(ri.totalPrice), 0),
                       COUNT(DISTINCT r.id),
                       COUNT(ri)
                FROM ReceiptItem ri
                JOIN ri.receipt r
                LEFT JOIN ri.product p%s
                WHERE %s
                GROUP BY %s
                ORDER BY %s
                """.formatted(dimension.selectKeys(), clauses.join(), clauses.where(),
                dimension.groupByKeys(), dimension.orderBy());
        var query = entityManager.createQuery(jpql, Object[].class);
        clauses.bind(query);
        query.setMaxResults(filters.limit());

        var discounts = bucketDiscounts(filters, dimension);
        return query.getResultList().stream()
                .map(dimension::toBucket)
                .map(bucket -> dimension.attributesDiscount()
                        ? bucket.withDiscount(discounts.getOrDefault(bucket.key(), BigDecimal.ZERO))
                        : bucket)
                .toList();
    }

    /**
     * HOUSEHOLD-lens CATEGORY buckets. SQL grouping by {@code p.category} would
     * double-count products moved by an override, so instead we group at
     * {@code (productId, category, receiptId)} granularity and re-aggregate in
     * code by the household's EFFECTIVE category. Grouping by receipt id too lets
     * us keep {@code receiptCount} as a correct DISTINCT count per effective
     * category (a receipt touching two products in the same bucket counts once).
     */
    private List<Bucket> computeHouseholdCategoryBuckets(QueryFilters filters) {
        var clauses = buildClauses(filters);
        var jpql = """
                SELECT p.id, COALESCE(ri.categoryAtConfirmation, p.category), r.id,
                       COALESCE(SUM(ri.totalPrice), 0),
                       COUNT(ri)
                FROM ReceiptItem ri
                JOIN ri.receipt r
                LEFT JOIN ri.product p%s
                WHERE %s
                GROUP BY p.id, COALESCE(ri.categoryAtConfirmation, p.category), r.id
                """.formatted(clauses.join(), clauses.where());
        var query = entityManager.createQuery(jpql, Object[].class);
        clauses.bind(query);
        var rows = query.getResultList();

        var productIds = rows.stream()
                .map(row -> (UUID) row[0])
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        var overrides = categoryOverrideService.overrideKeysByProduct(filters.householdId(), productIds);

        var accumulators = new LinkedHashMap<String, CategoryAccumulator>();
        for (var row : rows) {
            var productId = (UUID) row[0];
            var globalCategory = (ProductCategory) row[1];
            var receiptId = (UUID) row[2];
            var total = (BigDecimal) row[3];
            var itemCount = ((Number) row[4]).longValue();
            var override = productId != null ? overrides.get(productId) : null;
            var categoryName = globalCategory != null ? globalCategory.name() : ProductCategory.OTHER.name();
            var label = override != null ? override.label() : categoryName;
            // Discriminated bucket key so a custom category named like an enum doesn't merge.
            var bucketKey = override != null ? override.key() : "enum:" + categoryName;
            accumulators.computeIfAbsent(bucketKey, key -> new CategoryAccumulator(label))
                    .add(total, itemCount, receiptId);
        }
        return accumulators.values().stream()
                .sorted(Comparator.comparing(CategoryAccumulator::total).reversed())
                .limit(filters.limit())
                .map(CategoryAccumulator::toBucket)
                .toList();
    }

    private static final class CategoryAccumulator {
        private final String label;
        private BigDecimal total = BigDecimal.ZERO;
        private long itemCount = 0;
        private final Set<UUID> receiptIds = new HashSet<>();

        CategoryAccumulator(String label) {
            this.label = label;
        }

        void add(BigDecimal amount, long items, UUID receiptId) {
            total = total.add(amount);
            itemCount += items;
            if (receiptId != null) receiptIds.add(receiptId);
        }

        BigDecimal total() {
            return total;
        }

        Bucket toBucket() {
            var receiptCount = receiptIds.size();
            // CATEGORY (household lens) is item-level: no per-bucket discount.
            return new Bucket(label, label, total, null, receiptCount, itemCount, InsightsDimension.averageTicket(total, receiptCount));
        }
    }

    /**
     * Assembles the WHERE clause + parameter bindings dynamically. Each
     * optional filter is added ONLY when its value is non-null — Hibernate
     * can't infer the right type when the same parameter would have to
     * satisfy both a scalar {@code IS NULL} check AND a collection
     * {@code IN} clause, which is what a single static query template
     * would require.
     */
    private static FilterClauses buildClauses(QueryFilters filters) {
        var clauses = new ArrayList<String>();
        var bindings = new LinkedHashMap<String, Object>();
        var join = "";

        clauses.add("r.household.id = :householdId");
        bindings.put("householdId", filters.householdId());

        clauses.add("r.status = :status");
        bindings.put("status", ReceiptStatus.CONFIRMED);

        // Market-segment scope. supportedCnpjs is resolved in query() (null for ALL). SUPPORTED with
        // none matches nothing (summary/buckets can't early-return like a page can); JPQL rejects IN ().
        if (filters.scope() == MarketScope.SUPPORTED) {
            var supported = filters.supportedCnpjs();
            if (supported == null || supported.isEmpty()) {
                clauses.add("r.id IS NULL");
            } else {
                clauses.add("r.cnpjEmitente IN (:scopeCnpjs)");
                bindings.put("scopeCnpjs", supported);
            }
        } else if (filters.scope() == MarketScope.OTHER
                && filters.supportedCnpjs() != null && !filters.supportedCnpjs().isEmpty()) {
            clauses.add("(r.cnpjEmitente IS NULL OR r.cnpjEmitente NOT IN (:scopeCnpjs))");
            bindings.put("scopeCnpjs", filters.supportedCnpjs());
        }

        clauses.add("ri.excluded = false");
        clauses.add("ri.excludedFromPersonal = false");

        clauses.add("r.issuedAt >= :from");
        bindings.put("from", filters.from());
        clauses.add("r.issuedAt <= :to");
        bindings.put("to", filters.to());

        if (filters.marketCnpjs() != null) {
            clauses.add("r.cnpjEmitente IN (:marketCnpjs)");
            bindings.put("marketCnpjs", filters.marketCnpjs());
        }
        if (filters.marketCnpjRoots() != null) {
            clauses.add("SUBSTRING(r.cnpjEmitente, 1, 8) IN (:marketCnpjRoots)");
            bindings.put("marketCnpjRoots", filters.marketCnpjRoots());
        }
        if (filters.categories() != null) {
            // Snapshot-first (categoryAtConfirmation → live p.category) so the filter
            // matches the snapshot-first aggregation and the /items endpoint. OTHER
            // also matches null-category rows (unmatched/uncategorized show as OTHER).
            var includeUnmatched = filters.categories().contains(ProductCategory.OTHER);
            var enumMatch = includeUnmatched
                    ? "(COALESCE(ri.categoryAtConfirmation, p.category) IS NULL"
                            + " OR COALESCE(ri.categoryAtConfirmation, p.category) IN (:categories))"
                    : "COALESCE(ri.categoryAtConfirmation, p.category) IN (:categories)";
            if (filters.categoryView() == CategoryView.HOUSEHOLD) {
                // Household lens: filter by EFFECTIVE category (override wins), mirroring
                // ItemQueryService — a row matches with no override + matching enum, or an
                // override targeting a listed enum.
                join = " LEFT JOIN HouseholdProductCategoryOverride o"
                        + " ON o.product = p AND o.household.id = :householdId";
                clauses.add("((o.id IS NULL AND " + enumMatch + ") OR o.category IN (:categories))");
            } else {
                clauses.add(enumMatch);
            }
            bindings.put("categories", filters.categories());
        }
        if (filters.productIds() != null) {
            clauses.add("p.id IN (:productIds)");
            bindings.put("productIds", filters.productIds());
        }
        if (filters.eans() != null) {
            clauses.add("ri.ean IN (:eans)");
            bindings.put("eans", filters.eans());
        }
        if (filters.minReceiptTotal() != null) {
            clauses.add("r.totalAmount >= :minReceiptTotal");
            bindings.put("minReceiptTotal", filters.minReceiptTotal());
        }
        if (filters.maxReceiptTotal() != null) {
            clauses.add("r.totalAmount <= :maxReceiptTotal");
            bindings.put("maxReceiptTotal", filters.maxReceiptTotal());
        }
        return new FilterClauses(String.join(" AND ", clauses), join, bindings);
    }

    private record FilterClauses(String where, String join, Map<String, Object> bindings) {
        void bind(Query query) {
            bindings.forEach(query::setParameter);
        }
    }

    /**
     * Raw input from the controller. Lists/ranges may be null or empty;
     * {@link #normalize} turns empties into nulls so the {@code IS NULL OR IN}
     * clauses behave correctly.
     */
    public record QueryFilters(
            UUID householdId,
            LocalDateTime from,
            LocalDateTime to,
            List<String> marketCnpjs,
            List<String> marketCnpjRoots,
            List<ProductCategory> categories,
            List<UUID> productIds,
            List<String> eans,
            BigDecimal minReceiptTotal,
            BigDecimal maxReceiptTotal,
            InsightsGroupBy groupBy,
            int limit,
            CategoryView categoryView,
            MarketScope scope,
            // Resolved by the service (not from the request): the household's supported-segment
            // CNPJs, used by the scope clause. Null/empty until resolved in query().
            List<String> supportedCnpjs
    ) {
        /** Backwards-compatible builder — defaults the lens to HOUSEHOLD, scope to ALL. */
        public static QueryFilters fromRequest(LocalDateTime from, LocalDateTime to,
                                               List<String> marketCnpjs,
                                               List<String> marketCnpjRoots,
                                               List<ProductCategory> categories,
                                               List<UUID> productIds,
                                               List<String> eans,
                                               BigDecimal minReceiptTotal,
                                               BigDecimal maxReceiptTotal,
                                               InsightsGroupBy groupBy,
                                               Integer limit) {
            return fromRequest(from, to, marketCnpjs, marketCnpjRoots, categories, productIds, eans,
                    minReceiptTotal, maxReceiptTotal, groupBy, limit, CategoryView.HOUSEHOLD);
        }

        public static QueryFilters fromRequest(LocalDateTime from, LocalDateTime to,
                                               List<String> marketCnpjs,
                                               List<String> marketCnpjRoots,
                                               List<ProductCategory> categories,
                                               List<UUID> productIds,
                                               List<String> eans,
                                               BigDecimal minReceiptTotal,
                                               BigDecimal maxReceiptTotal,
                                               InsightsGroupBy groupBy,
                                               Integer limit,
                                               CategoryView categoryView) {
            return fromRequest(from, to, marketCnpjs, marketCnpjRoots, categories, productIds, eans,
                    minReceiptTotal, maxReceiptTotal, groupBy, limit, categoryView, MarketScope.ALL);
        }

        /** Builder for the controller — householdId is filled in by the service. */
        public static QueryFilters fromRequest(LocalDateTime from, LocalDateTime to,
                                               List<String> marketCnpjs,
                                               List<String> marketCnpjRoots,
                                               List<ProductCategory> categories,
                                               List<UUID> productIds,
                                               List<String> eans,
                                               BigDecimal minReceiptTotal,
                                               BigDecimal maxReceiptTotal,
                                               InsightsGroupBy groupBy,
                                               Integer limit,
                                               CategoryView categoryView,
                                               MarketScope scope) {
            return new QueryFilters(null, from, to,
                    marketCnpjs, marketCnpjRoots, categories, productIds, eans,
                    minReceiptTotal, maxReceiptTotal,
                    groupBy != null ? groupBy : InsightsGroupBy.NONE,
                    clampLimit(limit),
                    categoryView != null ? categoryView : CategoryView.HOUSEHOLD,
                    scope != null ? scope : MarketScope.ALL,
                    null);
        }

        static QueryFilters normalize(QueryFilters filters, UUID householdId) {
            return new QueryFilters(
                    householdId,
                    filters.from() != null ? filters.from() : EPOCH_FLOOR,
                    filters.to() != null ? filters.to() : EPOCH_CEIL,
                    nullIfEmpty(trimAll(filters.marketCnpjs())),
                    nullIfEmpty(trimAll(filters.marketCnpjRoots())),
                    nullIfEmpty(filters.categories()),
                    nullIfEmpty(filters.productIds()),
                    nullIfEmpty(trimAll(filters.eans())),
                    filters.minReceiptTotal(),
                    filters.maxReceiptTotal(),
                    filters.groupBy(),
                    clampLimit(filters.limit()),
                    filters.categoryView() != null ? filters.categoryView() : CategoryView.HOUSEHOLD,
                    filters.scope() != null ? filters.scope() : MarketScope.ALL,
                    filters.supportedCnpjs()
            );
        }

        /** Copy with the service-resolved supported-segment CNPJs bound in. */
        QueryFilters withSupportedCnpjs(List<String> resolved) {
            return new QueryFilters(householdId, from, to, marketCnpjs, marketCnpjRoots, categories,
                    productIds, eans, minReceiptTotal, maxReceiptTotal, groupBy, limit, categoryView,
                    scope, resolved);
        }

        private static <T> List<T> nullIfEmpty(List<T> list) {
            return list == null || list.isEmpty() ? null : list;
        }

        private static List<String> trimAll(List<String> list) {
            if (list == null) return null;
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .toList();
        }

        private static int clampLimit(Integer limit) {
            if (limit == null || limit <= 0) return DEFAULT_LIMIT;
            return Math.min(limit, MAX_LIMIT);
        }
    }

}
