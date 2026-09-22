package com.relyon.economizaai.model.enums;

/**
 * Dimensions a flexible insights query can group spend by.
 * NONE means "no grouping — return only the summary aggregate".
 *
 * <p>One groupBy at a time: the FE makes one request per dimension. Cross-tab
 * (groupBy=month×market) is intentionally out of scope — call twice with
 * different filters or groupBys instead.
 */
public enum InsightsGroupBy {
    NONE,
    DAY,
    WEEK,
    MONTH,
    YEAR,
    MARKET,        // groups by full CNPJ (one row per individual store)
    CHAIN,         // groups by CNPJ root (8 digits — one row per chain across all stores)
    CATEGORY,
    PRODUCT
}
