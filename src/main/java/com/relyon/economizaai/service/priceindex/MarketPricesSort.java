package com.relyon.economizaai.service.priceindex;

/** Ordering of the market screen's price list ({@code GET /markets/{cnpj}/prices}). */
public enum MarketPricesSort {
    /** Newest observation first (default). */
    RECENT,
    /** Lowest latest price first. */
    CHEAPEST,
    /** Product name A→Z. */
    NAME
}
