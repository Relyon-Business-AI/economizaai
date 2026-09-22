package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses Minas Gerais NFC-e DANFE HTML from the SEF/MG portal
 * ({@code portalsped.fazenda.mg.gov.br}). MG serves a contingency-style table
 * distinct from the standard SEFAZ consult markup, so it needs its own parser:
 *
 * <pre>
 *   &lt;td&gt;&lt;h7&gt;GEL KANECHOM STYLE 230G &lt;/h7&gt;(Código: 6707)&lt;/td&gt;
 *   &lt;td&gt;Qtde total de ítens: 1&lt;/td&gt;
 *   &lt;td&gt;UN: UN&lt;/td&gt;
 *   &lt;td&gt;Valor total R$: R$ 9,99&lt;/td&gt;
 * </pre>
 *
 * <p>Only line totals are given (no unit price → derived), quantities use a DOT
 * decimal ({@code 1.0177}) while money uses a COMMA decimal ({@code 9,99}), and
 * the "Código" is a merchant PLU, not a GTIN — so items carry no EAN.
 */
@Slf4j
public final class MgNfceDanfeParser {

    private static final DateTimeFormatter ISSUED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final Pattern CNPJ = Pattern.compile("CNPJ\\s*:?\\s*([\\d./-]{14,20})", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMISSION = Pattern.compile("(\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})");
    // One product row: description, PLU code, quantity, unit, line total.
    private static final Pattern ITEM = Pattern.compile(
            "<h7>\\s*(.*?)\\s*</h7>\\s*\\(C[óo]digo:\\s*([^)]*)\\)\\s*</td>\\s*"
                    + "<td>\\s*Qtde\\s+total\\s+de\\s+[íi]tens:\\s*([\\d.,]+)\\s*</td>\\s*"
                    + "<td>\\s*UN:\\s*([^<]*?)\\s*</td>\\s*"
                    + "<td>\\s*Valor\\s+total\\s+R\\$:\\s*R?\\$?\\s*([\\d.,]+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private MgNfceDanfeParser() {
    }

    public static ParsedReceipt parse(String html, String chaveAcesso, String sourceUrl) {
        if (html == null || html.isBlank()) {
            throw new ReceiptParseException("mg-danfe-empty");
        }
        var items = parseItems(html);
        if (items.isEmpty()) {
            log.warn("MG parser found no items for chave {}", LogMasker.chave(chaveAcesso));
            throw new ReceiptParseException("mg-danfe-no-items");
        }
        var text = Jsoup.parse(html).text();
        return ParsedReceipt.builder()
                .chaveAcesso(chaveAcesso)
                .cnpjEmitente(extractCnpj(text))
                .marketName(extractMarketName(text))
                .marketAddress(null)
                .issuedAt(extractIssuedAt(text))
                .totalAmount(sumItemTotals(items))
                .discountTotal(null)
                .approxTaxFederal(null)
                .approxTaxEstadual(null)
                .sourceUrl(sourceUrl)
                .rawHtml(null)
                .items(items)
                .build();
    }

    private static List<ParsedReceiptItem> parseItems(String html) {
        var items = new ArrayList<ParsedReceiptItem>();
        var matcher = ITEM.matcher(html);
        var lineNumber = 1;
        while (matcher.find()) {
            var description = Jsoup.parse(matcher.group(1)).text().trim();
            var quantity = parseQuantity(matcher.group(3));
            var unit = UnitNormalizer.normalize(matcher.group(4).trim());
            var totalPrice = parseMoney(matcher.group(5));
            var unitPrice = quantity.signum() == 0
                    ? BigDecimal.ZERO
                    : totalPrice.divide(quantity, 2, RoundingMode.HALF_UP);
            items.add(ParsedReceiptItem.builder()
                    .lineNumber(lineNumber++)
                    .rawDescription(description)
                    .ean(null) // MG reports a merchant PLU ("Código"), not a GTIN
                    .quantity(quantity)
                    .unit(unit)
                    .unitPrice(unitPrice)
                    .totalPrice(totalPrice)
                    .nfcePromoFlag(false)
                    .build());
        }
        return items;
    }

    // The market name is the trailing run of company text right before "CNPJ" in
    // the DANFE header (e.g. "SUPERMERCADO BORGES E MIRANDA LTDA CNPJ:").
    private static final Pattern MARKET_NAME = Pattern.compile("([A-ZÀ-Ú0-9][A-ZÀ-Ú0-9 ./&'-]{4,90})\\s*$");

    private static String extractMarketName(String text) {
        var cnpjIndex = text.indexOf("CNPJ");
        if (cnpjIndex <= 0) return null;
        var head = text.substring(0, cnpjIndex).trim();
        var matcher = MARKET_NAME.matcher(head);
        if (!matcher.find()) return null;
        var name = matcher.group(1).trim();
        return name.isBlank() ? null : name;
    }

    private static String extractCnpj(String text) {
        var matcher = CNPJ.matcher(text);
        if (!matcher.find()) return null;
        var digits = matcher.group(1).replaceAll("\\D", "");
        return digits.length() == 14 ? digits : null;
    }

    private static LocalDateTime extractIssuedAt(String text) {
        var matcher = EMISSION.matcher(text);
        if (!matcher.find()) return null;
        try {
            return LocalDateTime.parse(matcher.group(1).trim(), ISSUED_AT);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Total from the sum of line totals. The DANFE's own grand-total figure is
     * echoed from the QR ("73.45", DOT decimal) and clashes with the comma-decimal
     * line values, so summing the (exact) per-line totals is the reliable source.
     */
    private static BigDecimal sumItemTotals(List<ParsedReceiptItem> items) {
        return items.stream().map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /** Money uses a comma decimal and optional dot thousands: "1.234,56" → 1234.56. */
    private static BigDecimal parseMoney(String raw) {
        var cleaned = raw.replaceAll("[^0-9.,]", "").replace(".", "").replace(",", ".");
        return cleaned.isBlank() ? BigDecimal.ZERO : new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
    }

    /** Quantity uses a DOT decimal ("1.0177"); a comma variant is tolerated. */
    private static BigDecimal parseQuantity(String raw) {
        var cleaned = raw.trim();
        if (cleaned.contains(",") && !cleaned.contains(".")) {
            cleaned = cleaned.replace(",", ".");
        }
        cleaned = cleaned.replaceAll("[^0-9.]", "");
        return cleaned.isBlank() ? BigDecimal.ONE : new BigDecimal(cleaned);
    }
}
