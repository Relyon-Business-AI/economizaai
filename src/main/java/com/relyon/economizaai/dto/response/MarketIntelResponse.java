package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Market intelligence for the admin: the collaborative price-index size, the
 * most-scanned products and markets, where the money goes by category, and the
 * geographic spread by UF. All aggregated across every household (the shared
 * asset), from confirmed non-excluded data. Spend is BRL.
 */
public record MarketIntelResponse(
        long priceObservations,
        long contributingHouseholds,
        List<TopProduct> topProducts,
        /** Most-scanned markets, one row per CNPJ (per store unit). */
        List<TopMarket> topMarkets,
        /** Same, but grouped by chain (marketName) — unifies multi-unit chains like Zaffari. */
        List<TopMarket> topMarketsByChain,
        List<CategorySpend> categorySpend,
        List<UfRow> byUf) {

    public record TopProduct(String productId, String name, long scans) {
    }

    public record TopMarket(String cnpj, String name, long scans, BigDecimal spend) {
    }

    /** Category name (or "UNCATEGORIZED" when the confirmation snapshot had none). */
    public record CategorySpend(String category, BigDecimal spend, long items) {
    }

    public record UfRow(String uf, long receipts, BigDecimal spend) {
    }
}
