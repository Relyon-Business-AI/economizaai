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
            + "C/=com). Classifique cada produto na folha de categoria MAIS específica que couber. "
            + "Itens não-alimentares de uso doméstico (pilha, cola, fita, lâmpada, ferramenta, utensílio de "
            + "cozinha, vela, fósforo, carvão) vão em 'utilidades' ou 'descartaveis' (de Casa e descartáveis). "
            + "Se o produto pertence claramente a uma CATEGORIA mas a nenhuma subcategoria específica, use a "
            + "folha 'Outros …' daquela categoria (ex.: bebida alcoólica mista → bebidas-alcoolicas-outros; "
            + "energético/isotônico → bebidas-outros) em vez de forçar uma folha específica errada. "
            + "Se não se encaixa em nenhuma categoria (ex.: brinquedo, vestuário, papelaria), PULE — não force. "
            + "Seja conservador: se não der para saber, pule. Responda SEMPRE somente JSON válido, sem markdown.";

    private final AiGateway aiGateway;
    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        return aiGateway.isEnabled();
    }

    /** One LLM subcategory proposal: which leaf node, its slug, and how sure the model is (0..1). */
    public record FineSuggestion(UUID productId, UUID categoryId, String slug, double confidence) {}

    /**
     * Classify a batch of products into fine leaf category ids via the LLM.
     * Returns productId → categoryId for the ones it could place (invalid/absent
     * slugs are skipped — never guessed). One AI call per invocation.
     */
    public Map<UUID, UUID> classify(List<Product> products) {
        return suggest(products).stream()
                .collect(Collectors.toMap(FineSuggestion::productId, FineSuggestion::categoryId,
                        (first, second) -> first));
    }

    /**
     * Same LLM pass as {@link #classify}, but keeps the per-item CONFIDENCE so the caller
     * can order/threshold the proposals (the admin quick-accept review). Invalid/absent
     * slugs are skipped — never guessed. One AI call per invocation.
     */
    public List<FineSuggestion> suggest(List<Product> products) {
        if (products == null || products.isEmpty()) return List.of();
        var categories = categoryRepository.findAllByActiveTrueOrderBySortOrderAsc();
        // Leaves only: the LLM must return a SUBCATEGORY, never a group — a group slug is
        // not in the map, so it's skipped (never guessed) just like an unknown slug.
        var idBySlug = categories.stream()
                .filter(category -> category.getParent() != null)
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

                Responda SOMENTE um array JSON: [{"id": "<id recebido>", "slug": "<slug>", "confidence": <0..1>}].
                confidence = sua certeza (1.0 = óbvio, 0.5 = em dúvida). Pule os que realmente não der para classificar.
                """.formatted(lines, categoryList);

        var text = aiGateway.complete(AiActivity.FINE_CATEGORY_CLASSIFY, aiGateway.extractorModel(),
                SYSTEM, userPrompt, 4000);

        var suggestions = new ArrayList<FineSuggestion>();
        for (var node : parseArray(text)) {
            var slug = node.path("slug").asText("").trim().toLowerCase();
            var categoryId = idBySlug.get(slug);
            if (categoryId == null) continue; // unknown slug → skip, never guess
            UUID productId;
            try {
                productId = UUID.fromString(node.path("id").asText(""));
            } catch (IllegalArgumentException ignored) {
                continue; // malformed id in the response — skip that row
            }
            suggestions.add(new FineSuggestion(productId, categoryId, slug, clampConfidence(node.path("confidence").asDouble(0))));
        }
        log.info("ai.fine_classify requested={} placed={}", products.size(), suggestions.size());
        return suggestions;
    }

    private static double clampConfidence(double value) {
        if (value < 0) return 0;
        return Math.min(value, 1);
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
