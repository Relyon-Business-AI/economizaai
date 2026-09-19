package com.relyon.economizai.service.sefaz;

import com.relyon.economizai.exception.ReceiptParseException;
import com.relyon.economizai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the raw <b>NFe v4.00 XML</b> a few state portals return to non-browser
 * clients instead of the responsive-DANFE HTML. Pernambuco (PE) is the proven
 * case: its portal 301-redirects {@code http}→{@code https:444} and then answers
 * a plain GET with {@code text/xml} (the browser renders it client-side via the
 * referenced XSL), so {@link ResponsiveDanfeParser}'s HTML selectors find nothing
 * — see {@code fixtures/sefaz/pe/}.
 *
 * <p>Stateless and pure, like {@link ResponsiveDanfeParser}. Namespace-agnostic:
 * parses with Jsoup's XML parser and selects by local tag name, so the default
 * {@code xmlns="http://www.portalfiscal.inf.br/nfe"} needs no special handling.
 */
@Slf4j
public final class NfceXmlParser {

    private NfceXmlParser() {
    }

    /** Cheap sniff so the adapter can route XML bodies here and HTML to the DANFE parser. */
    static boolean looksLikeNfeXml(String body) {
        if (body == null) return false;
        var head = body.stripLeading();
        return head.startsWith("<?xml") && (body.contains("<infNFe") || body.contains("<nfeProc"));
    }

    public static ParsedReceipt parse(String xml, String chaveAcesso, String sourceUrl) {
        var document = Jsoup.parse(xml, "", Parser.xmlParser());
        var items = parseItems(document);
        if (items.isEmpty()) {
            var motivo = textOf(document.selectFirst("xmotivo"));
            log.warn("NFe XML had no items for chave {}{}", LogMasker.chave(chaveAcesso),
                    motivo.isBlank() ? "" : " motivo='" + motivo + "'");
            throw new ReceiptParseException("no-items-found");
        }
        var emit = document.selectFirst("emit");
        var totals = document.selectFirst("icmstot");
        var parsed = ParsedReceipt.builder()
                .chaveAcesso(chaveAcesso)
                .cnpjEmitente(emit == null ? null : digitsOrNull(textOf(emit.selectFirst("cnpj"))))
                .marketName(emit == null ? null : blankToNull(textOf(emit.selectFirst("xnome"))))
                .marketAddress(parseAddress(emit))
                .issuedAt(parseIssuedAt(document))
                .totalAmount(totals == null ? null : decimalOrNull(textOf(totals.selectFirst("vnf"))))
                .discountTotal(parsePositiveDiscount(totals))
                .approxTaxFederal(null)
                .approxTaxEstadual(null)
                .sourceUrl(sourceUrl)
                .rawHtml(xml)
                .items(items)
                .build();
        log.info("Parsed NFe XML receipt: market='{}', total={}, items={}",
                parsed.marketName(), parsed.totalAmount(), items.size());
        return parsed;
    }

    private static List<ParsedReceiptItem> parseItems(Element document) {
        var items = new ArrayList<ParsedReceiptItem>();
        var line = 0;
        for (var det : document.select("det")) {
            var prod = det.selectFirst("prod");
            if (prod == null) continue;
            var description = textOf(prod.selectFirst("xprod"));
            var totalPrice = decimalOrNull(textOf(prod.selectFirst("vprod")));
            if (description.isBlank() || totalPrice == null) continue;

            line++;
            var quantity = decimalOrNull(textOf(prod.selectFirst("qcom")));
            items.add(ParsedReceiptItem.builder()
                    .lineNumber(line)
                    .rawDescription(description.trim())
                    .ean(extractEan(textOf(prod.selectFirst("cean"))))
                    .quantity(quantity == null || quantity.signum() == 0 ? BigDecimal.ONE : quantity)
                    .unit(UnitNormalizer.normalize(textOf(prod.selectFirst("ucom"))))
                    .unitPrice(decimalOrNull(textOf(prod.selectFirst("vuncom"))))
                    .totalPrice(totalPrice.setScale(2, RoundingMode.HALF_UP))
                    .nfcePromoFlag(false)
                    .build());
        }
        return items;
    }

    private static String parseAddress(Element emit) {
        if (emit == null) return null;
        var ender = emit.selectFirst("enderemit");
        if (ender == null) return null;
        var street = textOf(ender.selectFirst("xlgr"));
        var number = textOf(ender.selectFirst("nro"));
        var district = textOf(ender.selectFirst("xbairro"));
        var city = textOf(ender.selectFirst("xmun"));
        var uf = textOf(ender.selectFirst("uf"));
        var parts = new ArrayList<String>();
        if (!street.isBlank()) parts.add(number.isBlank() ? street : street + ", " + number);
        if (!district.isBlank()) parts.add(district);
        if (!city.isBlank()) parts.add(uf.isBlank() ? city : city + " - " + uf);
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static LocalDateTime parseIssuedAt(Element document) {
        var raw = textOf(document.selectFirst("dhemi"));
        if (raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw.trim()).toLocalDateTime();
        } catch (Exception ex) {
            log.debug("Failed to parse dhEmi from '{}': {}", raw, ex.getMessage());
            return null;
        }
    }

    /** {@code vDesc} is present-but-zero on most notes; keep it only when the merchant declared one. */
    private static BigDecimal parsePositiveDiscount(Element totals) {
        if (totals == null) return null;
        var discount = decimalOrNull(textOf(totals.selectFirst("vdesc")));
        return discount != null && discount.signum() > 0 ? discount : null;
    }

    /** {@code cEAN} is "SEM GTIN" when the product has no barcode; only real 8-14 digit GTINs survive. */
    private static String extractEan(String raw) {
        if (raw == null) return null;
        var code = raw.replaceAll("\\D", "");
        if (code.length() < 8 || code.length() > 14) return null;
        return code;
    }

    private static String textOf(Element element) {
        return element == null ? "" : element.text().trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String digitsOrNull(String value) {
        if (value == null) return null;
        var digits = value.replaceAll("\\D", "");
        return digits.isBlank() ? null : digits;
    }

    private static BigDecimal decimalOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
