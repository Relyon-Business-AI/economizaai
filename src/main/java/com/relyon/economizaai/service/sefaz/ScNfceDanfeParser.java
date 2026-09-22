package com.relyon.economizaai.service.sefaz;

import com.relyon.economizaai.exception.ReceiptParseException;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
public final class ScNfceDanfeParser {

    private static final DateTimeFormatter ISSUED_AT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final Pattern CNPJ = Pattern.compile("CNPJ\\s*:?\\s*([\\d./-]{14,18})", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMISSION = Pattern.compile(
            "Emiss[aã]o\\s*:?\\s*(\\d{2}/\\d{2}/\\d{4}\\s+\\d{2}:\\d{2}:\\d{2})",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CHAVE = Pattern.compile("(\\d[\\d ]{42,}\\d)");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern ITEM_HEADER = Pattern.compile(
            "^\\s*(.+?)\\s*\\(?\\s*C[oó]d(?:igo|\\.)\\s*:?\\s*([^)]*?)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ITEM_HEADER_ANYWHERE = Pattern.compile(
            "([\\p{L}\\p{N}][\\p{L}\\p{N} ./%+\\-]{2,180}?)\\s*\\(?\\s*C[oó]d(?:igo|\\.)\\s*:?\\s*([^)]*?)\\)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ITEM_QUANTITY_UNIT = Pattern.compile(
            "(?:Qtde|Qtd|Quantidade)\\.?\\s*:?\\s*([\\d.,]+)\\s*UN\\s*:?\\s*(.*?)(?=\\s*(?:Vl\\.?\\s*Unit|Valor\\s*Unit|Vl\\.?\\s*Total|Valor\\s*Total)|$)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ITEM_UNIT_PRICE = Pattern.compile(
            "(?:Vl\\.?\\s*Unit\\.?|Valor\\s*Unit(?:[aá]rio)?)\\s*:?\\s*([\\d.,]+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ITEM_TOTAL_PRICE = Pattern.compile(
            "(?:Vl\\.?\\s*Total|Valor\\s*Total)\\s*:?\\s*([\\d.,]+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TOTAL = Pattern.compile(
            "(?:Valor\\s+(?:total|a\\s+pagar)|Cart[aã]o\\s+de\\s+D[eé]bito|Cart[aã]o\\s+de\\s+Cr[eé]dito|Dinheiro|Pix)\\s+([\\d.,]+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ScNfceDanfeParser() {
    }

    public static ParsedReceipt parse(String html, String chaveAcesso, String sourceUrl) {
        var document = Jsoup.parse(html);
        var items = parseItems(document);
        if (items.isEmpty()) {
            log.warn("SC parser found no items for chave {}", LogMasker.chave(chaveAcesso));
            throw new ReceiptParseException("no-items-found");
        }
        var chave = chaveAcesso == null || chaveAcesso.isBlank()
                ? extractChave(document).orElse(null)
                : chaveAcesso;
        return ParsedReceipt.builder()
                .chaveAcesso(chave)
                .cnpjEmitente(parseCnpj(document))
                .marketName(parseMarketName(document))
                .marketAddress(parseMarketAddress(document))
                .issuedAt(parseIssuedAt(document))
                .totalAmount(parseTotal(document, items))
                .sourceUrl(sourceUrl)
                .rawHtml(html)
                .items(items)
                .build();
    }

    /**
     * True when the DANFE has at least one parseable item. Lets the SC adapter tell a
     * real receipt from a flaky non-DANFE page (error/interstitial) before returning
     * it, so the fetch retry loop can re-fetch instead of hard-failing on 0 items.
     */
    public static boolean hasItems(String html) {
        if (html == null || html.isBlank()) return false;
        return !parseItems(Jsoup.parse(html)).isEmpty();
    }

    static java.util.Optional<String> extractChave(Document document) {
        var text = document.text();
        var marker = text.toLowerCase().indexOf("chave de acesso");
        var search = marker >= 0 ? text.substring(marker) : text;
        var matcher = CHAVE.matcher(search);
        if (!matcher.find()) return java.util.Optional.empty();
        var digits = matcher.group(1).replaceAll("\\D", "");
        return digits.length() == 44 ? java.util.Optional.of(digits) : java.util.Optional.empty();
    }

    private static List<ParsedReceiptItem> parseItems(Document document) {
        List<ParsedReceiptItem> items = new ArrayList<>();
        var line = 0;
        for (var element : document.select("tr, li, div")) {
            var text = element.text();
            if (!looksLikeItem(text) || hasItemChild(element)) continue;
            var parsed = parseItem(text, line + 1);
            if (parsed == null) continue;
            line++;
            items.add(parsed);
        }
        if (!items.isEmpty()) return items;
        items = parseItemsFromTextLines(document);
        return items.isEmpty() ? parseItemsFromFlattenedText(document) : items;
    }

    private static List<ParsedReceiptItem> parseItemsFromTextLines(Document document) {
        var items = new ArrayList<ParsedReceiptItem>();
        StringBuilder current = null;
        for (var line : lines(document)) {
            if (looksLikeItemHeader(line)) {
                addItemBlock(items, current);
                current = new StringBuilder(line);
            } else if (current != null) {
                current.append(' ').append(line);
            }
        }
        addItemBlock(items, current);
        return items;
    }

    private static void addItemBlock(List<ParsedReceiptItem> items, StringBuilder block) {
        if (block == null) return;
        var text = block.toString();
        if (!looksLikeItem(text)) return;
        var parsed = parseItem(text, items.size() + 1);
        if (parsed != null) {
            items.add(parsed);
        }
    }

    private static List<ParsedReceiptItem> parseItemsFromFlattenedText(Document document) {
        var text = document.text();
        var headers = ITEM_HEADER_ANYWHERE.matcher(text);
        var blocks = new ArrayList<String>();
        var blockStart = -1;
        while (headers.find()) {
            if (blockStart >= 0) {
                blocks.add(text.substring(blockStart, headers.start()).trim());
            }
            blockStart = headers.start();
        }
        if (blockStart >= 0) {
            blocks.add(text.substring(blockStart).trim());
        }

        var items = new ArrayList<ParsedReceiptItem>();
        for (var block : blocks) {
            if (!looksLikeItem(block)) continue;
            var parsed = parseItem(block, items.size() + 1);
            if (parsed != null) {
                items.add(parsed);
            }
        }
        return items;
    }

    private static ParsedReceiptItem parseItem(String text, int lineNumber) {
        var header = ITEM_HEADER.matcher(text);
        var quantityUnit = ITEM_QUANTITY_UNIT.matcher(text);
        var unitPrice = ITEM_UNIT_PRICE.matcher(text);
        var totalPrice = ITEM_TOTAL_PRICE.matcher(text);
        if (header.find() && quantityUnit.find() && unitPrice.find() && totalPrice.find()) {
            return item(lineNumber, header.group(1), header.group(2), quantityUnit.group(1),
                    quantityUnit.group(2), unitPrice.group(1), totalPrice.group(1));
        }
        return null;
    }

    private static ParsedReceiptItem item(int lineNumber, String description, String code, String quantity,
                                          String unit, String unitPrice, String totalPrice) {
        return ParsedReceiptItem.builder()
                .lineNumber(lineNumber)
                .rawDescription(description.trim())
                .ean(extractEan(code))
                .quantity(parseDecimalOrZero(quantity))
                .unit(unit.trim().toUpperCase())
                .unitPrice(parseDecimalOrNull(unitPrice))
                .totalPrice(parseDecimalOrNull(totalPrice))
                .nfcePromoFlag(false)
                .build();
    }

    private static boolean looksLikeItem(String text) {
        if (text == null) return false;
        var lower = text.toLowerCase();
        return (lower.contains("código") || lower.contains("codigo") || lower.contains("cód."))
                && (lower.contains("qtde") || lower.contains("qtd") || lower.contains("quantidade"))
                && (lower.contains("vl. total") || lower.contains("vl total") || lower.contains("valor total"));
    }

    private static boolean looksLikeItemHeader(String text) {
        if (text == null) return false;
        return ITEM_HEADER.matcher(text).find();
    }

    private static boolean hasItemChild(Element element) {
        for (var child : element.children()) {
            if (looksLikeItem(child.text())) return true;
        }
        return false;
    }

    private static String parseCnpj(Document document) {
        var matcher = CNPJ.matcher(document.text());
        return matcher.find() ? matcher.group(1).replaceAll("\\D", "") : null;
    }

    private static String parseMarketName(Document document) {
        var lines = lines(document);
        for (var i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase().startsWith("cnpj") && i > 0) {
                return lines.get(i - 1);
            }
        }
        return null;
    }

    private static String parseMarketAddress(Document document) {
        var lines = lines(document);
        for (var i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).toLowerCase().startsWith("cnpj")) {
                return lines.get(i + 1);
            }
        }
        return null;
    }

    private static LocalDateTime parseIssuedAt(Document document) {
        var matcher = EMISSION.matcher(document.text());
        if (!matcher.find()) return null;
        try {
            return LocalDateTime.parse(matcher.group(1), ISSUED_AT);
        } catch (Exception ex) {
            return null;
        }
    }

    private static BigDecimal parseTotal(Document document, List<ParsedReceiptItem> items) {
        BigDecimal total = null;
        var matcher = TOTAL.matcher(document.text());
        while (matcher.find()) {
            total = parseDecimalOrNull(matcher.group(1));
        }
        if (total != null) return total;
        return items.stream()
                .map(ParsedReceiptItem::totalPrice)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // jsoup's Document.body() is @Nullable (null for body-less/fragment inputs);
    // Sonar's flow analysis can't see that, so it thinks the guard is always false.
    // Keep it — a malformed SEFAZ page without a <body> must not NPE.
    @SuppressWarnings("java:S2583")
    private static List<String> lines(Document document) {
        var body = document.body();
        var text = body == null ? document.text() : body.wholeText();
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private static String extractEan(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var matcher = DIGITS.matcher(raw);
        if (!matcher.find()) return null;
        var digits = matcher.group();
        if (digits.length() < 8) return null;
        if (digits.length() > 14) digits = digits.substring(digits.length() - 14);
        return digits;
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
