package com.relyon.economizaai.service.sefaz;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record ParsedReceiptItem(
        int lineNumber,
        String rawDescription,
        String ean,
        BigDecimal quantity,
        String unit,
        BigDecimal unitPrice,
        BigDecimal totalPrice,
        boolean nfcePromoFlag
) {}
