package com.relyon.economizaai.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.AiFindingRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import static com.relyon.economizaai.config.AsyncConfig.AI_SWEEP_EXECUTOR;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real-time AI fallback for items the deterministic categorizer could not match.
 * ONE AI call per receipt carries every unmatched item (one {@code LLM_ENRICH} ledger row
 * per call); an item the batched answer doesn't cover simply stays unmatched.
 * Called AFTER the confirm transaction commits — never holds a DB connection across
 * the outbound AI HTTP call (CLAUDE.md: "Never hold a DB transaction across an
 * outbound HTTP call").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiItemFallbackService {

    /** Above this many unmatched items the receipt is split into more than one call (rare). */
    static final int MAX_ITEMS_PER_CALL = 50;
    private static final int BASE_COMPLETION_TOKENS = 200;
    private static final int COMPLETION_TOKENS_PER_ITEM = 120;
    private static final int MAX_COMPLETION_TOKENS = 8000;
    private static final String LEDGER_PROVIDER = "ai";

    private static final String SYSTEM_PROMPT = """
            Você é um classificador de itens de cupom fiscal brasileiro.
            Você recebe uma lista NUMERADA de textos brutos de itens de nota fiscal. Retorne SOMENTE um array JSON
            com UM objeto por item recebido, repetindo o número do item em "n":
            [{"n":1,"genericName":"<nome genérico em português, sem marca>","brand":"<marca ou null>","category":"<GROCERIES|BEVERAGES|PRODUCE|MEAT_DAIRY|BAKERY|CLEANING|PERSONAL_CARE|HEALTH|PET_SUPPLIES|OTHER>","confidence":<0.0-1.0>}]
            Sem texto fora do JSON. Não invente marca. Não pule nem junte itens.
            """;

    private final AiGateway aiGateway;
    private final ProductRepository productRepository;
    private final CategoryResolver categoryResolver;
    private final ReceiptItemRepository receiptItemRepository;
    private final AiFindingRepository findingRepository;
    private final TransactionTemplate transactionTemplate;
    private final PaidApiGuardService paidApiGuard;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Classifies items in the given receipt that were left unmatched by the
     * deterministic categorizer. Called from the controller AFTER confirm() commits.
     * The spend is attributed to {@code userId} (per-user daily cap + ledger).
     * No-op when AI is disabled.
     */
    @Async(AI_SWEEP_EXECUTOR)
    public void applyFallback(UUID receiptId, UUID userId) {
        if (!aiGateway.isEnabled()) return;
        runReceiptFallback(receiptId, userId);
    }

    /**
     * Admin diagnostic: synchronously re-run the fallback over the most recent confirmed
     * receipts that STILL have unmatched items, returning a tally of outcomes. Lets us see
     * WHY the fallback does/doesn't persist without waiting for organic traffic.
     * Un-attributed (no user): only the daily budget applies, not a per-user cap.
     */
    public FallbackTally reprocessUnmatched(int maxReceipts) {
        if (!aiGateway.isEnabled()) return FallbackTally.EMPTY;
        var receiptIds = receiptItemRepository.findReceiptIdsWithUnmatchedItems(
                PageRequest.of(0, Math.max(1, Math.min(maxReceipts, 50))));
        var total = FallbackTally.EMPTY;
        for (var receiptId : receiptIds) {
            total = total.plus(toTally(runReceiptFallback(receiptId, null)));
        }
        log.info("ai.item_fallback.reprocess receipts={} {}", receiptIds.size(), total);
        return total;
    }

    /**
     * Runs the fallback for one receipt's unmatched items — ONE AI call carrying all of
     * them (split only above {@link #MAX_ITEMS_PER_CALL}); returns the per-outcome counts.
     */
    private EnumMap<FallbackOutcome, Integer> runReceiptFallback(UUID receiptId, UUID userId) {
        var tally = new EnumMap<FallbackOutcome, Integer>(FallbackOutcome.class);
        // Skip anything that already has a product BEFORE spending the call.
        var unmatched = receiptItemRepository.findUnmatchedByReceiptId(receiptId).stream()
                .filter(item -> item.getProduct() == null)
                .toList();
        if (unmatched.isEmpty()) return tally;
        log.info("ai.item_fallback.start rcpt={} items={}", abbrev(receiptId), unmatched.size());
        for (var start = 0; start < unmatched.size(); start += MAX_ITEMS_PER_CALL) {
            var batch = unmatched.subList(start, Math.min(start + MAX_ITEMS_PER_CALL, unmatched.size()));
            classifyBatch(receiptId, userId, batch, tally);
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

    /** One AI call (one ledger row) for the batch, then one short transaction per classified item. */
    private void classifyBatch(UUID receiptId, UUID userId, List<ReceiptItem> batch,
                               EnumMap<FallbackOutcome, Integer> tally) {
        if (isBlockedByCostGuard(receiptId, userId)) {
            tally.merge(FallbackOutcome.CALL_ERROR, batch.size(), Integer::sum);
            return;
        }
        String responseText;
        try {
            responseText = aiGateway.complete(AiActivity.ITEM_CLASSIFY, aiGateway.extractorModel(),
                    SYSTEM_PROMPT, numberedItems(batch), completionTokensFor(batch.size()), userId);
        } catch (RuntimeException callFailure) {
            tally.merge(FallbackOutcome.CALL_ERROR, batch.size(), Integer::sum);
            log.warn("ai.item_fallback.failed rcpt={} items={} reason={}",
                    abbrev(receiptId), batch.size(), callFailure.getMessage());
            return;
        }
        paidApiGuard.recordSuccess(userId, PaidApiService.LLM_ENRICH, null, LEDGER_PROVIDER);

        var classificationByNumber = parseClassifications(responseText, batch.size());
        for (var position = 0; position < batch.size(); position++) {
            var item = batch.get(position);
            var classification = classificationByNumber.get(position + 1);
            if (classification == null) {
                // The batched answer has nothing usable for this item — it stays unmatched.
                tally.merge(FallbackOutcome.PARSE_FAILED, 1, Integer::sum);
                continue;
            }
            try {
                tally.merge(applyClassification(receiptId, item.getId(), item.getRawDescription(), classification),
                        1, Integer::sum);
            } catch (RuntimeException persistFailure) {
                tally.merge(FallbackOutcome.CALL_ERROR, 1, Integer::sum);
                log.warn("ai.item_fallback.failed rcpt={} item={} description='{}' reason={}",
                        abbrev(receiptId), abbrev(item.getId()), item.getRawDescription(), persistFailure.getMessage());
            }
        }
        if (classificationByNumber.size() < batch.size()) {
            log.warn("ai.item_fallback.parse_failed rcpt={} items={} parsed={}",
                    abbrev(receiptId), batch.size(), classificationByNumber.size());
        }
    }

    /** Best-effort flow: a spent budget / per-user cap skips the call silently (one log line). */
    private boolean isBlockedByCostGuard(UUID receiptId, UUID userId) {
        try {
            paidApiGuard.assertUnderDailyBudget(PaidApiService.LLM_ENRICH);
            paidApiGuard.assertWithinDailyCap(userId, PaidApiService.LLM_ENRICH);
            return false;
        } catch (PaidApiBudgetExceededException | PaidApiQuotaExceededException guardEx) {
            log.info("ai.item_fallback.skipped rcpt={} reason={}", abbrev(receiptId), guardEx.getClass().getSimpleName());
            return true;
        }
    }

    private static String numberedItems(List<ReceiptItem> batch) {
        var lines = new StringBuilder("Itens:\n");
        for (var position = 0; position < batch.size(); position++) {
            lines.append(position + 1).append(". \"").append(batch.get(position).getRawDescription()).append("\"\n");
        }
        return lines.toString();
    }

    private static int completionTokensFor(int itemCount) {
        return Math.min(MAX_COMPLETION_TOKENS, BASE_COMPLETION_TOKENS + COMPLETION_TOKENS_PER_ITEM * itemCount);
    }

    /**
     * Item number → its classification object. Tolerant by design: every top-level JSON
     * object in the answer is parsed on its own, so a truncated array, a markdown fence or
     * ONE malformed object only costs the items it affects, never the whole batch.
     */
    private Map<Integer, JsonNode> parseClassifications(String responseText, int batchSize) {
        var classificationByNumber = new HashMap<Integer, JsonNode>();
        var objectTexts = jsonObjectsIn(responseText);
        for (var objectText : objectTexts) {
            JsonNode classification;
            try {
                classification = objectMapper.readTree(objectText);
            } catch (Exception malformedObject) {
                continue;
            }
            var itemNumber = classification.path("n").asInt(0);
            if (itemNumber == 0 && batchSize == 1 && objectTexts.size() == 1) {
                itemNumber = 1; // single item answered without its number — unambiguous
            }
            if (itemNumber >= 1 && itemNumber <= batchSize) {
                classificationByNumber.putIfAbsent(itemNumber, classification);
            }
        }
        return classificationByNumber;
    }

    /** Every balanced top-level {@code {...}} block in the text (string-aware; an unclosed tail is dropped). */
    static List<String> jsonObjectsIn(String text) {
        var objects = new ArrayList<String>();
        if (text == null) return objects;
        var depth = 0;
        var objectStart = -1;
        var insideString = false;
        var escaped = false;
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            if (insideString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    insideString = false;
                }
                continue;
            }
            if (character == '"' && depth > 0) {
                insideString = true;
            } else if (character == '{') {
                if (depth == 0) objectStart = index;
                depth++;
            } else if (character == '}' && depth > 0) {
                depth--;
                if (depth == 0) objects.add(text.substring(objectStart, index + 1));
            }
        }
        return objects;
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

    private FallbackOutcome applyClassification(UUID receiptId, UUID itemId, String rawDescription, JsonNode result) {
        var genericName = result.path("genericName").asText("").strip();
        if (genericName.isBlank()) {
            log.warn("ai.item_fallback.blank_name rcpt={} item={}", abbrev(receiptId), abbrev(itemId));
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
            categoryResolver.alignNewProduct(product); // enum↔nó consistentes na criação
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
