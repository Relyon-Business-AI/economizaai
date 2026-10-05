package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses Distrito Federal NFC-e DANFE HTML from the SEFAZ-DF viewer
 * ({@code ww1.receita.fazenda.df.gov.br/DecVisualizador/Nfce}). DF serves a
 * Bootstrap card/row layout (not the standard SEFAZ consult table): each item is
 * an {@code <li class="list-group-item">} holding a description + "(Cód: N)" PLU, a
 * "Qtde.: / UN: / Vl. Unit." line and a line total. Quantities use a DOT decimal
 * ({@code 1.0000}) while money uses a COMMA decimal ({@code 3,49}); the "Cód" is a
 * merchant PLU, not a GTIN, so items carry no EAN. The grand total comes from the
 * explicit "Valor a pagar R$:" line. Verified against a real DF nota.
 */
@Slf4j
public final class DfNfceDanfeParser {

    private static final DateTimeFormatter ISSUED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final Pattern CNPJ = Pattern.compile("CNPJ\\s*:?\\s*([\\d./-]{14,20})", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMISSION = Pattern.compile("(\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})");
    // "Qtde.: 1.0000 UN: UN Vl. Unit.: 3,49" (strong tags collapse to spaces via .text()).
    private static final Pattern QTY_LINE = Pattern.compile(
            "Qtde\\.?:\\s*([\\d.,]+)\\s*UN:\\s*(\\S+)\\s*Vl\\.?\\s*Unit\\.?:\\s*([\\d.,]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_A_PAGAR = Pattern.compile(
            "Valor\\s+a\\s+pagar\\s+R\\$:?\\s*([\\d.,]+)", Pattern.CASE_INSENSITIVE);

    private DfNfceDanfeParser() {
    }

    public static ParsedReceipt parse(String html, String chaveAcesso, String sourceUrl) {
        if (html == null || html.isBlank()) {
            throw new ReceiptParseException("df-danfe-empty");
        }
        var document = Jsoup.parse(html);
        var items = parseItems(document);
        if (items.isEmpty()) {
            log.warn("DF parser found no items for chave {}", LogMasker.chave(chaveAcesso));
            throw new ReceiptParseException("df-danfe-no-items");
        }
        var text = document.text();
        return ParsedReceipt.builder()
                .chaveAcesso(chaveAcesso)
                .cnpjEmitente(extractCnpj(text))
                .marketName(extractMarketName(document))
                .marketAddress(extractAddress(document))
                .issuedAt(extractIssuedAt(text))
                .totalAmount(extractTotal(text, items))
                .discountTotal(null)
                .approxTaxFederal(null)
                .approxTaxEstadual(null)
                .sourceUrl(sourceUrl)
                .rawHtml(null)
                .items(items)
                .build();
    }

    private static List<ParsedReceiptItem> parseItems(Document document) {
        var items = new ArrayList<ParsedReceiptItem>();
        var lineNumber = 1;
        for (var li : document.select("li.list-group-item")) {
            var descriptionElement = li.selectFirst("div.col-9 p.h6");
            var rows = li.select("div.row");
            if (descriptionElement == null || rows.size() < 2) {
                continue;
            }
            var withoutCode = descriptionElement.clone();
            withoutCode.select("small").remove(); // drop the "(Cód: N)" PLU
            var description = withoutCode.text().trim();
            var quantityLine = rows.get(1).selectFirst("div.col-9");
            if (description.isEmpty() || quantityLine == null) {
                continue;
            }
            var matcher = QTY_LINE.matcher(quantityLine.text());
            if (!matcher.find()) {
                continue;
            }
            var quantity = parseQuantity(matcher.group(1));
            var unit = UnitNormalizer.normalize(matcher.group(2).trim());
            var unitPrice = parseMoney(matcher.group(3));
            var lineTotalElement = rows.get(1).selectFirst("div.col-3 span");
            var totalPrice = lineTotalElement != null
                    ? parseMoney(lineTotalElement.text())
                    : unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            items.add(ParsedReceiptItem.builder()
                    .lineNumber(lineNumber++)
                    .rawDescription(description)
                    .ean(null) // DF reports a merchant PLU ("Cód"), not a GTIN
                    .quantity(quantity)
                    .unit(unit)
                    .unitPrice(unitPrice)
                    .totalPrice(totalPrice)
                    .nfcePromoFlag(false)
                    .build());
        }
        return items;
    }

    // Header: #heading1 holds three divs — name (bold col), CNPJ, address.
    private static String extractMarketName(Document document) {
        var element = document.selectFirst("#heading1 div.col");
        if (element == null) {
            return null;
        }
        var name = element.text().trim();
        return name.isBlank() ? null : name;
    }

    private static String extractAddress(Document document) {
        var header = document.selectFirst("#heading1");
        if (header == null) {
            return null;
        }
        var children = header.children();
        if (children.size() < 3) {
            return null;
        }
        var address = children.get(2).text().trim()
                .replaceAll("\\s+", " ")
                .replaceAll("(,\\s*)+", ", ")
                .replaceAll("^,\\s*|,\\s*$", "")
                .trim();
        return address.isBlank() ? null : address;
    }

    private static String extractCnpj(String text) {
        var matcher = CNPJ.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        var digits = matcher.group(1).replaceAll("\\D", "");
        return digits.length() == 14 ? digits : null;
    }

    private static LocalDateTime extractIssuedAt(String text) {
        var matcher = EMISSION.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDateTime.parse(matcher.group(1).trim(), ISSUED_AT);
        } catch (Exception ex) {
            return null;
        }
    }

    // Prefer the explicit "Valor a pagar R$:" grand total; fall back to summing lines.
    private static BigDecimal extractTotal(String text, List<ParsedReceiptItem> items) {
        var matcher = TOTAL_A_PAGAR.matcher(text);
        if (matcher.find()) {
            return parseMoney(matcher.group(1));
        }
        return items.stream().map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /** Money uses a comma decimal and optional dot thousands: "1.234,56" → 1234.56. */
    private static BigDecimal parseMoney(String raw) {
        var cleaned = raw.replaceAll("[^0-9.,]", "").replace(".", "").replace(",", ".");
        return cleaned.isBlank() ? BigDecimal.ZERO : new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
    }

    /** Quantity uses a DOT decimal ("1.0000"); a comma variant is tolerated. */
    private static BigDecimal parseQuantity(String raw) {
        var cleaned = raw.trim();
        if (cleaned.contains(",") && !cleaned.contains(".")) {
            cleaned = cleaned.replace(",", ".");
        }
        cleaned = cleaned.replaceAll("[^0-9.]", "");
        return cleaned.isBlank() ? BigDecimal.ONE : new BigDecimal(cleaned);
    }
}
