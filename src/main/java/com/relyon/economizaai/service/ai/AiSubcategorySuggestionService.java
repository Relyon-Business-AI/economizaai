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
import com.relyon.economizaai.service.extraction.AiFineCategoryClassifier.FineSuggestion;
import com.relyon.economizaai.service.extraction.ProductNameSubcategoryClassifier;
import com.relyon.economizaai.service.extraction.ProductNameSubcategoryClassifier.Confidence;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import static com.relyon.economizaai.config.AsyncConfig.AI_SWEEP_EXECUTOR;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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

    /** Non-food groups have no finer leaf to suggest — skip them (mirrors the recategorizer). */
    private static final Set<ProductCategory> NON_FOOD = Set.of(
            ProductCategory.CLEANING, ProductCategory.PERSONAL_CARE,
            ProductCategory.HEALTH, ProductCategory.PET_SUPPLIES);

    private final ProductRepository productRepository;
    private final AiFindingRepository findingRepository;
    private final AiFineCategoryClassifier classifier;
    private final CategoryResolver categoryResolver;
    private final ReceiptItemRepository receiptItemRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

        // 1) Keyword HIGH (grátis, determinístico) — o resto vai pro LLM.
        var keywordFindings = new ArrayList<AiFinding>();
        var forLlm = new ArrayList<Product>();
        for (var product : fresh) {
            var keyword = ProductNameSubcategoryClassifier.classify(
                    DescriptionNormalizer.normalize(product.getNormalizedName()));
            if (keyword.confidence() == Confidence.HIGH) {
                var nodeId = categoryResolver.idForSlug(keyword.slug());
                // Só sugere se for uma subcategoria DENTRO da categoria atual (nunca muda de categoria).
                if (nodeId != null && !nodeId.equals(product.getCategoryId()) && fitsCurrentCategory(product, nodeId)) {
                    keywordFindings.add(buildFinding(product, new FineSuggestion(product.getId(), nodeId, keyword.slug(), KEYWORD_CONFIDENCE)));
                }
                // keyword HIGH de OUTRA categoria (ou já é a folha) → não sugere nem manda pro LLM
            } else {
                forLlm.add(product);
            }
        }
        findingRepository.saveAll(keywordFindings);
        var created = keywordFindings.size();

        // 2) LLM pro ambíguo (LOW/NONE).
        var stopped = false;
        for (var start = 0; start < forLlm.size(); start += LLM_BATCH) {
            var batch = forLlm.subList(start, Math.min(start + LLM_BATCH, forLlm.size()));
            List<FineSuggestion> suggestions;
            try {
                suggestions = classifier.suggest(batch);
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
                groupIds, CategorizationSource.USER, NON_FOOD, PageRequest.of(0, limit));
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
            if (suggestion.categoryId().equals(product.getCategoryId())) continue; // already on that leaf
            // Nunca muda de categoria: descarta sugestão cuja folha é de OUTRA categoria (ex.:
            // azeite/Mercearia → folha de Hortifrúti). Uma varredura de subcategoria só refina.
            if (!fitsCurrentCategory(product, suggestion.categoryId())) continue;
            findings.add(buildFinding(product, suggestion));
        }
        findingRepository.saveAll(findings);
        return findings.size();
    }

    /**
     * True se a folha sugerida está DENTRO da categoria atual do produto — a varredura de
     * subcategoria só refina, nunca muda a categoria. Categoria atual = grupo do nó atual (se
     * houver) senão o grupo do enum legado. Sem categoria conhecida → qualquer folha serve.
     */
    private boolean fitsCurrentCategory(Product product, UUID leafId) {
        var currentGroup = product.getCategoryId() != null
                ? categoryResolver.groupIdOf(product.getCategoryId())
                : categoryResolver.idForLegacyEnum(product.getCategory());
        if (currentGroup == null) return true;
        return currentGroup.equals(categoryResolver.groupIdOf(leafId));
    }

    private AiFinding buildFinding(Product product, FineSuggestion suggestion) {
        var suggestedLabel = categoryResolver.labelFor(suggestion.categoryId(), PT);
        var leafEnum = categoryResolver.legacyEnumForNode(suggestion.categoryId());
        var currentNodeLabel = categoryResolver.labelFor(product.getCategoryId(), PT);
        var productName = product.getGenericName() != null ? product.getGenericName() : product.getNormalizedName();

        var payload = objectMapper.createObjectNode();
        payload.put("productId", product.getId().toString());
        payload.put("productName", productName);
        payload.put("categoryNodeSlug", suggestion.slug());
        payload.put("suggestedLabel", suggestedLabel);
        payload.put("confidence", suggestion.confidence());
        if (leafEnum != null) payload.put("category", leafEnum.name());
        if (product.getCategory() != null) payload.put("currentCategory", product.getCategory().name());
        if (currentNodeLabel != null) payload.put("currentNodeLabel", currentNodeLabel);
        applyLastPurchase(product.getId(), payload);

        var title = "Subcategoria: \"" + product.getNormalizedName() + "\" → " + suggestedLabel;
        return AiFinding.builder()
                .type(AiFindingType.SUSPECT_CATEGORY)
                .status(AiFindingStatus.PENDING)
                .title(title.length() > 290 ? title.substring(0, 290) : title)
                .detail(currentNodeLabel != null ? "Hoje: " + currentNodeLabel : "Sem subcategoria")
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
