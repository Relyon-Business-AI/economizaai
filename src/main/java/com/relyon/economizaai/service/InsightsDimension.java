package com.relyon.economizaai.service;

import com.relyon.economizaai.dto.response.InsightsQueryResponse.Bucket;
import com.relyon.economizaai.model.enums.InsightsGroupBy;
import com.relyon.economizaai.model.enums.ProductCategory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Maps an {@link InsightsGroupBy} to JPQL fragments + row→bucket conversion
 * for {@link InsightsQueryService}. Encapsulating each dimension here keeps
 * the query service focused on assembly, not per-dimension shape.
 */
enum InsightsDimension {
    DAY(
            "EXTRACT(YEAR FROM r.issuedAt), EXTRACT(MONTH FROM r.issuedAt), EXTRACT(DAY FROM r.issuedAt)",
            "EXTRACT(YEAR FROM r.issuedAt), EXTRACT(MONTH FROM r.issuedAt), EXTRACT(DAY FROM r.issuedAt)",
            "EXTRACT(YEAR FROM r.issuedAt) ASC, EXTRACT(MONTH FROM r.issuedAt) ASC, EXTRACT(DAY FROM r.issuedAt) ASC") {
        @Override Bucket toBucket(Object[] row) {
            var key = "%04d-%02d-%02d".formatted(intAt(row, 0), intAt(row, 1), intAt(row, 2));
            return bucketOf(key, key, row, 3);
        }
        @Override String discountKey(Object[] row, int offset) {
            return "%04d-%02d-%02d".formatted(intAt(row, offset), intAt(row, offset + 1), intAt(row, offset + 2));
        }
    },
    WEEK(
            "EXTRACT(YEAR FROM r.issuedAt), EXTRACT(WEEK FROM r.issuedAt)",
            "EXTRACT(YEAR FROM r.issuedAt), EXTRACT(WEEK FROM r.issuedAt)",
            "EXTRACT(YEAR FROM r.issuedAt) ASC, EXTRACT(WEEK FROM r.issuedAt) ASC") {
        @Override Bucket toBucket(Object[] row) {
            var key = "%04d-W%02d".formatted(intAt(row, 0), intAt(row, 1));
            return bucketOf(key, key, row, 2);
        }
        @Override String discountKey(Object[] row, int offset) {
            return "%04d-W%02d".formatted(intAt(row, offset), intAt(row, offset + 1));
        }
    },
    MONTH(
            "EXTRACT(YEAR FROM r.issuedAt), EXTRACT(MONTH FROM r.issuedAt)",
            "EXTRACT(YEAR FROM r.issuedAt), EXTRACT(MONTH FROM r.issuedAt)",
            "EXTRACT(YEAR FROM r.issuedAt) ASC, EXTRACT(MONTH FROM r.issuedAt) ASC") {
        @Override Bucket toBucket(Object[] row) {
            var key = "%04d-%02d".formatted(intAt(row, 0), intAt(row, 1));
            return bucketOf(key, key, row, 2);
        }
        @Override String discountKey(Object[] row, int offset) {
            return "%04d-%02d".formatted(intAt(row, offset), intAt(row, offset + 1));
        }
    },
    YEAR(
            "EXTRACT(YEAR FROM r.issuedAt)",
            "EXTRACT(YEAR FROM r.issuedAt)",
            "EXTRACT(YEAR FROM r.issuedAt) ASC") {
        @Override Bucket toBucket(Object[] row) {
            var key = String.valueOf(intAt(row, 0));
            return bucketOf(key, key, row, 1);
        }
        @Override String discountKey(Object[] row, int offset) {
            return String.valueOf(intAt(row, offset));
        }
    },
    MARKET(
            "r.cnpjEmitente, MAX(r.marketName)",
            "r.cnpjEmitente",
            InsightsDimension.ORDER_BY_TOTAL_DESC) {
        @Override Bucket toBucket(Object[] row) {
            var cnpj = (String) row[0];
            var marketName = (String) row[1];
            return bucketOf(cnpj, marketName != null ? marketName : cnpj, row, 2);
        }
        @Override String discountKey(Object[] row, int offset) {
            return (String) row[offset];
        }
    },
    CHAIN(
            "SUBSTRING(r.cnpjEmitente, 1, 8), MAX(r.marketName)",
            "SUBSTRING(r.cnpjEmitente, 1, 8)",
            InsightsDimension.ORDER_BY_TOTAL_DESC) {
        @Override Bucket toBucket(Object[] row) {
            var cnpjRoot = (String) row[0];
            var sampleName = (String) row[1];
            return bucketOf(cnpjRoot, sampleName != null ? sampleName : cnpjRoot, row, 2);
        }
        @Override String discountKey(Object[] row, int offset) {
            return (String) row[offset];
        }
    },
    CATEGORY(
            "p.category",
            "p.category",
            InsightsDimension.ORDER_BY_TOTAL_DESC) {
        @Override Bucket toBucket(Object[] row) {
            var category = row[0] == null ? ProductCategory.OTHER : (ProductCategory) row[0];
            return bucketOf(category.name(), category.name(), row, 1);
        }
        @Override boolean attributesDiscount() { return false; }
    },
    PRODUCT(
            "p.id, MAX(p.normalizedName)",
            "p.id",
            InsightsDimension.ORDER_BY_TOTAL_DESC) {
        @Override Bucket toBucket(Object[] row) {
            var productId = row[0] == null ? null : ((UUID) row[0]).toString();
            var name = (String) row[1];
            var label = name != null ? name : productId;
            return bucketOf(productId != null ? productId : "unknown", label, row, 2);
        }
        @Override boolean attributesDiscount() { return false; }
    };

