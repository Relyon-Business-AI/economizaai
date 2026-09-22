package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One row of the "Ofertas pra você" screen: a product the household buys,
 * currently observed cheaper at a relevant market than the household last paid,
 * by enough to clear the progressive relevance threshold.
 *
 * <p>The community price is always k-anonymity guarded (median disclosed only
 * when ≥ K distinct households contributed). The FE renders the list from these
 * rows and fires {@code DEAL_VIEWED} / {@code DEAL_TAPPED} telemetry keyed by
 * {@code productId} + {@code marketCnpj} via {@code POST /notifications/events}.
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
        LocalDateTime observedAt
) {}
