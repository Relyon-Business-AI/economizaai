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
 * Parses the <b>legacy RS DANFE NFC-e</b> HTML served by
 * {@code sefaz.rs.gov.br/ASP/AAE_ROOT/NFE/SAT-WEB-NFE-NFC_2.asp}. Unlike the SVRS
 * responsive DANFE ({@link ResponsiveDanfeParser}), this portal renders the note
 * from a <b>bare 44-digit chave</b> (no QR signature) — the only public RS path
 * that does — so it powers the reconsult-by-chave bulk import
 * ({@link RsChaveReconsultService}). Validated 2026-09-22; see
 * {@code docs/ONBOARDING_IMPORT.md}.
 *
 * <p>The layout is a classic ASP table: item rows carry {@code id="Item + N"}
 * with six {@code td.NFCDetalhe_Item} cells (código, descrição, qtde, un, vl
 * unit, vl total); header blocks use {@code td.NFCCabecalho_*}.
 *
 * <p>The "Código" column is the <b>merchant-internal PLU</b> (zero-padded to 18
 * digits), NOT a GTIN — storing it as an EAN would wrongly merge different
 * products across merchants, so it is dropped and matching falls back to the
 * description (same rule {@link ResponsiveDanfeParser} applies to internal codes).
 *
 * <p>Stateless and pure, like {@link ResponsiveDanfeParser}.
 */
@Slf4j
public final class SatWebNfceParser {

    private static final DateTimeFormatter ISSUED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final Pattern EMISSION = Pattern.compile(
            "Data de Emiss[aã]o\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CNPJ = Pattern.compile("CNPJ\\s*:?\\s*([\\d./-]{14,18})", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL = Pattern.compile(
            "Valor total\\s*R\\$?\\s*([\\d.,]+)", Pattern.CASE_INSENSITIVE);

    private SatWebNfceParser() {
    }

    public static ParsedReceipt parse(String html, String chaveAcesso, String sourceUrl) {
        var document = Jsoup.parse(html);
        var items = parseItems(document);
        if (items.isEmpty()) {
            log.warn("SAT-WEB parser found no items for chave {}", LogMasker.chave(chaveAcesso));
            throw new ReceiptParseException("no-items-found");
        }
        var parsed = ParsedReceipt.builder()
                .chaveAcesso(chaveAcesso)
                .cnpjEmitente(parseCnpj(document))
                .marketName(parseMarketName(document))
                .marketAddress(parseMarketAddress(document))
                .issuedAt(parseIssuedAt(document))
                .totalAmount(parseTotal(document))
                .discountTotal(null)
                .approxTaxFederal(null)
                .approxTaxEstadual(null)
                .sourceUrl(sourceUrl)
                .rawHtml(html)
                .items(items)
                .build();
        log.info("Parsed SAT-WEB NFC-e: market='{}', total={}, items={}",
                parsed.marketName(), parsed.totalAmount(), items.size());
        return parsed;
    }

    private static List<ParsedReceiptItem> parseItems(Document document) {
        var items = new ArrayList<ParsedReceiptItem>();
        var line = 0;
        for (var row : document.select("tr[id^=Item]")) {
            var cells = row.select("td.NFCDetalhe_Item");
            if (cells.size() < 6) continue;
            var description = cells.get(1).text().trim();
            if (description.isBlank()) continue;
            var quantity = parseDecimalOrZero(cells.get(2).text());
            var unit = cells.get(3).text().trim();
            var unitPrice = parseDecimalOrNull(cells.get(4).text());
            var totalPrice = parseDecimalOrNull(cells.get(5).text());
            if (totalPrice == null && unitPrice != null && quantity.signum() > 0) {
                totalPrice = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            }
            if (totalPrice == null) continue;
            line++;
            items.add(ParsedReceiptItem.builder()
                    .lineNumber(line)
                    .rawDescription(description)
                    // "Código" is a merchant-internal PLU, not a GTIN — never an EAN.
                    .ean(null)
                    .quantity(quantity.signum() == 0 ? BigDecimal.ONE : quantity.setScale(3, RoundingMode.HALF_UP))
                    .unit(UnitNormalizer.normalize(unit))
                    .unitPrice(unitPrice == null ? null : unitPrice.setScale(4, RoundingMode.HALF_UP))
                    .totalPrice(totalPrice.setScale(2, RoundingMode.HALF_UP))
                    .nfcePromoFlag(false)
                    .build());
        }
        return items;
    }

    private static String parseCnpj(Document document) {
        var matcher = CNPJ.matcher(document.text());
        return matcher.find() ? matcher.group(1).replaceAll("\\D", "") : null;
    }

    private static String parseMarketName(Document document) {
        var name = document.selectFirst(".NFCCabecalho_SubTitulo");
        return name == null || name.text().isBlank() ? null : name.text().trim();
    }

    private static String parseMarketAddress(Document document) {
        for (var block : document.select(".NFCCabecalho_SubTitulo1")) {
            var text = block.text().trim();
            if (!text.isEmpty() && !text.toLowerCase().contains("cnpj")) {
                return text;
            }
        }
        return null;
    }

    private static LocalDateTime parseIssuedAt(Document document) {
        var matcher = EMISSION.matcher(document.text());
        if (matcher.find()) {
            try {
                return LocalDateTime.parse(matcher.group(1), ISSUED_AT);
            } catch (Exception ex) {
                log.debug("Failed to parse issuedAt from '{}': {}", matcher.group(1), ex.getMessage());
            }
        }
        return null;
    }

    private static BigDecimal parseTotal(Document document) {
        var matcher = TOTAL.matcher(document.text());
        return matcher.find() ? parseDecimalOrNull(matcher.group(1)) : null;
    }

    private static BigDecimal parseDecimalOrNull(String value) {
        if (value == null) return null;
        var cleaned = value.replaceAll("[^0-9,.\\-]", "").replace(".", "").replace(",", ".");
        if (cleaned.isBlank()) return null;
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal parseDecimalOrZero(String value) {
        var parsed = parseDecimalOrNull(value);
        return parsed == null ? BigDecimal.ZERO : parsed;
    }
}
