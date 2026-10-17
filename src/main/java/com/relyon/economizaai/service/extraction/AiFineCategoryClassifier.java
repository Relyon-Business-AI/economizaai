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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LLM subcategory classifier — the smart second pass of the hybrid. The keyword classifier
 * handles the cheap, high-confidence cases for free; everything it marks LOW/NONE (ambiguous
 * flavor names, generic terms, no match) comes here. Claude understands the context keywords
 * can't ("MACARRÃO SABOR PICANHA" é macarrão, "LEITE DE COCO" não é laticínio).
 *
 * <p>Pra ser confiável e não gerar correção manual, o prompt carrega: (1) o <b>gabarito</b> de
 * cada categoria e subcategoria (definição + exemplos, vindos das colunas {@code description_pt}
 * /{@code examples_pt}); (2) por produto, o <b>contexto</b> — nome na nota, nome normalizado,
 * marca e a <b>categoria/subcategoria atual</b>. A saída é restrita aos slugs de SUBCATEGORIA
 * válidos (nunca categoria, nunca slug inventado). Roda no modelo curator (mais forte) e
 * compartilha o cap diário + ledger via {@link AiGateway}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFineCategoryClassifier {

    private static final String SYSTEM =
            "Você é o curador de dados do economizaai, um app brasileiro que lê cupons fiscais (NFC-e) de "
            + "supermercado/farmácia. Você entende abreviações de cupom (REFRIG=refrigerante, FERM=fermento, "
            + "C/=com). Classifique cada produto na SUBCATEGORIA mais específica que couber, usando o gabarito "
            + "(definição + exemplos) de cada categoria e subcategoria. "
            + "Cada produto já tem uma CATEGORIA ATUAL: prefira uma subcategoria DENTRO dela. Só proponha uma "
            + "subcategoria de OUTRA categoria se tiver certeza de que a categoria atual está errada — e reflita "
            + "isso numa confidence alta. "
            + "Se o produto pertence à categoria mas a nenhuma subcategoria específica, use a subcategoria "
            + "'Outros …' daquela categoria em vez de forçar uma errada. "
            + "Se não der para classificar com segurança (ou o produto não casa com nenhuma subcategoria), PULE — "
            + "nunca invente nem chute. "
            + "confidence = sua certeza real (1.0 = óbvio, 0.5 = em dúvida). Responda SEMPRE somente JSON válido, sem markdown.";

    private final AiGateway aiGateway;
    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isEnabled() {
        return aiGateway.isEnabled();
    }

    /** Contexto por produto que o classificador usa pra decidir a subcategoria. */
    public record ClassificationInput(
            UUID productId,
            String receiptName,        // nome cru da nota (pode ser null)
            String normalizedName,
            String brand,              // pode ser null
            String currentCategory,    // rótulo da categoria atual (ou null = sem categoria)
            String currentSubcategory  // rótulo da subcategoria atual (ou null = sem subcategoria)
    ) {}

    /** One LLM subcategory proposal: which leaf node, its slug, and how sure the model is (0..1). */
    public record FineSuggestion(UUID productId, UUID categoryId, String slug, double confidence) {}

    /**
     * Conveniência pro recategorizador: classifica a partir de {@link Product}s (nome normalizado +
     * marca; sem contexto de categoria atual nem nome da nota) e devolve productId → categoryId das
     * que deu pra colocar. Ainda usa o gabarito no prompt.
     */
    public Map<UUID, UUID> classify(List<Product> products) {
        if (products == null || products.isEmpty()) return Map.of();
        var inputs = products.stream()
                .map(product -> new ClassificationInput(product.getId(), null, product.getNormalizedName(),
                        product.getBrand(), null, null))
                .toList();
        return suggest(inputs).stream()
                .collect(Collectors.toMap(FineSuggestion::productId, FineSuggestion::categoryId, (first, second) -> first));
    }

    /**
     * Classifica um lote de produtos na SUBCATEGORIA (folha) mais específica via LLM, mantendo a
     * CONFIANÇA por item pra o chamador ordenar/aplicar limiares. Slugs inválidos/ausentes são
     * pulados — nunca chutados. Uma chamada de IA por invocação.
     */
    public List<FineSuggestion> suggest(List<ClassificationInput> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        var categories = categoryRepository.findAllByActiveTrueOrderBySortOrderAsc();
        // Subcategorias (folhas) são o único alvo válido: um slug de categoria não está no mapa,
        // então é pulado (nunca chutado) igual a um slug desconhecido.
        var idBySlug = categories.stream()
                .filter(category -> category.getParent() != null)
                .collect(Collectors.toMap(Category::getSlug, Category::getId, (first, second) -> first));

        // Gabarito vai no SYSTEM (prefixo estável por taxonomia) → a Anthropic cacheia esse bloco
        // grande e fixo; só os PRODUTOS (que variam por nota) ficam no user. Corta custo no lote e
        // no real-time de volume. Ver AnthropicChatAdapter (cache_control no system).
        var system = SYSTEM + """


                GABARITO (categorias e suas subcategorias — use o SLUG exato da subcategoria):
                %s""".formatted(buildGabarito(categories));
        var userPrompt = """
                Classifique cada produto na subcategoria mais específica.

                PRODUTOS:
                %s

                Responda SOMENTE um array JSON: [{"id": "<id recebido>", "slug": "<slug da subcategoria>", "confidence": <0..1>}].
                Pule (não inclua no array) o que realmente não der para classificar.
                """.formatted(buildProductLines(inputs));

        var text = aiGateway.complete(AiActivity.FINE_CATEGORY_CLASSIFY, aiGateway.curatorModel(),
                system, userPrompt, 4000);

        var suggestions = new ArrayList<FineSuggestion>();
        for (var node : parseArray(text)) {
            var slug = node.path("slug").asText("").trim().toLowerCase();
            var categoryId = idBySlug.get(slug);
            if (categoryId == null) continue; // slug desconhecido ou de categoria → pula, nunca chuta
            UUID productId;
            try {
                productId = UUID.fromString(node.path("id").asText(""));
            } catch (IllegalArgumentException ignored) {
                continue; // id malformado na resposta — pula a linha
            }
            suggestions.add(new FineSuggestion(productId, categoryId, slug, clampConfidence(node.path("confidence").asDouble(0))));
        }
        log.info("ai.fine_classify requested={} placed={}", inputs.size(), suggestions.size());
        return suggestions;
    }

    /** Catálogo agrupado: cada categoria com seu gabarito, e abaixo cada subcategoria com o dela. */
    private String buildGabarito(List<Category> categories) {
        var childrenByParent = new LinkedHashMap<UUID, List<Category>>();
        for (var category : categories) {
            if (category.getParent() != null) {
                childrenByParent.computeIfAbsent(category.getParent().getId(), key -> new ArrayList<>()).add(category);
            }
        }
        var builder = new StringBuilder();
        for (var category : categories) {
            if (category.getParent() != null) continue; // só nível 1 aqui; as folhas vêm aninhadas
            var children = childrenByParent.get(category.getId());
            if (children == null || children.isEmpty()) continue; // categoria sem subcategoria não é alvo da varredura
            builder.append("CATEGORIA ").append(category.getNamePt());
            appendGabarito(builder, category);
            builder.append('\n');
            for (var child : children) {
                builder.append("  - ").append(child.getSlug()).append(" (").append(child.getNamePt()).append(")");
                appendGabarito(builder, child);
                builder.append('\n');
            }
        }
        return builder.toString().strip();
    }

    private void appendGabarito(StringBuilder builder, Category category) {
        if (category.getDescriptionPt() != null && !category.getDescriptionPt().isBlank()) {
            builder.append(" — ").append(category.getDescriptionPt().strip());
        }
        if (category.getExamplesPt() != null && !category.getExamplesPt().isBlank()) {
            builder.append(" Ex: ").append(category.getExamplesPt().strip());
        }
    }

    private String buildProductLines(List<ClassificationInput> inputs) {
        return inputs.stream().map(input -> {
            var line = new StringBuilder("- id=").append(input.productId());
            if (input.receiptName() != null && !input.receiptName().isBlank()) {
                line.append(" | nota: \"").append(input.receiptName().strip()).append('"');
            }
            line.append(" | normalizado: \"").append(input.normalizedName() == null ? "" : input.normalizedName().strip()).append('"');
            if (input.brand() != null && !input.brand().isBlank()) {
                line.append(" | marca: ").append(input.brand().strip());
            }
            line.append(" | categoria atual: ").append(input.currentCategory() == null ? "sem categoria" : input.currentCategory())
                .append(" › ").append(input.currentSubcategory() == null ? "sem subcategoria" : input.currentSubcategory());
            return line.toString();
        }).collect(Collectors.joining("\n"));
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
