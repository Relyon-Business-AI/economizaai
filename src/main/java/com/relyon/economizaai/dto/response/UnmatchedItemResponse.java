package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record UnmatchedItemResponse(
        UUID receiptItemId,
        UUID receiptId,
        String marketName,
        LocalDateTime issuedAt,
        String rawDescription,
        String ean,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        String unit
) {}
