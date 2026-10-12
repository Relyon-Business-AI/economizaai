package com.relyon.economizaai.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import com.relyon.economizaai.service.ai.AiGateway;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import com.relyon.economizaai.service.privacy.LogMasker;
import com.relyon.economizaai.service.sefaz.ChaveAcessoParser;
import com.relyon.economizaai.service.sefaz.ParsedReceipt;
import com.relyon.economizaai.service.sefaz.ParsedReceiptItem;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Parse-rescue layer: when a state parser fails on SEFAZ HTML we ALREADY fetched,
 * the AI extracts the receipt from the page text before the ingestion chain falls
 * to the paid Infosimples re-fetch (~R$0.05 vs R$0.24). It self-heals portal layout
 * changes — the main source of Infosimples fallback spend.
 *
 * <p>Runs on the app's live AI stack ({@link AiGateway} → Anthropic, extractor
 * model) — NOT the dormant OpenAI client — so it works wherever ANTHROPIC_API_KEY
 * is set (dev + prod today). The gateway logs tokens/cost per call under
 * {@link AiActivity#RECEIPT_RESCUE} in the admin spend panel.
 *
 * <p>Anti-hallucination rails: the chave and the CNPJ are NEVER taken from the model
 * (both come from the chave we already hold, and a CNPJ the model does extract must
 * match it), and the item totals must add up to the receipt total within a per-line
 * rounding tolerance — otherwise the rescue is discarded and the chain continues
 * unchanged. Rescued receipts come from the real SEFAZ document, so unlike photo
 * extractions they DO feed the collaborative index.
 *
 * <p>Cost guards: global paid-API budget + per-user daily cap
 * ({@link PaidApiService#LLM_HTML_RESCUE}) checked before spending; one ledger row
 * per successful call (the gateway's own usage log covers failures and the global
 * AI daily request cap). A guard block returns null (the chain continues) instead
 * of failing the receipt.
 */
@Slf4j
@Service
public class HtmlReceiptRescueService {

    private static final DateTimeFormatter ISSUED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern EAN = Pattern.compile("\\d{8}|\\d{12,14}");
    /** Anything shorter is an error shell with no DANFE to read — don't spend on it. */
    private static final int MIN_TEXT_CHARS = 200;
    private static final int MAX_COMPLETION_TOKENS = 8000;

    private final AiGateway aiGateway;
    private final PaidApiGuardService paidApiGuard;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final int maxItems;
    private final int maxTextChars;

    public HtmlReceiptRescueService(AiGateway aiGateway,
                                    PaidApiGuardService paidApiGuard,
                                    @Value("${economizaai.llm.html-rescue.enabled:true}") boolean enabled,
                                    @Value("${economizaai.llm.html-rescue.max-items:80}") int maxItems,
                                    @Value("${economizaai.llm.html-rescue.max-text-chars:60000}") int maxTextChars) {
        this.aiGateway = aiGateway;
        this.paidApiGuard = paidApiGuard;
        this.enabled = enabled;
        this.maxItems = maxItems;
        this.maxTextChars = maxTextChars;
    }

    /**
     * Attempts to extract a {@link ParsedReceipt} from DANFE HTML the regular parser
     * failed on. Returns null — the caller continues the fallback chain untouched —
     * when the layer is off or unconfigured, a cost guard blocks the spend, the AI
     * call fails, or the extraction fails validation.
     */
    public ParsedReceipt rescue(String html, String chave, UnidadeFederativa uf, String sourceUrl, UUID userId) {
        if (!enabled || !aiGateway.isEnabled() || html == null || html.isBlank()) {
            return null;
        }
        try {
            paidApiGuard.assertUnderGlobalBudget();
            paidApiGuard.assertWithinDailyCap(userId, PaidApiService.LLM_HTML_RESCUE);
        } catch (PaidApiBudgetExceededException | PaidApiQuotaExceededException guardEx) {
            log.info("llm_rescue.blocked uf={} chave={} reason={}",
                    uf, LogMasker.chave(chave), guardEx.getClass().getSimpleName());
            return null;
        }
        var pageText = extractPlainText(html);
        if (pageText.length() < MIN_TEXT_CHARS) {
            log.info("llm_rescue.skipped uf={} chave={} reason=text_too_short chars={}",
                    uf, LogMasker.chave(chave), pageText.length());
            return null;
        }
        String responseText;
        try {
            responseText = aiGateway.complete(AiActivity.RECEIPT_RESCUE, aiGateway.extractorModel(),
                    systemPrompt(), userContent(chave, pageText), MAX_COMPLETION_TOKENS);
        } catch (AiGateway.AiUnavailableException unavailableEx) {
            // Disabled / daily cap / provider failure — tokens and failures are already
            // accounted in the gateway's own usage log, so no paid_api_call row here.
            log.warn("llm_rescue.unavailable uf={} chave={} reason={}",
                    uf, LogMasker.chave(chave), unavailableEx.getMessage());
            return null;
        }
        paidApiGuard.recordSuccess(userId, PaidApiService.LLM_HTML_RESCUE, uf.name(), "anthropic");
        var extraction = parseJsonOrNull(responseText);
        if (extraction == null) {
            log.info("llm_rescue.rejected uf={} chave={} reason=unparseable_response", uf, LogMasker.chave(chave));
            return null;
        }
        return toValidatedReceipt(extraction, html, chave, uf, sourceUrl);
    }

    private String systemPrompt() {
        return """
                Voce extrai os dados de um cupom fiscal brasileiro (DANFE NFC-e) a partir do TEXTO \
                da pagina de consulta da SEFAZ que o parser regular nao conseguiu ler. \
                Se o texto NAO contem um cupom fiscal com itens, responda {"is_receipt": false}. \
                Nunca invente itens ou valores; copie exatamente o que esta no texto. Valores com ponto decimal. \
                total_amount e o "Valor total R$" (a soma dos itens, ANTES de descontos); \
                discount_total e o "Descontos R$" quando impresso.
                Responda APENAS o JSON, sem texto fora dele: {"is_receipt": true, "cnpj": "14 digitos do emitente|null", \
                "market_name": "...|null", "market_address": "...|null", "issued_at": "yyyy-MM-dd HH:mm|null", \
                "total_amount": <numero|null>, "discount_total": <numero|null>, \
                "items": [{"description": "...", "ean": "codigo de barras, so digitos|null", \
                "quantity": <numero>, "unit": "UN|KG|...", "unit_price": <numero|null>, "total_price": <numero|null>}]}""";
    }

    private static String userContent(String chave, String pageText) {
        return "Chave de acesso: " + chave + "\n\nTexto da pagina:\n" + pageText;
    }

    /** Collapses the HTML to block-level text — ~10x fewer tokens than raw markup. */
    private String extractPlainText(String html) {
        var document = Jsoup.parse(html);
        document.select("script, style, noscript, iframe, svg").remove();
        document.select("br").append("\\n");
        document.select("p, div, li, tr, table, h1, h2, h3, h4").append("\\n");
        var text = document.text().replace("\\n", "\n").replaceAll("\n{2,}", "\n").trim();
        return text.length() > maxTextChars ? text.substring(0, maxTextChars) : text;
    }

    /** The model answers plain text promised to be JSON — strip markdown fences and parse defensively. */
    private JsonNode parseJsonOrNull(String responseText) {
        if (responseText == null) return null;
        var text = responseText.trim();
        var start = text.indexOf('{');
        var end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return objectMapper.readTree(text.substring(start, end + 1));
        } catch (Exception parseEx) {
            return null;
        }
    }

    private ParsedReceipt toValidatedReceipt(JsonNode extraction, String html, String chave,
                                             UnidadeFederativa uf, String sourceUrl) {
        if (!extraction.path("is_receipt").asBoolean(false)) {
            log.info("llm_rescue.rejected uf={} chave={} reason=not_a_receipt", uf, LogMasker.chave(chave));
            return null;
        }
        var items = buildItems(extraction.path("items"));
        if (items == null || items.isEmpty()) {
            log.info("llm_rescue.rejected uf={} chave={} reason=no_usable_items", uf, LogMasker.chave(chave));
            return null;
        }
        if (items.size() > maxItems) {
            log.info("llm_rescue.rejected uf={} chave={} reason=too_many_items count={}",
                    uf, LogMasker.chave(chave), items.size());
            return null;
        }
        var cnpjFromChave = ChaveAcessoParser.extractCnpj(chave);
        var cnpjFromLlm = digitsOrNull(textOrNull(extraction, "cnpj"));
        if (cnpjFromLlm != null && !cnpjFromLlm.equals(cnpjFromChave)) {
            log.info("llm_rescue.rejected uf={} chave={} reason=cnpj_mismatch", uf, LogMasker.chave(chave));
            return null;
        }
        var itemsSum = items.stream()
                .map(ParsedReceiptItem::totalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        var totalAmount = scaled(decimalOrNull(extraction, "total_amount"));
        if (totalAmount == null) {
            totalAmount = itemsSum;
        } else if (itemsSum.subtract(totalAmount).abs().compareTo(totalTolerance(items.size())) > 0) {
            log.info("llm_rescue.rejected uf={} chave={} reason=total_mismatch itemsSum={} total={}",
                    uf, LogMasker.chave(chave), itemsSum, totalAmount);
            return null;
        }
        var discount = scaled(decimalOrNull(extraction, "discount_total"));
        if (discount == null || discount.signum() <= 0) {
            discount = null;
        }
        // Parser semantics (see RealDiscountFixtureTest): totalAmount is what was
        // PAID ("Valor a pagar"), items keep gross printed prices. The model
        // extracts the gross total (validated against the items sum above), so
        // subtract the receipt-level discount before persisting.
        var paidTotal = discount == null ? totalAmount : totalAmount.subtract(discount);
        var receipt = ParsedReceipt.builder()
                .chaveAcesso(chave)
                .cnpjEmitente(cnpjFromChave)
                .marketName(textOrNull(extraction, "market_name"))
                .marketAddress(textOrNull(extraction, "market_address"))
                .issuedAt(parseIssuedAt(extraction))
                .totalAmount(paidTotal)
                .discountTotal(discount)
                .sourceUrl(sourceUrl)
                .rawHtml(html)
                .items(items)
                .build();
        log.info("llm_rescue.ok uf={} chave={} items={} total={}",
                uf, LogMasker.chave(chave), items.size(), paidTotal);
        return receipt;
    }

    /** One cent of per-line rounding slack — the model copies printed values, so drift means hallucination. */
    private static BigDecimal totalTolerance(int itemCount) {
        return BigDecimal.valueOf(Math.max(itemCount, 5), 2);
    }

    /**
     * Maps the extracted items, deriving the missing one of unit/total price from
     * the other. Returns null — rejecting the whole rescue — when any item lacks
     * both prices: a partially-priced extraction can't pass the totals check honestly.
     */
    private List<ParsedReceiptItem> buildItems(JsonNode itemsNode) {
        var items = new ArrayList<ParsedReceiptItem>();
        var lineNumber = 1;
        for (var itemNode : itemsNode) {
            var description = textOrNull(itemNode, "description");
            if (description == null) continue;
            var quantity = decimalOrNull(itemNode, "quantity");
            if (quantity == null || quantity.signum() <= 0) {
                quantity = BigDecimal.ONE;
            }
            quantity = quantity.setScale(3, RoundingMode.HALF_UP);
            var unitPrice = scaled(decimalOrNull(itemNode, "unit_price"));
            var totalPrice = scaled(decimalOrNull(itemNode, "total_price"));
            if (totalPrice == null && unitPrice != null) {
                totalPrice = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            }
            if (unitPrice == null && totalPrice != null) {
                unitPrice = totalPrice.divide(quantity, 2, RoundingMode.HALF_UP);
            }
            if (totalPrice == null) {
                return null;
            }
            items.add(ParsedReceiptItem.builder()
                    .lineNumber(lineNumber++)
                    .rawDescription(description)
                    .ean(validEanOrNull(textOrNull(itemNode, "ean")))
                    .quantity(quantity)
                    .unit(unitOrDefault(itemNode))
                    .unitPrice(unitPrice)
                    .totalPrice(totalPrice)
                    .nfcePromoFlag(false)
                    .build());
        }
        return items;
    }

    private static String validEanOrNull(String ean) {
        return ean != null && EAN.matcher(ean).matches() ? ean : null;
    }

    private static String unitOrDefault(JsonNode itemNode) {
        var unit = textOrNull(itemNode, "unit");
        return unit == null ? "UN" : unit.toUpperCase();
    }

    private static LocalDateTime parseIssuedAt(JsonNode extraction) {
        var raw = textOrNull(extraction, "issued_at");
        if (raw == null) return null;
        try {
            return LocalDateTime.parse(raw, ISSUED_AT);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String digitsOrNull(String value) {
        if (value == null) return null;
        var digits = value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimalOrNull(JsonNode node, String field) {
        return node.path(field).isNumber() ? BigDecimal.valueOf(node.path(field).asDouble()) : null;
    }

    private static String textOrNull(JsonNode node, String field) {
        var value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        var text = value.asText().trim();
        return text.isEmpty() || text.equalsIgnoreCase("null") ? null : text;
    }
}
