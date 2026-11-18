package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * "Esta compra em outros mercados": what the items of one nota would have cost at up to three
 * nearby markets. Each market compares only the items it has a price for, and {@code paidTotal}
 * is what the household paid for those SAME items — never the whole nota.
 *
 * <p>The other markets' prices are a snapshot taken when the nota was confirmed
 * ({@code computedAt}); they do not change when the nota is opened again. {@code available} is
 * false when there is no comparison for this nota and there will not be one: it is not confirmed,
 * or it was bought too long ago for today's prices to say anything about it.
 *
 * <p>{@code comparableItemCount} is how many distinct products of the nota can be compared at
 * all (identified product, with a price, not excluded). {@code locationKnown} false means there
 * is no position to measure "nearby" from yet, so {@code markets} is empty.
 */
public record ReceiptMarketComparisonResponse(
        UUID receiptId,
        boolean available,
        OffsetDateTime computedAt,
        int comparableItemCount,
        boolean locationKnown,
        List<Market> markets
) {
    /** No comparison for this nota, now or later. */
    public static ReceiptMarketComparisonResponse unavailable(UUID receiptId) {
        return new ReceiptMarketComparisonResponse(receiptId, false, null, 0, true, List.of());
    }

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
