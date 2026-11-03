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

    /**
     * Spend bucketed by the NEW 2-level taxonomy: one row per GROUP (Carnes,
     * Mercearia, Combustível…), with the per-LEAF breakdown in {@code children}
     * (expansível no dashboard). Items confirmed before the taxonomy fall back
     * to the legacy enum's default group; no signal at all → "Sem categoria".
     */
    public record CategorySpend(String slug, String name, BigDecimal spend, long items,
                                List<CategorySpend> children) {
    }

    public record UfRow(String uf, long receipts, BigDecimal spend) {
    }
}
