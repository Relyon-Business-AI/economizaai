package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.DealPriceSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of the "Ofertas pra você" screen: a product the household buys,
 * currently observed cheaper at a relevant market chain than the household last
 * paid, by enough to clear the progressive relevance threshold.
 *
 * <p>{@code currentPrice} is the chain's MOST RECENT observed price (at most
 * {@code deals-max-price-age-days} old) — the same number the best-markets screen
 * shows for that chain — and {@code observedAt} is the day of that observation.
 * {@code priceSource} says whether that observation is the household's own
 * purchase or someone else's. The chain where the household last bought the
 * product is never suggested. K-anonymity is applied by tier inside the price
 * index (grocery n=1; health products and non-grocery stores K=3). The FE
 * renders the list from these rows and fires {@code DEAL_VIEWED} /
 * {@code DEAL_TAPPED} telemetry keyed by {@code productId} + {@code marketCnpj}
 * via {@code POST /notifications/events}.
 */
public record DealResponse(
        UUID productId,
        String productName,
        String friendlyDescription,
        String category,
        String marketCnpj,
        String marketName,
        BigDecimal currentPrice,
        BigDecimal lastPaidPrice,
        BigDecimal savingsAmount,
        BigDecimal savingsPct,
        BigDecimal discountFraction,
        long distinctHouseholds,
        Double distanceKm,
        boolean isWatched,
        LocalDateTime observedAt,
        DealPriceSource priceSource
) {
    /** Pre-{@code priceSource} shape: a deal with no stated origin is community evidence. */
    public DealResponse(UUID productId, String productName, String friendlyDescription, String category,
                        String marketCnpj, String marketName, BigDecimal currentPrice, BigDecimal lastPaidPrice,
                        BigDecimal savingsAmount, BigDecimal savingsPct, BigDecimal discountFraction,
                        long distinctHouseholds, Double distanceKm, boolean isWatched, LocalDateTime observedAt) {
        this(productId, productName, friendlyDescription, category, marketCnpj, marketName, currentPrice,
                lastPaidPrice, savingsAmount, savingsPct, discountFraction, distinctHouseholds, distanceKm,
                isWatched, observedAt, DealPriceSource.COMMUNITY);
    }
}
