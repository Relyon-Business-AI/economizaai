package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.model.enums.ReceiptChannel;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses the <b>NF-e (modelo 55)</b> "Produtos e Serviços" view rendered by the
 * SVRS public consult
 * ({@code dfe-portal.svrs.rs.gov.br/NFe/ConsultaPublicaDfe}) from a bare chave.
 * This is the e-commerce path of the reconsult bulk import — online purchases
 * (Amazon &amp; co.) are NF-e 55, and unlike the NFC-e DANFE this view exposes the
 * <b>real EAN</b> ("Código EAN Comercial"). Validated 2026-09-22; see
 * {@code docs/ONBOARDING_IMPORT.md}.
 *
 * <p>Layout (XSLT): each item is a {@code table.toggle.box} summary row
 * (número, descrição, qtd, unidade, valor) immediately followed by a
 * {@code table.toggable.box} detail table carrying label/span pairs (Código EAN
 * Comercial, Valor unitário de comercialização, …).
 *
 * <p>Stateless and pure, like {@link ResponsiveDanfeParser}.
 */
@Slf4j
public final class SvrsNfeProdutosParser {

    private static final DateTimeFormatter ISSUED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final Pattern EMISSION = Pattern.compile(
            "Data de Emiss[aã]o\\s*(\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private SvrsNfeProdutosParser() {
    }

    public static ParsedReceipt parse(String html, String chaveAcesso, String sourceUrl) {
        var document = Jsoup.parse(html);
        var items = parseItems(document);
        if (items.isEmpty()) {
            log.warn("NF-e Produtos parser found no items for chave {}", LogMasker.chave(chaveAcesso));
            throw new ReceiptParseException("no-items-found");
        }
        var parsed = ParsedReceipt.builder()
                .chaveAcesso(chaveAcesso)
                .cnpjEmitente(ChaveAcessoParser.extractCnpj(chaveAcesso))
                .marketName(parseMarketName(document))
                .marketAddress(null)
                .issuedAt(parseIssuedAt(document))
                .totalAmount(parseTotal(document, items))
                .discountTotal(null)
                .approxTaxFederal(null)
                .approxTaxEstadual(null)
                .sourceUrl(sourceUrl)
                .rawHtml(html)
                .channel(parseChannel(document))
                .items(items)
                .build();
        log.info("Parsed NF-e 55 receipt: market='{}', total={}, items={}",
                parsed.marketName(), parsed.totalAmount(), items.size());
        return parsed;
    }

    private static List<ParsedReceiptItem> parseItems(Document document) {
        var items = new ArrayList<ParsedReceiptItem>();
        var line = 0;
        for (var summary : document.select("table.toggle.box")) {
            var description = text(summary, "td.fixo-prod-serv-descricao span");
            if (description.isBlank()) continue;
            var quantity = parseDecimalOrZero(text(summary, "td.fixo-prod-serv-qtd span"));
            var unit = text(summary, "td.fixo-prod-serv-uc span");
            var totalPrice = parseDecimalOrNull(text(summary, "td.fixo-prod-serv-vb span"));

            var detail = summary.nextElementSibling();
            var ean = detail == null ? null : extractEan(labelValue(detail, "Código EAN Comercial"));
            var unitPrice = detail == null ? null
                    : parseDecimalOrNull(labelValue(detail, "Valor unitário de comercialização"));
            if (totalPrice == null && unitPrice != null && quantity.signum() > 0) {
                totalPrice = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            }
            if (totalPrice == null) continue;
            line++;
            items.add(ParsedReceiptItem.builder()
                    .lineNumber(line)
                    .rawDescription(description.trim())
                    .ean(ean)
                    .quantity(quantity.signum() == 0 ? BigDecimal.ONE : quantity.setScale(3, RoundingMode.HALF_UP))
                    .unit(UnitNormalizer.normalize(unit))
                    .unitPrice(unitPrice == null ? null : unitPrice.setScale(4, RoundingMode.HALF_UP))
                    .totalPrice(totalPrice.setScale(2, RoundingMode.HALF_UP))
                    .nfcePromoFlag(false)
                    .build());
        }
        return items;
    }

    /** Emitter is the FIRST "Nome / Razão Social" (the Destinatário's is the buyer, and comes after). */
    private static String parseMarketName(Document document) {
        for (var label : document.select("label")) {
            if (label.text().toLowerCase().contains("razão social")) {
                var span = label.nextElementSibling();
                var value = span == null ? "" : span.text().trim();
                if (!value.isBlank()) return value;
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

    /**
     * The nota total ("Valor Total da Nota Fiscal") is net of item discounts;
     * fall back to summing item totals when the label is absent.
     */
    private static BigDecimal parseTotal(Document document, List<ParsedReceiptItem> items) {
        var labelled = parseDecimalOrNull(findLabelValue(document, "Valor Total da Nota"));
        if (labelled != null) return labelled;
        return items.stream()
                .map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * "Presença do Comprador" (indPres): "2 - Operação pela internet", "1 - Operação presencial", etc.
     * Não presencial (2 internet, 3 teleatendimento, 4 entrega domicílio, 9 outros) → ONLINE;
     * qualquer outro / ausente → IN_STORE (conservador, não infla o índice online por engano).
     */
    private static ReceiptChannel parseChannel(Document document) {
        var value = findLabelValue(document, "Presença do Comprador");
        var digit = DIGITS.matcher(value == null ? "" : value);
        if (digit.find()) {
            var code = digit.group();
            if (code.equals("2") || code.equals("3") || code.equals("4") || code.equals("9")) {
                return ReceiptChannel.ONLINE;
            }
        }
        return ReceiptChannel.IN_STORE;
    }

    private static String findLabelValue(Element root, String labelContains) {
        var needle = labelContains.toLowerCase();
        for (var label : root.select("label")) {
            if (label.text().toLowerCase().contains(needle)) {
                var span = label.nextElementSibling();
                var value = span == null ? "" : span.text().trim();
                if (!value.isBlank()) return value;
            }
        }
        return null;
    }

    private static String labelValue(Element scope, String labelContains) {
        return findLabelValue(scope, labelContains);
    }

    private static String text(Element root, String selector) {
        var element = root.selectFirst(selector);
        return element == null ? "" : element.text().trim();
    }

    /** Keeps only real all-digit GTINs (8–14 digits); "SEM GTIN" and internal codes return null. */
    private static String extractEan(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var code = raw.replaceAll("[^A-Za-z0-9]", "");
        if (!DIGITS.matcher(code).matches()) return null;
        if (code.length() < 8) return null;
        if (code.length() > 14) code = code.substring(code.length() - 14);
        return code;
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
