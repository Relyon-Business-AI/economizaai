package com.relyon.economizaai.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.AiFindingRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import static com.relyon.economizaai.config.AsyncConfig.AI_SWEEP_EXECUTOR;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.UUID;

/**
 * Real-time AI fallback for items the deterministic categorizer could not match.
 * Called AFTER the confirm transaction commits — never holds a DB connection across
 * the outbound AI HTTP call (CLAUDE.md: "Never hold a DB transaction across an
 * outbound HTTP call").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiItemFallbackService {

    private static final String SYSTEM_PROMPT = """
            Você é um classificador de itens de cupom fiscal brasileiro.
            Dado o texto bruto de um item de nota fiscal, retorne SOMENTE um JSON:
            {"genericName":"<nome genérico em português, sem marca>","brand":"<marca ou null>","category":"<GROCERIES|BEVERAGES|PRODUCE|MEAT_DAIRY|BAKERY|CLEANING|PERSONAL_CARE|HEALTH|PET_SUPPLIES|OTHER>","confidence":<0.0-1.0>}
            Sem texto fora do JSON. Não invente marca.
            """;

    private final AiGateway aiGateway;
    private final ProductRepository productRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final AiFindingRepository findingRepository;
    private final TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Classifies items in the given receipt that were left unmatched by the
     * deterministic categorizer. Called from the controller AFTER confirm() commits.
     * No-op when AI is disabled.
     */
    @Async(AI_SWEEP_EXECUTOR)
    public void applyFallback(UUID receiptId) {
        if (!aiGateway.isEnabled()) return;
        runReceiptFallback(receiptId);
    }

    /**
     * Admin diagnostic: synchronously re-run the fallback over the most recent confirmed
     * receipts that STILL have unmatched items, returning a tally of outcomes. Lets us see
     * WHY the fallback does/doesn't persist without waiting for organic traffic.
     */
    public FallbackTally reprocessUnmatched(int maxReceipts) {
        if (!aiGateway.isEnabled()) return FallbackTally.EMPTY;
        var receiptIds = receiptItemRepository.findReceiptIdsWithUnmatchedItems(
                PageRequest.of(0, Math.max(1, Math.min(maxReceipts, 50))));
        var total = FallbackTally.EMPTY;
        for (var receiptId : receiptIds) {
            total = total.plus(toTally(runReceiptFallback(receiptId)));
        }
        log.info("ai.item_fallback.reprocess receipts={} {}", receiptIds.size(), total);
        return total;
    }

    /** Runs the fallback for one receipt's unmatched items; returns the per-outcome counts. */
    private EnumMap<FallbackOutcome, Integer> runReceiptFallback(UUID receiptId) {
        var tally = new EnumMap<FallbackOutcome, Integer>(FallbackOutcome.class);
        var unmatched = receiptItemRepository.findUnmatchedByReceiptId(receiptId);
        if (unmatched.isEmpty()) return tally;
        log.info("ai.item_fallback.start rcpt={} items={}", abbrev(receiptId), unmatched.size());
        for (var item : unmatched) {
            try {
                tally.merge(classifyOne(receiptId, item.getId(), item.getRawDescription()), 1, Integer::sum);
            } catch (RuntimeException ex) {
                tally.merge(FallbackOutcome.CALL_ERROR, 1, Integer::sum);
                log.warn("ai.item_fallback.failed rcpt={} item={} description='{}' reason={}",
                        abbrev(receiptId), abbrev(item.getId()), item.getRawDescription(), ex.getMessage());
            }
        }
        // One summary line per receipt so a single grep tells us WHY the fallback did/didn't
        // persist — the discard reasons used to be silent (the 0-LLM-products mystery).
        log.info("ai.item_fallback.done rcpt={} items={} matched={} already_matched={} parse_failed={} blank_name={} gone={} call_error={}",
                abbrev(receiptId), unmatched.size(),
                tally.getOrDefault(FallbackOutcome.MATCHED, 0),
                tally.getOrDefault(FallbackOutcome.ALREADY_MATCHED, 0),
                tally.getOrDefault(FallbackOutcome.PARSE_FAILED, 0),
                tally.getOrDefault(FallbackOutcome.BLANK_NAME, 0),
                tally.getOrDefault(FallbackOutcome.GONE, 0),
                tally.getOrDefault(FallbackOutcome.CALL_ERROR, 0));
        return tally;
    }

    private static FallbackTally toTally(EnumMap<FallbackOutcome, Integer> counts) {
        return new FallbackTally(1,
                counts.getOrDefault(FallbackOutcome.MATCHED, 0),
                counts.getOrDefault(FallbackOutcome.ALREADY_MATCHED, 0),
                counts.getOrDefault(FallbackOutcome.PARSE_FAILED, 0),
                counts.getOrDefault(FallbackOutcome.BLANK_NAME, 0),
                counts.getOrDefault(FallbackOutcome.GONE, 0),
                counts.getOrDefault(FallbackOutcome.CALL_ERROR, 0));
    }

    /** Outcome of one fallback classification — tallied per receipt for observability. */
    private enum FallbackOutcome { MATCHED, ALREADY_MATCHED, PARSE_FAILED, BLANK_NAME, GONE, CALL_ERROR }

    public record FallbackTally(int receipts, int matched, int alreadyMatched, int parseFailed, int blankName, int gone, int callError) {
        static final FallbackTally EMPTY = new FallbackTally(0, 0, 0, 0, 0, 0, 0);
        FallbackTally plus(FallbackTally other) {
            return new FallbackTally(receipts + other.receipts, matched + other.matched, alreadyMatched + other.alreadyMatched,
                    parseFailed + other.parseFailed, blankName + other.blankName, gone + other.gone, callError + other.callError);
        }
    }

    private FallbackOutcome classifyOne(UUID receiptId, UUID itemId, String rawDescription) {
        // Re-check BEFORE spending an LLM call: if the deterministic cascade (or a concurrent
        // path) already gave this item a product, there's nothing to do — and we avoid paying
        // for a call whose result we'd just discard.
        var existing = receiptItemRepository.findById(itemId).orElse(null);
        if (existing == null) return FallbackOutcome.GONE;
        if (existing.getProduct() != null) {
            log.info("ai.item_fallback.already_matched rcpt={} item={} stage=pre_llm", abbrev(receiptId), abbrev(itemId));
            return FallbackOutcome.ALREADY_MATCHED;
        }

        var raw = aiGateway.complete(AiActivity.ITEM_CLASSIFY, aiGateway.extractorModel(),
                SYSTEM_PROMPT, "Item: \"" + rawDescription + "\"", 200);

        JsonNode result;
        try {
            var text = raw.trim();
            var start = text.indexOf('{');
            var end = text.lastIndexOf('}');
            if (start < 0 || end <= start) throw new IllegalStateException("no JSON object in response");
            result = objectMapper.readTree(text.substring(start, end + 1));
        } catch (Exception ex) {
            log.warn("ai.item_fallback.parse_failed rcpt={} item={} raw='{}'", abbrev(receiptId), abbrev(itemId), raw);
            return FallbackOutcome.PARSE_FAILED;
        }

        var genericName = result.path("genericName").asText("").strip();
        if (genericName.isBlank()) {
            log.warn("ai.item_fallback.blank_name rcpt={} item={} raw='{}'", abbrev(receiptId), abbrev(itemId), raw);
            return FallbackOutcome.BLANK_NAME;
        }

        var brandText = result.path("brand").isNull() ? null : result.path("brand").asText(null);
        var brand = brandText != null && brandText.isBlank() ? null : brandText;
        var confidence = result.path("confidence").asDouble(0.5);

        ProductCategory category;
        try {
            category = ProductCategory.valueOf(result.path("category").asText("OTHER"));
        } catch (IllegalArgumentException ex) {
            category = ProductCategory.OTHER;
        }
        final var resolvedCategory = category;
        final var resolvedGenericName = genericName;
        final var resolvedBrand = brand;

        var outcome = transactionTemplate.execute(txStatus -> {
            var item = receiptItemRepository.findById(itemId).orElse(null);
            if (item == null) return FallbackOutcome.GONE;
            if (item.getProduct() != null) return FallbackOutcome.ALREADY_MATCHED;

            var product = Product.builder()
                    .normalizedName(resolvedGenericName.toUpperCase())
                    .genericName(resolvedGenericName)
                    .genericNameNorm(DescriptionNormalizer.normalizeOrNull(resolvedGenericName))
                    .brand(resolvedBrand)
                    .category(resolvedCategory)
                    .categorizationSource(CategorizationSource.LLM)
                    .build();
            productRepository.save(product);

            item.setProduct(product);
            if (resolvedCategory != null) {
                item.setCategoryAtConfirmation(resolvedCategory);
            }
            receiptItemRepository.save(item);
            return FallbackOutcome.MATCHED;
        });

        if (outcome == FallbackOutcome.ALREADY_MATCHED) {
            log.info("ai.item_fallback.already_matched rcpt={} item={} stage=persist", abbrev(receiptId), abbrev(itemId));
            return outcome;
        }
        if (outcome != FallbackOutcome.MATCHED) return outcome; // GONE

        log.info("ai.item_fallback.matched rcpt={} item={} name='{}' category={} confidence={}",
                abbrev(receiptId), abbrev(itemId), resolvedGenericName, resolvedCategory, confidence);

        // The finding is only the admin suggestion card — its failure must never
        // undo the classification, which is already committed above.
        try {
            createFindingIfAbsent(rawDescription, resolvedGenericName, resolvedBrand, resolvedCategory, confidence);
        } catch (RuntimeException ex) {
            log.warn("ai.item_fallback.finding_failed rcpt={} item={} description='{}' reason={}",
                    abbrev(receiptId), abbrev(itemId), rawDescription, ex.getMessage());
        }
        return FallbackOutcome.MATCHED;
    }

    private void createFindingIfAbsent(String description, String genericName, String brand,
                                       ProductCategory category, double confidence) {
        var titlePrefix = "Regra: \"" + description + "\" →";
        var alreadyPending = findingRepository.existsByTypeAndStatusAndTitleStartingWith(
                AiFindingType.MISSING_RULE, AiFindingStatus.PENDING, titlePrefix);
        if (alreadyPending) return;

        var payload = objectMapper.createObjectNode();
        payload.put("description", description);
        payload.put("keyword", description.split("\\s+")[0].toLowerCase());
        payload.put("genericName", genericName);
        if (brand != null) payload.put("brand", brand);
        payload.put("category", category.name());
        payload.put("confidence", confidence);
        payload.put("reason", "Classificado pela IA durante scan (fallback determinístico)");

        findingRepository.save(AiFinding.builder()
                .type(AiFindingType.MISSING_RULE)
                .status(AiFindingStatus.PENDING)
                .title("Regra: \"" + description + "\" → " + genericName)
                .detail("Sugerido pela IA como fallback de scan. Aprove para virar regra no dicionário.")
                .payload(payload.toString())
                .confidence(BigDecimal.valueOf(confidence))
                .build());
    }

    private static String abbrev(UUID id) {
        return id.toString().substring(0, 8);
    }
}
