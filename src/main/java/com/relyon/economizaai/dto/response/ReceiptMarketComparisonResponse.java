package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * "Esta compra em outros mercados": what the items of one nota would have cost at up to three
 * nearby markets. Each market compares only the items it has a price for, and {@code paidTotal}
 * is what the household paid for those SAME items — never the whole nota.
 *
 * <p>{@code comparableItemCount} is how many distinct products of the nota could be compared at
 * all (identified product, with a price, not excluded). {@code locationKnown} false means there
 * is no position to measure "nearby" from, so {@code markets} is empty.
 */
public record ReceiptMarketComparisonResponse(
        UUID receiptId,
        int comparableItemCount,
        boolean locationKnown,
        List<Market> markets
) {
    /** {@code difference} = alternativeTotal − paidTotal: negative means it would have been cheaper there. */
    public record Market(
            String cnpj,
            String marketName,
            String friendlyName,
            String chainLogoUrl,
            Double distanceKm,
            int matchedItemCount,
            BigDecimal paidTotal,
            BigDecimal alternativeTotal,
            BigDecimal difference,
            List<Item> items
    ) {}

    public record Item(
            UUID productId,
            String productName,
            BigDecimal quantity,
            BigDecimal paidTotal,
            BigDecimal alternativeUnitPrice,
            BigDecimal alternativeTotal,
            LocalDateTime observedAt
    ) {}
}
