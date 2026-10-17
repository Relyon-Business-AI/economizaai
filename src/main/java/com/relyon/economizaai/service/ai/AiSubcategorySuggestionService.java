package com.relyon.economizaai.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.relyon.economizaai.service.CategoryResolver;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.extraction.AiFineCategoryClassifier;
import com.relyon.economizaai.service.extraction.AiFineCategoryClassifier.ClassificationInput;
import com.relyon.economizaai.service.extraction.AiFineCategoryClassifier.FineSuggestion;
import com.relyon.economizaai.service.extraction.ProductNameSubcategoryClassifier;
import com.relyon.economizaai.service.extraction.ProductNameSubcategoryClassifier.Confidence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import static com.relyon.economizaai.config.AsyncConfig.AI_SWEEP_EXECUTOR;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The AI subcategory-suggestion sweep: finds products that still lack a fine subcategory
 * (no node, or sitting on a group node), asks the LLM for the best leaf + confidence, and
 * files one PENDING {@link AiFindingType#SUSPECT_CATEGORY} finding per product for the admin
 * to quick-accept (ordered by confidence, "Aceitar todos ≥ X%", inline edit) — nothing is
 * applied until a human approves.
 *
 * <p>Pool-safe by construction: candidate reads, the LLM call, and the finding writes are
 * separate steps — no DB transaction is held across the outbound AI call. Each repository
 * call runs in its own short transaction; the finding payload carries a scalar price/market
 * projection so no lazy entity is touched outside a session.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSubcategorySuggestionService {

    /** One synchronous admin run covers at most this many products (≈ LIMIT/40 LLM calls). Repeatable. */
    private static final int MAX_LIMIT = 200;
    private static final int LLM_BATCH = 40;
    /** Keyword HIGH é bem confiável — entra no topo e cabe no "Aceitar todos ≥ 90%". */
    private static final double KEYWORD_CONFIDENCE = 0.97;
    private static final Locale PT = Locale.forLanguageTag("pt");

    /**
     * Categorias que NÃO têm subcategoria — não há folha pra sugerir, então pula (economiza chamada
     * de IA). Saúde saiu daqui: ganhou subcategorias (medicamentos/suplementos/outros) e agora é varrida.
     */
    private static final Set<ProductCategory> CATEGORIES_WITHOUT_SUBCATEGORY = Set.of(
            ProductCategory.CLEANING, ProductCategory.PERSONAL_CARE, ProductCategory.PET_SUPPLIES);

    /** Quão certa a IA precisa estar conforme o risco da ação (option B — mudar exige mais que preencher). */
    private enum Action { SET, CHANGE_SUBCATEGORY, CHANGE_CATEGORY }

    private final ProductRepository productRepository;
    private final AiFindingRepository findingRepository;
    private final AiFineCategoryClassifier classifier;
    private final CategoryResolver categoryResolver;
    private final ReceiptItemRepository receiptItemRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Limiares assimétricos: preencher subcategoria vazia é baixo risco; trocar uma existente pede
    // mais; mudar a CATEGORIA (azeite→Hortifrúti) pede o máximo. Inline default = valor de teste.
    @Value("${economizaai.ai.subcategory-suggestion.set-min-confidence:0.55}")
    private double setMinConfidence = 0.55;
    @Value("${economizaai.ai.subcategory-suggestion.change-subcategory-min-confidence:0.80}")
    private double changeSubcategoryMinConfidence = 0.80;
    @Value("${economizaai.ai.subcategory-suggestion.change-category-min-confidence:0.90}")
    private double changeCategoryMinConfidence = 0.90;

    // Self-reference so the @Async call goes through the Spring proxy (same pattern as
    // AiSweepService). Defaults to `this` for unit tests.
    @Lazy
    @Autowired
    private AiSubcategorySuggestionService self = this;

    /**
     * Validates the AI is configured, then fires the suggestion run in the BACKGROUND and returns
     * immediately. The run makes several sequential LLM calls (one per 40-product batch), which far
     * exceeds the client/proxy HTTP timeout — so it must NOT run inside the request thread. The FE
     * reloads the suggestion list to see findings appear as they're created.
     */
    public void start(int limit) {
        if (!classifier.isEnabled()) {
            throw new AiGateway.AiUnavailableException("IA não configurada");
        }
        self.runAsync(limit);
    }

    @Async(AI_SWEEP_EXECUTOR)
    public void runAsync(int limit) {
        try {
            generate(limit);
        } catch (RuntimeException ex) {
            log.error("ai.subcat_suggest.failed reason={}", ex.getMessage());
        }
    }

    /**
     * Scans up to {@code limit} subcategory-less products and files one suggestion finding each.
     * HYBRID (como o recategorizador): primeiro o classificador de KEYWORD resolve de graça os
     * casos HIGH-confidence (determinístico, sem LLM — é aqui que pilha/lâmpada/fósforo caem em
     * Utilidades sem a IA errar); só o que ele marca LOW/NONE vai pro LLM. Dedupe contra produtos
     * que já têm sugestão pendente. Para de boa (sem falhar) quando o cap/credito da IA acaba.
     */
    public SuggestOutcome generate(int limit) {
        if (!classifier.isEnabled()) {
            throw new AiGateway.AiUnavailableException("IA não configurada");
        }
        var bounded = Math.min(Math.max(1, limit), MAX_LIMIT);
        var candidates = loadCandidates(bounded);
        if (candidates.isEmpty()) return new SuggestOutcome(0, 0, 0, false);

        var alreadyPending = pendingProductIds();
        var fresh = candidates.stream()
                .filter(product -> !alreadyPending.contains(product.getId()))
                .toList();
        var skippedExisting = candidates.size() - fresh.size();

        // 1) Keyword HIGH (grátis, determinístico) — o resto vai pro LLM. Passa pelo mesmo gating
        // de confiança do LLM; como KEYWORD_CONFIDENCE é altíssimo, ele cobre até mudança de categoria.
        var keywordFindings = new ArrayList<AiFinding>();
        var forLlm = new ArrayList<Product>();
        for (var product : fresh) {
            var keyword = ProductNameSubcategoryClassifier.classify(
                    DescriptionNormalizer.normalize(product.getNormalizedName()));
            if (keyword.confidence() == Confidence.HIGH) {
                var nodeId = categoryResolver.idForSlug(keyword.slug());
                if (nodeId != null) {
                    tryBuildFinding(product, new FineSuggestion(product.getId(), nodeId, keyword.slug(), KEYWORD_CONFIDENCE))
                            .ifPresent(keywordFindings::add);
                }
            } else {
                forLlm.add(product);
            }
        }
        findingRepository.saveAll(keywordFindings);
        var created = keywordFindings.size();

        // 2) LLM pro ambíguo (LOW/NONE), com contexto rico (nota, normalizado, marca, categoria atual).
        var stopped = false;
        for (var start = 0; start < forLlm.size(); start += LLM_BATCH) {
            var batch = forLlm.subList(start, Math.min(start + LLM_BATCH, forLlm.size()));
            List<FineSuggestion> suggestions;
            try {
                suggestions = classifier.suggest(buildClassificationInputs(batch));
            } catch (AiGateway.AiUnavailableException unavailable) {
                log.warn("ai.subcat_suggest stopped reason={}", unavailable.getMessage());
                stopped = true;
                break;
            }
            created += persistBatch(batch, suggestions);
        }
        log.info("ai.subcat_suggest scanned={} keyword={} llm_created={} skipped_existing={} stopped={}",
                fresh.size(), keywordFindings.size(), created - keywordFindings.size(), skippedExisting, stopped);
        return new SuggestOutcome(created, fresh.size(), skippedExisting, stopped);
    }

    private List<Product> loadCandidates(int limit) {
        var groupIds = categoryResolver.groupIds();
        if (groupIds.isEmpty()) return List.of(); // taxonomy not warmed — nothing to sit on
        return productRepository.findWithoutSubcategory(
                groupIds, CategorizationSource.USER, CATEGORIES_WITHOUT_SUBCATEGORY, PageRequest.of(0, limit));
    }

    private Set<UUID> pendingProductIds() {
        return findingRepository.findByStatusAndType(AiFindingStatus.PENDING, AiFindingType.SUSPECT_CATEGORY).stream()
                .map(finding -> payloadProductId(finding.getPayload()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private int persistBatch(List<Product> batch, List<FineSuggestion> suggestions) {
        var byId = batch.stream().collect(Collectors.toMap(Product::getId, product -> product, (first, second) -> first));
        var findings = new ArrayList<AiFinding>();
        for (var suggestion : suggestions) {
            var product = byId.get(suggestion.productId());
            if (product == null) continue;
            tryBuildFinding(product, suggestion).ifPresent(findings::add);
        }
        findingRepository.saveAll(findings);
        return findings.size();
    }

    /**
     * Decide se a sugestão vira finding. Classifica a AÇÃO (preencher subcategoria vazia / trocar
     * uma existente / mudar a categoria) e só passa se a confiança atinge o limiar daquela ação —
     * mudar algo já certo exige mais certeza do que preencher o que está vazio (option B).
     */
    private Optional<AiFinding> tryBuildFinding(Product product, FineSuggestion suggestion) {
        if (suggestion.categoryId().equals(product.getCategoryId())) return Optional.empty(); // já está nessa folha
        var action = classifyAction(product, suggestion.categoryId());
        var threshold = switch (action) {
            case SET -> setMinConfidence;
            case CHANGE_SUBCATEGORY -> changeSubcategoryMinConfidence;
            case CHANGE_CATEGORY -> changeCategoryMinConfidence;
        };
        if (suggestion.confidence() < threshold) return Optional.empty();
        return Optional.of(buildFinding(product, suggestion, action));
    }

    /**
     * SET = produto sem subcategoria (preenche). CHANGE_SUBCATEGORY = já tem folha, troca por outra
     * da MESMA categoria. CHANGE_CATEGORY = a folha sugerida é de OUTRA categoria (azeite→Hortifrúti).
     * Categoria atual = grupo do nó atual (se houver) senão o grupo do enum legado.
     */
    private Action classifyAction(Product product, UUID leafId) {
        var currentGroup = product.getCategoryId() != null
                ? categoryResolver.groupIdOf(product.getCategoryId())
                : categoryResolver.idForLegacyEnum(product.getCategory());
        var suggestedGroup = categoryResolver.groupIdOf(leafId);
        if (currentGroup != null && suggestedGroup != null && !suggestedGroup.equals(currentGroup)) {
            return Action.CHANGE_CATEGORY;
        }
        var hasSubcategory = product.getCategoryId() != null && categoryResolver.isLeafNode(product.getCategoryId());
        return hasSubcategory ? Action.CHANGE_SUBCATEGORY : Action.SET;
    }

    /** Monta o contexto por produto pro LLM: nome na nota, normalizado, marca e categoria/subcategoria atual. */
    private List<ClassificationInput> buildClassificationInputs(List<Product> batch) {
        var rawNames = latestRawNames(batch.stream().map(Product::getId).toList());
        return batch.stream().map(product -> {
            var currentGroup = product.getCategoryId() != null
                    ? categoryResolver.groupIdOf(product.getCategoryId())
                    : categoryResolver.idForLegacyEnum(product.getCategory());
            var currentCategory = currentGroup != null ? categoryResolver.labelFor(currentGroup, PT) : null;
            var currentSubcategory = (product.getCategoryId() != null && categoryResolver.isLeafNode(product.getCategoryId()))
                    ? categoryResolver.labelFor(product.getCategoryId(), PT) : null;
            return new ClassificationInput(product.getId(), rawNames.get(product.getId()),
                    product.getNormalizedName(), product.getBrand(), currentCategory, currentSubcategory);
        }).toList();
    }

    /** productId → descrição ORIGINAL mais recente da nota (driver pode devolver UUID ou texto). */
    private Map<UUID, String> latestRawNames(List<UUID> productIds) {
        var rawNames = new HashMap<UUID, String>();
        if (productIds.isEmpty()) return rawNames;
        for (var row : receiptItemRepository.findLatestRawDescriptions(productIds)) {
            if (row[0] == null || row[1] == null) continue;
            var productId = row[0] instanceof UUID uuid ? uuid : UUID.fromString(row[0].toString());
            rawNames.put(productId, row[1].toString());
        }
        return rawNames;
    }

    private AiFinding buildFinding(Product product, FineSuggestion suggestion, Action action) {
        var suggestedLabel = categoryResolver.labelFor(suggestion.categoryId(), PT);
        var suggestedCategoryLabel = categoryResolver.labelFor(categoryResolver.groupIdOf(suggestion.categoryId()), PT);
        var leafEnum = categoryResolver.legacyEnumForNode(suggestion.categoryId());
        var currentNodeLabel = categoryResolver.labelFor(product.getCategoryId(), PT);
        var productName = product.getGenericName() != null ? product.getGenericName() : product.getNormalizedName();

        var payload = objectMapper.createObjectNode();
        payload.put("productId", product.getId().toString());
        payload.put("productName", productName);
        payload.put("categoryNodeSlug", suggestion.slug());
        payload.put("suggestedLabel", suggestedLabel);
        if (suggestedCategoryLabel != null) payload.put("suggestedCategory", suggestedCategoryLabel);
        payload.put("confidence", suggestion.confidence());
        // Marca a ação pra o admin ver que é uma MUDANÇA (não só preenchimento) — ver option B.
        payload.put("action", action.name());
        payload.put("changesCategory", action == Action.CHANGE_CATEGORY);
        if (leafEnum != null) payload.put("category", leafEnum.name());
        if (product.getCategory() != null) payload.put("currentCategory", product.getCategory().name());
        if (currentNodeLabel != null) payload.put("currentNodeLabel", currentNodeLabel);
        applyLastPurchase(product.getId(), payload);

        var title = "Subcategoria: \"" + product.getNormalizedName() + "\" → " + suggestedLabel;
        var detail = switch (action) {
            case CHANGE_CATEGORY -> "Muda categoria: " + (currentNodeLabel != null ? currentNodeLabel : "—")
                    + " → " + (suggestedCategoryLabel != null ? suggestedCategoryLabel + " › " : "") + suggestedLabel;
            case CHANGE_SUBCATEGORY -> "Troca: " + (currentNodeLabel != null ? currentNodeLabel : "—") + " → " + suggestedLabel;
            case SET -> currentNodeLabel != null ? "Hoje: " + currentNodeLabel : "Sem subcategoria";
        };
        return AiFinding.builder()
                .type(AiFindingType.SUSPECT_CATEGORY)
                .status(AiFindingStatus.PENDING)
                .title(title.length() > 290 ? title.substring(0, 290) : title)
                .detail(detail.length() > 290 ? detail.substring(0, 290) : detail)
                .payload(payload.toString())
                .confidence(BigDecimal.valueOf(suggestion.confidence()).setScale(3, RoundingMode.HALF_UP))
                .activity(AiActivity.FINE_CATEGORY_CLASSIFY)
                .build();
    }

    /** Scalar [unitPrice, marketName] of the product's last purchase — shown on the review row. */
    private void applyLastPurchase(UUID productId, ObjectNode payload) {
        var rows = receiptItemRepository.findLastPriceAndMarket(productId, PageRequest.of(0, 1));
        if (rows.isEmpty()) return;
        var row = rows.get(0);
        if (row[0] instanceof BigDecimal unitPrice) payload.put("lastPrice", unitPrice);
        if (row[1] instanceof String market && !market.isBlank()) payload.put("lastMarket", market);
    }

    private UUID payloadProductId(String payload) {
        try {
            var id = objectMapper.readTree(payload).path("productId").asText("");
            return id.isBlank() ? null : UUID.fromString(id);
        } catch (Exception ignored) {
            return null;
        }
    }

    public record SuggestOutcome(int created, int scanned, int skippedExisting, boolean stopped) {}
}
