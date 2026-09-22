package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Where a product can be found and at what price, for the "onde está mais
 * barato" screen. One row per market.
 *
 * <p><strong>Privacy:</strong> {@code priceType} distinguishes the source.
 * {@code OWN_LAST} is the household's own most-recent paid price at a market it
 * shopped at (own data — exact). {@code COMMUNITY_MEDIAN} is the k-anonymity
 * guarded median from the collaborative index (only shown when ≥ K distinct
 * households contributed — see {@code economizaai.collaborative.min-households-for-public}).
 * A single sub-K community price is never exposed.
 */
public record ProductMarketPriceResponse(
        String cnpj,
        String cnpjRoot,
        String marketName,
        String friendlyName,
        BigDecimal price,
        PriceType priceType,
        BigDecimal communityMinPrice,
        Integer sampleCount,
        Long distinctHouseholds,
        Double distanceKm,
        boolean watched,
        boolean visited,
        LocalDateTime observedAt
) {
    public enum PriceType { OWN_LAST, COMMUNITY_MEDIAN }
}
