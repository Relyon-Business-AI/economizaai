package com.relyon.economizaai.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.AiFinding;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.ReceiptItem;
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
 * Transaction safety: reads/validation run without a transaction, the outbound AI call is
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
    private final ProductRepository productRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final AiFindingRepository findingRepository;
    private final TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Re-classifies the given item using the AI + the user's hint, then persists the result.
     * Validates that the item belongs to the given receipt and that the receipt belongs to
     * the given household before touching anything.
     *
     * @throws IllegalStateException    when the AI layer is disabled
     * @throws IllegalArgumentException when the item/receipt ownership check fails
     */
    public void correct(UUID receiptId, UUID itemId, UUID householdId, String hint) {
        if (!aiGateway.isEnabled()) {
            throw new IllegalStateException("IA não disponível no momento");
        }

        // Validate ownership without opening a long transaction — plain repository call.
        var item = receiptItemRepository.findById(itemId).orElse(null);
        if (item == null
                || !item.getReceipt().getId().equals(receiptId)
                || !item.getReceipt().getHousehold().getId().equals(householdId)) {
            throw new IllegalArgumentException("Item não encontrado na nota");
        }
        var rawDescription = item.getRawDescription();

        log.info("ai.item_correction.start rcpt={} item={}", abbrev(receiptId), abbrev(itemId));

        // AI call — untransacted, must not hold a DB connection.
        var userMessage = "Item na nota: \"" + rawDescription + "\"\nDica do usuário: \"" + hint + "\"";
        var raw = aiGateway.complete(AiActivity.ITEM_CORRECTION, aiGateway.extractorModel(),
                SYSTEM_PROMPT, userMessage, 200);

        JsonNode result;
        try {
            var text = raw.trim();
            result = objectMapper.readTree(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1));
        } catch (Exception ex) {
            log.warn("ai.item_correction.parse_failed item={} raw='{}'", abbrev(itemId), raw);
            throw new IllegalStateException("IA retornou resposta inválida");
        }

        var genericName = result.path("genericName").asText("").strip();
        if (genericName.isBlank()) {
            throw new IllegalStateException("IA não conseguiu identificar o produto");
        }

        var brandText = result.path("brand").isNull() ? null : result.path("brand").asText(null);
        var resolvedBrand = brandText != null && brandText.isBlank() ? null : brandText;
        var resolvedConfidence = result.path("confidence").asDouble(0.5);

        ProductCategory resolvedCategory;
        try {
            resolvedCategory = ProductCategory.valueOf(result.path("category").asText("OTHER"));
        } catch (IllegalArgumentException ex) {
            resolvedCategory = ProductCategory.OTHER;
        }

        final var resolvedGenericName = genericName;
        final var resolvedCategoryFinal = resolvedCategory;
        final var resolvedBrandFinal = resolvedBrand;

        transactionTemplate.execute(txStatus -> {
            var freshItem = receiptItemRepository.findById(itemId).orElse(null);
            if (freshItem == null) return null;

            if (freshItem.getProduct() != null) {
                // Update the existing product rather than creating a duplicate.
                var product = freshItem.getProduct();
                product.setGenericName(resolvedGenericName);
                product.setGenericNameNorm(DescriptionNormalizer.normalizeOrNull(resolvedGenericName));
                product.setBrand(resolvedBrandFinal);
                product.setCategory(resolvedCategoryFinal);
                product.setCategorizationSource(CategorizationSource.LLM);
                productRepository.save(product);
            } else {
                var product = Product.builder()
                        .normalizedName(resolvedGenericName.toUpperCase())
                        .genericName(resolvedGenericName)
                        .genericNameNorm(DescriptionNormalizer.normalizeOrNull(resolvedGenericName))
                        .brand(resolvedBrandFinal)
                        .category(resolvedCategoryFinal)
                        .categorizationSource(CategorizationSource.LLM)
                        .build();
                productRepository.save(product);
                freshItem.setProduct(product);
            }

            freshItem.setCategoryAtConfirmation(resolvedCategoryFinal);
            receiptItemRepository.save(freshItem);

            log.info("ai.item_correction.matched rcpt={} item={} name='{}' category={} confidence={}",
                    abbrev(receiptId), abbrev(itemId), resolvedGenericName, resolvedCategoryFinal, resolvedConfidence);

            createOrUpdateFinding(rawDescription, hint, resolvedGenericName, resolvedBrandFinal,
                    resolvedCategoryFinal, resolvedConfidence);
            return null;
        });
    }

    private void createOrUpdateFinding(String description, String userHint, String genericName,
                                       String brand, ProductCategory category, double confidence) {
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
        payload.put("genericName", genericName);
        if (brand != null) payload.put("brand", brand);
        payload.put("category", category.name());
        payload.put("confidence", confidence);
        payload.put("source", "USER_CORRECTION");
        payload.put("userHint", userHint);
        payload.put("reason", "Corrigido pelo usuário durante revisão da nota");

        findingRepository.save(AiFinding.builder()
                .type(AiFindingType.MISSING_RULE)
                .status(AiFindingStatus.PENDING)
                .title("Regra: \"" + description + "\" → " + genericName)
                .detail("Corrigido pelo usuário durante revisão. Aprove para virar regra no dicionário.")
                .payload(payload.toString())
                .confidence(BigDecimal.valueOf(confidence))
                .build());
    }

    private static String abbrev(UUID id) {
        return id.toString().substring(0, 8);
    }
}