    private static final String ORDER_BY_TOTAL_DESC = "SUM(ri.totalPrice) DESC";

    private final String selectKeys;
    private final String groupByKeys;
    private final String orderBy;

    InsightsDimension(String selectKeys, String groupByKeys, String orderBy) {
        this.selectKeys = selectKeys;
        this.groupByKeys = groupByKeys;
        this.orderBy = orderBy;
    }

    String selectKeys() { return selectKeys; }
    String groupByKeys() { return groupByKeys; }
    String orderBy() { return orderBy; }

    abstract Bucket toBucket(Object[] row);

    /**
     * Whether a receipt-level discount can be attributed to this dimension's
     * buckets. True when each receipt falls wholly in one bucket (time /
     * market / chain); false for item-level groupings (category / product).
     */
    boolean attributesDiscount() { return true; }

    /**
     * Builds the bucket key for a discount row whose grouping columns start at
     * {@code offset} (mirrors the key in {@link #toBucket}). Only called for
     * dimensions where {@link #attributesDiscount()} is true.
     */
    String discountKey(Object[] row, int offset) { return null; }

    static InsightsDimension forGroupBy(InsightsGroupBy groupBy) {
        return switch (groupBy) {
            case DAY -> DAY;
            case WEEK -> WEEK;
            case MONTH -> MONTH;
            case YEAR -> YEAR;
            case MARKET -> MARKET;
            case CHAIN -> CHAIN;
            case CATEGORY -> CATEGORY;
            case PRODUCT -> PRODUCT;
            case NONE -> throw new IllegalStateException("NONE handled by caller");
        };
    }

    /** Shared "total / receipts" math used by the query service and accumulators. */
    static BigDecimal averageTicket(BigDecimal total, long receiptCount) {
        return receiptCount == 0
                ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(receiptCount), 2, RoundingMode.HALF_UP);
    }

    protected static Bucket bucketOf(String key, String label, Object[] row, int aggregateOffset) {
        var total = (BigDecimal) row[aggregateOffset];
        var receiptCount = ((Number) row[aggregateOffset + 1]).longValue();
        var itemCount = ((Number) row[aggregateOffset + 2]).longValue();
        // discount is filled in later (per-bucket) only for receipt-level dimensions.
        return new Bucket(key, label, total, null, receiptCount, itemCount,
                averageTicket(total, receiptCount));
    }

    protected static int intAt(Object[] row, int idx) {
        return ((Number) row[idx]).intValue();
    }
}
