package com.relyon.economizaai.service.sefaz;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Builder
public record ParsedReceipt(
        String chaveAcesso,
        String cnpjEmitente,
        String marketName,
        String marketAddress,
        LocalDateTime issuedAt,
        BigDecimal totalAmount,
        BigDecimal discountTotal,
        BigDecimal approxTaxFederal,
        BigDecimal approxTaxEstadual,
        String sourceUrl,
        String rawHtml,
        List<ParsedReceiptItem> items
) {}
