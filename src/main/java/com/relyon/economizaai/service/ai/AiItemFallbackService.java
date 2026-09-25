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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import static com.relyon.economizaai.config.AsyncConfig.AI_SWEEP_EXECUTOR;

import java.math.BigDecimal;
import java.util.List;
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
        var unmatched = receiptItemRepository.findUnmatchedByReceiptId(receiptId);
        if (unmatched.isEmpty()) return;
        log.info("ai.item_fallback.start rcpt={} items={}", abbrev(receiptId), unmatched.size());
        for (var item : unmatched) {
            try {
                classifyOne(receiptId, item.getId(), item.getRawDescription());
            } catch (RuntimeException ex) {
                log.warn("ai.item_fallback.failed rcpt={} item={} description='{}' reason={}",
                        abbrev(receiptId), abbrev(item.getId()), item.getRawDescription(), ex.getMessage());
            }
        }
    }

    private void classifyOne(UUID receiptId, UUID itemId, String rawDescription) {
        var raw = aiGateway.complete(AiActivity.ITEM_CLASSIFY, aiGateway.extractorModel(),
                SYSTEM_PROMPT, "Item: \"" + rawDescription + "\"", 200);

        JsonNode result;
        try {
            var text = raw.trim();
            result = objectMapper.readTree(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1));
        } catch (Exception ex) {
            log.warn("ai.item_fallback.parse_failed item={} raw='{}'", abbrev(itemId), raw);
            return;
        }

        var genericName = result.path("genericName").asText("").strip();
        if (genericName.isBlank()) return;

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

        transactionTemplate.execute(txStatus -> {
            var item = receiptItemRepository.findById(itemId).orElse(null);
            if (item == null || item.getProduct() != null) return null;

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
            log.info("ai.item_fallback.matched rcpt={} item={} name='{}' category={} confidence={}",
                    abbrev(receiptId), abbrev(itemId), resolvedGenericName, resolvedCategory, confidence);

            createFindingIfAbsent(rawDescription, resolvedGenericName, resolvedBrand, resolvedCategory, confidence);
            return null;
        });
    }

    private void createFindingIfAbsent(String description, String genericName, String brand,
                                       ProductCategory category, double confidence) {
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
