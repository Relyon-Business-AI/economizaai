package com.relyon.economizaai.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.exception.ItemCorrectionFailedException;
import com.relyon.economizaai.exception.ItemCorrectionUnavailableException;
import com.relyon.economizaai.exception.PaidApiBudgetExceededException;
import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.exception.ReceiptItemNotFoundException;
import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ReceiptItem;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.AiFindingRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.HouseholdProductAliasService;
import com.relyon.economizaai.service.HouseholdProductCategoryOverrideService;
import com.relyon.economizaai.service.ai.AiGateway.AiUnavailableException;
import com.relyon.economizaai.service.canonicalization.CanonicalizationService;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * User-triggered re-classification of a receipt item the user knows the categorizer got wrong.
 * Differs from {@link AiItemFallbackService} in two ways: (1) it runs SYNCHRONOUSLY so the
 * caller can return the corrected receipt immediately; (2) it accepts a free-text user hint
 * that is included in the AI prompt to improve accuracy.
 *
 * <p>Scope of the result — "evidence, not truth": one user's hint must never rewrite the
 * catalog everyone shares. The correction is applied to the user's HOUSEHOLD only (friendly
 * name alias + category override on the item's product), and the same change is filed as a
 * {@link AiFindingType#PRODUCT_CORRECTION} finding; only an admin approving that finding
 * changes the global product.
 *
 * <p>Transaction safety: reads/validation run without a transaction, the outbound AI call is
 * untransacted, then persistence happens in a short TransactionTemplate block — never holds
 * a DB connection across the HTTP call (CLAUDE.md rule).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiItemCorrectionService {

    private static final String SYSTEM_PROMPT = """
            Você é um classificador de itens de cupom fiscal brasileiro.
            Dado o texto bruto de um item de nota fiscal E uma dica do usuário que comprou o produto,
            retorne SOMENTE um JSON:
            {"genericName":"<nome genérico em português, sem marca>","brand":"<marca ou null>","category":"<GROCERIES|BEVERAGES|PRODUCE|MEAT_DAIRY|BAKERY|CLEANING|PERSONAL_CARE|HEALTH|PET_SUPPLIES|OTHER>","confidence":<0.0-1.0>}
            Sem texto fora do JSON. Não invente marca se não tiver certeza.
            """;

    private final AiGateway aiGateway;
    private final ReceiptItemRepository receiptItemRepository;
    private final AiFindingRepository findingRepository;
    private final TransactionTemplate transactionTemplate;
    private final CanonicalizationService canonicalizationService;
    private final HouseholdProductAliasService householdProductAliasService;
    private final HouseholdProductCategoryOverrideService categoryOverrideService;
    private final PaidApiGuardService paidApiGuard;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** What the AI says the item is, given the user's hint. */
    record Classification(String genericName, String brand, ProductCategory category, double confidence) {
        /** Household-facing name: generic name plus the brand when the AI named one. */
        String displayName() {
            return brand == null ? genericName : genericName + " " + brand;
        }
    }

    /**
     * Re-classifies the given item using the AI + the user's hint and applies the result to the
     * household's view of that product. Validates that the item belongs to the given receipt
     * and that the receipt belongs to the given household before touching anything.
     * The AI call counts against {@code userId}'s daily cap and the categorization budget.
     *
     * @throws ItemCorrectionUnavailableException when the AI layer is off, over budget or unreachable
     * @throws ReceiptItemNotFoundException       when the item/receipt ownership check fails
     * @throws ItemCorrectionFailedException      when the AI could not identify the product
     * @throws PaidApiQuotaExceededException      when today's AI-correction limit is reached (429)
     */
    public void correct(UUID receiptId, UUID itemId, UUID householdId, UUID userId, String hint) {
        if (!aiGateway.isEnabled()) {
            throw new ItemCorrectionUnavailableException();
        }
        var rawDescription = requireOwnedItem(receiptId, itemId, householdId).getRawDescription();
        assertCorrectionSpendAllowed(userId);
        log.info("ai.item_correction.start rcpt={} item={}", abbrev(receiptId), abbrev(itemId));

        var classification = classify(itemId, userId, rawDescription, hint);

        transactionTemplate.executeWithoutResult(txStatus ->
                applyToHousehold(receiptId, itemId, rawDescription, hint, classification));
    }

    /**
     * Ownership check without opening a long transaction — a single fetch-join read that loads
     * receipt + household up front, so the proxy traversal doesn't hit a closed session.
     */
    private ReceiptItem requireOwnedItem(UUID receiptId, UUID itemId, UUID householdId) {
        var item = receiptItemRepository.findByIdWithReceiptAndHousehold(itemId).orElse(null);
        if (item == null
                || !item.getReceipt().getId().equals(receiptId)
                || !item.getReceipt().getHousehold().getId().equals(householdId)) {
            throw new ReceiptItemNotFoundException();
        }
        return item;
    }

    /** The AI call — untransacted, must not hold a DB connection. */
    private Classification classify(UUID itemId, UUID userId, String rawDescription, String hint) {
        var userMessage = "Item na nota: \"" + rawDescription + "\"\nDica do usuário: \"" + hint + "\"";
        String raw;
        try {
            raw = aiGateway.complete(AiActivity.ITEM_CORRECTION, aiGateway.extractorModel(),
                    SYSTEM_PROMPT, userMessage, 200, userId);
        } catch (AiUnavailableException unavailable) {
            log.warn("ai.item_correction.unavailable item={} reason={}", abbrev(itemId), unavailable.getMessage());
            throw new ItemCorrectionUnavailableException();
        }
        paidApiGuard.recordSuccess(userId, PaidApiService.LLM_ENRICH, null, "ai");
        return parseClassification(itemId, raw);
    }

    /** Synchronous endpoint: a spent budget / per-user cap becomes a localized 429, before any spend. */
    private void assertCorrectionSpendAllowed(UUID userId) {
        try {
            paidApiGuard.assertUnderDailyBudget(PaidApiService.LLM_ENRICH);
            paidApiGuard.assertWithinDailyCap(userId, PaidApiService.LLM_ENRICH);
        } catch (PaidApiBudgetExceededException | PaidApiQuotaExceededException guardEx) {
            log.info("ai.item_correction.blocked reason={}", guardEx.getClass().getSimpleName());
            throw new PaidApiQuotaExceededException("ai.correction.limit_reached", PaidApiService.LLM_ENRICH.name());
        }
    }

    private Classification parseClassification(UUID itemId, String raw) {
        JsonNode result;
        try {
            var text = raw.trim();
            result = objectMapper.readTree(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1));
        } catch (Exception ex) {
            log.warn("ai.item_correction.parse_failed item={} raw='{}'", abbrev(itemId), raw);
            throw new ItemCorrectionFailedException();
        }
        var genericName = result.path("genericName").asText("").strip();
        if (genericName.isBlank()) {
            log.warn("ai.item_correction.unidentified item={}", abbrev(itemId));
            throw new ItemCorrectionFailedException();
        }
        var brandText = result.path("brand").isNull() ? null : result.path("brand").asText(null);
        var brand = brandText == null || brandText.isBlank() || "null".equalsIgnoreCase(brandText.strip())
                ? null : brandText.strip();
        return new Classification(genericName, brand, parseCategory(result.path("category").asText("OTHER")),
                result.path("confidence").asDouble(0.5));
    }

    private static ProductCategory parseCategory(String categoryText) {
        try {
            return ProductCategory.valueOf(categoryText);
        } catch (IllegalArgumentException ex) {
            return ProductCategory.OTHER;
        }
    }

    /**
     * Household-scoped persistence: anchor the item to a product (deterministic link-or-create,
     * same as a manual category correction), remember the corrected name as the household's
     * alias and the corrected category as the household's override, then queue the global
     * proposal for the admin. The shared {@link Product} row is not modified here.
     */
    private void applyToHousehold(UUID receiptId, UUID itemId, String rawDescription, String hint,
                                  Classification classification) {
        var item = receiptItemRepository.findByIdWithReceiptAndHousehold(itemId).orElse(null);
        if (item == null) return;
        var receipt = item.getReceipt();
        var household = receipt.getHousehold();
        var product = canonicalizationService.linkOrCreateProduct(receipt, item);

        item.setFriendlyDescription(classification.displayName());
        receiptItemRepository.save(item);
        householdProductAliasService.rememberFromItem(household, item);
        categoryOverrideService.setHouseholdOverride(household, product, classification.category());
        log.info("ai.item_correction.household_applied rcpt={} item={} product={} name='{}' category={} confidence={}",
                abbrev(receiptId), abbrev(itemId), product.getId(), classification.displayName(),
                classification.category(), classification.confidence());

        proposeGlobalProductChange(product, rawDescription, hint, classification);
        proposeDictionaryRule(rawDescription, hint, classification);
    }

    /** One pending proposal per product is enough — a second correction doesn't stack another. */
    private void proposeGlobalProductChange(Product product, String description, String userHint,
                                            Classification classification) {
        var productReference = "\"productId\":\"" + product.getId() + "\"";
        if (findingRepository.existsByTypeAndStatusAndPayloadContaining(
                AiFindingType.PRODUCT_CORRECTION, AiFindingStatus.PENDING, productReference)) {
            log.info("ai.item_correction.finding_skipped product={} reason=already_pending", product.getId());
            return;
        }
        var payload = objectMapper.createObjectNode();
        payload.put("productId", product.getId().toString());
        payload.put("genericName", classification.genericName());
        if (classification.brand() != null) payload.put("brand", classification.brand());
        payload.put("category", classification.category().name());
        payload.put("confidence", classification.confidence());
        payload.put("description", description);
        payload.put("userHint", userHint);
        payload.put("source", "USER_CORRECTION");
        payload.put("normalizedName", product.getNormalizedName());
        payload.put("productBrand", product.getBrand() != null ? product.getBrand() : "");
        payload.put("productCategory", product.getCategory() != null ? product.getCategory().name() : "");
        payload.put("reason", "Correção feita por um usuário na revisão da nota; já vale para a casa dele.");

        findingRepository.save(AiFinding.builder()
                .type(AiFindingType.PRODUCT_CORRECTION)
                .status(AiFindingStatus.PENDING)
                .title(truncate("Correção: \"" + product.getNormalizedName() + "\" → " + classification.displayName()))
                .detail("Um usuário corrigiu este produto. A correção já vale para a casa dele; "
                        + "aprove para aplicar nome, marca e categoria ao produto de todos.")
                .payload(payload.toString())
                .confidence(BigDecimal.valueOf(classification.confidence()))
                .activity(AiActivity.ITEM_CORRECTION)
                .build());
        log.info("ai.item_correction.finding_created product={} type=PRODUCT_CORRECTION", product.getId());
    }

    private void proposeDictionaryRule(String description, String userHint, Classification classification) {
        // If a PENDING MISSING_RULE for this exact description already exists, skip — the admin
        // queue already has it. A user correction just enriches what's there.
        var alreadyPending = findingRepository.findByStatus(AiFindingStatus.PENDING).stream()
                .filter(finding -> finding.getType() == AiFindingType.MISSING_RULE)
                .anyMatch(finding -> {
                    try {
                        return objectMapper.readTree(finding.getPayload())
                                .path("description").asText("").equalsIgnoreCase(description);
                    } catch (Exception ex) {
                        return false;
                    }
                });
        if (alreadyPending) return;

        var payload = objectMapper.createObjectNode();
        payload.put("description", description);
        payload.put("keyword", description.split("\\s+")[0].toLowerCase());
        payload.put("genericName", classification.genericName());
        if (classification.brand() != null) payload.put("brand", classification.brand());
        payload.put("category", classification.category().name());
        payload.put("confidence", classification.confidence());
        payload.put("source", "USER_CORRECTION");
        payload.put("userHint", userHint);
        payload.put("reason", "Corrigido pelo usuário durante revisão da nota");

        findingRepository.save(AiFinding.builder()
                .type(AiFindingType.MISSING_RULE)
                .status(AiFindingStatus.PENDING)
                .title(truncate("Regra: \"" + description + "\" → " + classification.genericName()))
                .detail("Corrigido pelo usuário durante revisão. Aprove para virar regra no dicionário.")
                .payload(payload.toString())
                .confidence(BigDecimal.valueOf(classification.confidence()))
                .build());
    }

    private static String truncate(String text) {
        return text.length() > 290 ? text.substring(0, 290) : text;
    }

    private static String abbrev(UUID id) {
        return id.toString().substring(0, 8);
    }
}
