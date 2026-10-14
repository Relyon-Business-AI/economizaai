package com.relyon.economizaai.service.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.Category;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.repository.CategoryRepository;
import com.relyon.economizaai.service.ai.AiGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LLM leaf classifier — the smart second pass of the hybrid. The keyword classifier
 * handles the cheap, high-confidence cases for free; everything it marks LOW/NONE
 * (ambiguous flavor names, generic terms, no match) comes here. Claude understands
 * the context keywords can't ("MACARRÃO SABOR PICANHA" is instant noodles, "LEITE DE
 * COCO" isn't dairy). Routed through {@link AiGateway} so it shares the daily cap +
 * cost ledger. Output is constrained to the valid {@code categories.slug} list.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFineCategoryClassifier {

    private static final String SYSTEM =
            "Você é o curador de dados do economizaai, um app brasileiro que lê cupons fiscais (NFC-e) de "
            + "supermercado/farmácia. Você entende abreviações de cupom (REFRIG=refrigerante, FERM=fermento, "
            + "C/=com). Classifique cada produto na folha de categoria MAIS específica que couber. Seja "
            + "conservador: se não der para saber, pule. Responda SEMPRE somente JSON válido, sem markdown.";

    private final AiGateway aiGateway;
    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        return aiGateway.isEnabled();
    }

    /**
     * Classify a batch of products into fine leaf category ids via the LLM.
     * Returns productId → categoryId for the ones it could place (invalid/absent
     * slugs are skipped — never guessed). One AI call per invocation.
     */
    public Map<UUID, UUID> classify(List<Product> products) {
        if (products == null || products.isEmpty()) return Map.of();
        var categories = categoryRepository.findAllByActiveTrueOrderBySortOrderAsc();
        var idBySlug = categories.stream()
                .collect(Collectors.toMap(Category::getSlug, Category::getId, (first, second) -> first));
        var categoryList = categories.stream()
                .filter(category -> category.getParent() != null) // leaves are the specific target
                .map(category -> category.getSlug() + " (" + category.getNamePt() + ")")
                .collect(Collectors.joining(", "));
        var lines = products.stream()
                .map(product -> "- id=" + product.getId() + " \"" + product.getNormalizedName() + "\"")
                .collect(Collectors.joining("\n"));
        var userPrompt = """
                Classifique cada produto na folha mais específica:
                %s

                Folhas válidas (use o SLUG exato antes do parêntese): %s

                Responda SOMENTE um array JSON: [{"id": "<id recebido>", "slug": "<slug>"}].
                Pule os que realmente não der para classificar.
                """.formatted(lines, categoryList);

        var text = aiGateway.complete(AiActivity.FINE_CATEGORY_CLASSIFY, aiGateway.extractorModel(),
                SYSTEM, userPrompt, 4000);

        var result = new HashMap<UUID, UUID>();
        for (var node : parseArray(text)) {
            var categoryId = idBySlug.get(node.path("slug").asText("").trim().toLowerCase());
            if (categoryId == null) continue; // unknown slug → skip, never guess
            try {
                result.put(UUID.fromString(node.path("id").asText("")), categoryId);
            } catch (IllegalArgumentException ignored) {
                // malformed id in the response — skip that row
            }
        }
        log.info("ai.fine_classify requested={} placed={}", products.size(), result.size());
        return result;
    }

    // Defensive parse: strip markdown fences, isolate the array, tolerate errors.
    private List<JsonNode> parseArray(String text) {
        if (text == null) return List.of();
        var cleaned = text.trim().replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        var start = cleaned.indexOf('[');
        var end = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        try {
            var root = objectMapper.readTree(cleaned.substring(start, end + 1));
            if (!root.isArray()) return List.of();
            var nodes = new ArrayList<JsonNode>();
            root.forEach(nodes::add);
            return nodes;
        } catch (Exception ex) {
            log.warn("ai.fine_classify.parse_failed reason={}", ex.getMessage());
            return List.of();
        }
    }
}
