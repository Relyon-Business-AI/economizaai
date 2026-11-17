package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * How much the household saved in a period, line by line: the price paid for each product
 * against its reference price (what other households paid nearby around the same day, or, when
 * there is not enough of that, what the household itself usually paid).
 *
 * <p>{@code saved} adds only the lines bought BELOW the reference; {@code couldHaveSaved} adds,
 * separately, what was paid ABOVE it. Lines without a reference are in neither —
 * {@code comparedLineCount} out of {@code purchasedLineCount} says how much of the period the
 * numbers cover; {@code savedLineCount} and {@code paidAboveLineCount} say how many of the
 * compared lines fell on each side. No per-line reference price is exposed.
 */
public record HouseholdSavingsResponse(
        LocalDateTime from,
        LocalDateTime to,
        BigDecimal saved,
        BigDecimal couldHaveSaved,
        BigDecimal comparedSpend,
        int purchasedLineCount,
        int comparedLineCount,
        int regionComparedLineCount,
        int householdComparedLineCount,
        int savedLineCount,
        int paidAboveLineCount,
        List<MarketSavings> markets
) {
    /** The same numbers for one market, biggest {@code saved} first in the list. */
    public record MarketSavings(
            String cnpj,
            String marketName,
            String marketFriendlyName,
            BigDecimal saved,
            BigDecimal couldHaveSaved,
            BigDecimal comparedSpend,
            int comparedLineCount
    ) {}
}
