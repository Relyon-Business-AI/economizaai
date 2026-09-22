package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PriceHistoryResponse(
        UUID productId,
        String productName,
        String friendlyDescription,
        List<PricePoint> points
) {
    public record PricePoint(
            LocalDateTime issuedAt,
            String marketCnpj,
            String marketName,
            String marketFriendlyName,
            BigDecimal unitPrice,
            BigDecimal quantity
    ) {
        /** Copy with the household's custom display name applied; leaves the original {@code marketName} intact. */
        public PricePoint withMarketFriendlyName(String marketFriendlyName) {
            return new PricePoint(issuedAt, marketCnpj, marketName, marketFriendlyName, unitPrice, quantity);
        }
    }
}
