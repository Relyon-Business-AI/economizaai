package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Merchant B2B data product: notable price moves by OTHER stores in the
 * merchant's region, for products the merchant sells.
 *
 * <p><strong>Antitrust-safe by construction:</strong> this feed is ANONYMIZED. It
 * NEVER names the competing store, its CNPJ or its live price — only the product,
 * the direction of the move, its magnitude in %, and an approximate recency. Each
 * move is backed by at least K=3 distinct households (fail-closed), so it can
 * never surface one competitor's individual price point.
 */
public record MerchantCompetitorMovesResponse(int lookbackDays, int recentWindowDays,
                                              List<CompetitorMove> moves) {

    public record CompetitorMove(
            UUID productId,
            String productName,
            String state,
            /** DOWN (recent regional median fell) or UP (it rose) vs. the baseline. */
            String direction,
            /** Absolute size of the move, %, one decimal. Never an absolute price. */
            BigDecimal magnitudePercent,
            /** Days since the most recent other-store observation (day-truncated). */
            int approxDaysAgo) {}
}
