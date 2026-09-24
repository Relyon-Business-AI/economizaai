package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.model.enums.ReceiptChannel;
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
        // In-store vs online. Null from parsers that don't read indPres (NFC-e 65) → IN_STORE at persist.
        ReceiptChannel channel,
        List<ParsedReceiptItem> items
) {}
