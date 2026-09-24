package com.relyon.economizaai.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.relyon.economizaai.model.LlmDisagreement;
import com.relyon.economizaai.model.CuratedDictionaryEntry;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import com.relyon.economizaai.repository.LlmDisagreementRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.repository.ReceiptItemRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.extraction.DictionaryClassifier;
import com.relyon.economizaai.service.extraction.LearnableTokenFilter;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The "LLM as teacher" layer: products the strong (free) layers left wanting —
 * unmatched category, missing brand or pack size — are enriched in batches by
 * a cheap LLM, and each answer may also write a reusable keyword rule back
 * into the curated dictionary so the free layer grows with every paid call.
 *
 * <p>Ordering of truth (enforced here, never violated):
 * {@code USER > CONSENSUS > DICTIONARY/MERCHANT > LLM > LEARNED_DICTIONARY/ML/NONE}.
 * When the LLM disagrees with a higher-ranked source the suggestion is recorded
 * as a {@link LlmDisagreement} for admin review instead of applied.
 *
 * <p>Cost control: metered as {@link PaidApiService#LLM_ENRICH} through the
 * paid-API guard (global daily cap + budget + ledger); attempts per product are
 * capped so a hopeless product can't burn calls forever. The whole layer is a
 * config switch ({@code economizaai.llm.enrichment.enabled}) so it can be
 * disabled or later gated to premium tiers.
 */
@Slf4j
@Service
public class LlmEnrichmentService {

    /** Sources the LLM may overwrite (it ranks above these). */
    private static final Set<CategorizationSource> OVERWRITABLE_SOURCES = EnumSet.of(
            CategorizationSource.NONE, CategorizationSource.ML,
            CategorizationSource.LEARNED_DICTIONARY, CategorizationSource.LLM);
    private static final BigDecimal MIN_PLAUSIBLE_PRICE_PER_KG = new BigDecimal("0.5");
    private static final BigDecimal MAX_PLAUSIBLE_PRICE_PER_KG = new BigDecimal("1000");

    private final ProductRepository productRepository;
    private final ReceiptItemRepository receiptItemRepository;
    private final CuratedDictionaryEntryRepository curatedRepository;
    private final LlmDisagreementRepository disagreementRepository;
    private final DictionaryClassifier dictionaryClassifier;
    private final OpenAiClient openAiClient;
    private final PaidApiGuardService paidApiGuard;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final int batchSize;
    private final int maxAttempts;
    private final BigDecimal confidenceThreshold;

    public LlmEnrichmentService(ProductRepository productRepository,
                                ReceiptItemRepository receiptItemRepository,
                                CuratedDictionaryEntryRepository curatedRepository,
                                LlmDisagreementRepository disagreementRepository,
                                DictionaryClassifier dictionaryClassifier,
                                OpenAiClient openAiClient,
                                PaidApiGuardService paidApiGuard,
                                TransactionTemplate transactionTemplate,
                                @Value("${economizaai.llm.enrichment.enabled:true}") boolean enabled,
                                @Value("${economizaai.llm.enrichment.batch-size:25}") int batchSize,
                                @Value("${economizaai.llm.enrichment.max-attempts:3}") int maxAttempts,
                                @Value("${economizaai.llm.enrichment.confidence-threshold:0.7}") double confidenceThreshold) {
        this.productRepository = productRepository;
        this.receiptItemRepository = receiptItemRepository;
        this.curatedRepository = curatedRepository;
        this.disagreementRepository = disagreementRepository;
        this.dictionaryClassifier = dictionaryClassifier;
        this.openAiClient = openAiClient;
        this.paidApiGuard = paidApiGuard;
        this.transactionTemplate = transactionTemplate;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.confidenceThreshold = BigDecimal.valueOf(confidenceThreshold);
    }

    @Scheduled(fixedDelayString = "${economizaai.llm.enrichment.interval-ms:600000}",
               initialDelayString = "${economizaai.llm.enrichment.initial-delay-ms:90000}")
    public void enrichPendingProducts() {
        if (!enabled || !openAiClient.isConfigured()) return;
        var candidates = productRepository.findEnrichmentCandidates(maxAttempts,
                PageRequest.of(0, batchSize));
        if (candidates.isEmpty()) return;
        try {
            paidApiGuard.assertUnderGlobalBudget();
            paidApiGuard.assertWithinServiceDailyCap(PaidApiService.LLM_ENRICH);
        } catch (RuntimeException ex) {
            log.warn("llm_enrich.skipped reason=cost_guard {}", ex.getClass().getSimpleName());
            return;
        }
        log.info("llm_enrich.batch.start products={}", candidates.size());
        JsonNode response;
        try {
            // HTTP untransacted — never pin a connection across the LLM call.
            response = openAiClient.completeJson(systemPrompt(), userPrompt(candidates), 4000);
            paidApiGuard.recordSuccess(null, PaidApiService.LLM_ENRICH, null, "openai");
        } catch (LlmCallFailedException ex) {
            paidApiGuard.recordFailure(null, PaidApiService.LLM_ENRICH, null, "openai");
            log.warn("llm_enrich.call_failed reason={}", ex.getMessage());
            bumpAttempts(candidates);
            return;
        }
        var applied = applyBatch(candidates, response);
        log.info("llm_enrich.batch.done products={} applied={}", candidates.size(), applied);
    }

    // ---------- prompt ----------

    private String systemPrompt() {
        var categories = Arrays.stream(ProductCategory.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
        return """
                Voce e um especialista em produtos de supermercado brasileiros. Recebera uma lista de \
                descricoes de itens de cupom fiscal (NFC-e), frequentemente abreviadas e truncadas. \
                Para cada item, responda com categoria, marca, nome generico e tamanho da embalagem.
                Categorias validas (use exatamente): %s.
                Regras: marca null quando desconhecida ou produto sem marca (hortifruti a granel); \
                pack_size/pack_unit null quando nao presentes na descricao (nunca invente); \
                pack_unit em minusculas (g, kg, ml, l, un); confidence entre 0 e 1 refletindo sua certeza; \
                dictionary_keyword: UMA palavra-chave minuscula presente na descricao que identifica o \
                produto de forma generalizavel (ex: "azeit" para azeites), ou null se nenhuma for confiavel.
                Responda APENAS o JSON: {"products": [{"id": <id recebido>, "category": "...", \
                "brand": "...|null", "generic_name": "...", "pack_size": <numero|null>, \
                "pack_unit": "...|null", "confidence": <0-1>, "dictionary_keyword": "...|null"}]}""".formatted(categories);
    }

    private String userPrompt(List<Product> products) {
        var lines = new StringBuilder("Itens:\n");
        for (var index = 0; index < products.size(); index++) {
            var product = products.get(index);
            lines.append(index).append(": ").append(product.getNormalizedName());
            if (product.getEan() != null) lines.append(" (EAN ").append(product.getEan()).append(')');
            lines.append('\n');
        }
        return lines.toString();
    }

    // ---------- apply ----------

    private int applyBatch(List<Product> products, JsonNode response) {
        var applied = 0;
        var results = response.path("products");
        for (var result : results) {
            var index = result.path("id").asInt(-1);
            if (index < 0 || index >= products.size()) continue;
            var product = products.get(index);
            var appliedOne = transactionTemplate.execute(txStatus -> applyOne(product.getId(), result));
            if (Boolean.TRUE.equals(appliedOne)) applied++;
        }
        // products the model skipped still consume an attempt
        transactionTemplate.executeWithoutResult(txStatus -> {
            for (var product : products) {
                productRepository.findById(product.getId()).ifPresent(fresh -> {
                    if (fresh.getLlmEnrichedAt() == null) {
                        fresh.setLlmEnrichmentAttempts(fresh.getLlmEnrichmentAttempts() + 1);
                        productRepository.save(fresh);
                    }
                });
            }
        });
        if (applied > 0) dictionaryClassifier.reloadCuratedEntries();
        return applied;
    }

    private boolean applyOne(UUID productId, JsonNode result) {
        var product = productRepository.findById(productId).orElse(null);
        if (product == null) return false;
        var confidence = BigDecimal.valueOf(result.path("confidence").asDouble(0));
        if (confidence.compareTo(confidenceThreshold) < 0) {
            log.debug("llm_enrich.low_confidence product={} confidence={}", productId, confidence);
            return false;
        }
        var changed = applyCategory(product, result, confidence);
        changed |= applyBrandAndName(product, result);
        changed |= applyPackSize(product, result);
        maybeWriteBackDictionaryRule(product, result);
        if (changed) {
            product.setLlmEnrichedAt(LocalDateTime.now());
            log.info("item.enriched_by_llm product={} category={} brand={} pack={}{}",
                    productId, product.getCategory(), product.getBrand(),
                    product.getPackSize(), product.getPackUnit() == null ? "" : product.getPackUnit());
        }
        productRepository.save(product);
        return changed;
    }

    private boolean applyCategory(Product product, JsonNode result, BigDecimal confidence) {
        var suggested = parseCategory(result.path("category").asText(null));
        if (suggested == null || suggested == product.getCategory()) return false;
        if (!OVERWRITABLE_SOURCES.contains(product.getCategorizationSource())) {
            recordDisagreement(product, "category", product.getCategory().name(),
                    suggested.name(), confidence);
            return false;
        }
        product.setCategory(suggested);
        product.setCategorizationSource(CategorizationSource.LLM);
        return true;
    }

    private boolean applyBrandAndName(Product product, JsonNode result) {
        var changed = false;
        var brand = textOrNull(result, "brand");
        if (brand != null && (product.getBrand() == null || product.getBrand().isBlank())) {
            product.setBrand(brand);
            product.setBrandNorm(DescriptionNormalizer.normalizeOrNull(brand));
            changed = true;
        }
        var genericName = textOrNull(result, "generic_name");
        if (genericName != null && (product.getGenericName() == null || product.getGenericName().isBlank())) {
            product.setGenericName(genericName);
            product.setGenericNameNorm(DescriptionNormalizer.normalizeOrNull(genericName));
            changed = true;
        }
        return changed;
    }

    /** Pack size only lands when null today AND it survives the price-math sanity check. */
    private boolean applyPackSize(Product product, JsonNode result) {
        if (product.getPackSize() != null) return false;
        var packSize = result.path("pack_size").isNumber()
                ? BigDecimal.valueOf(result.path("pack_size").asDouble()) : null;
        var packUnit = textOrNull(result, "pack_unit");
        if (packSize == null || packUnit == null || packSize.signum() <= 0) return false;
        if (!packSizePassesPriceSanity(product, packSize, packUnit.toLowerCase(Locale.ROOT))) {
            recordDisagreement(product, "pack", null,
                    packSize.stripTrailingZeros().toPlainString() + packUnit, null);
            return false;
        }
        product.setPackSize(packSize);
        product.setPackUnit(packUnit.toLowerCase(Locale.ROOT));
        return true;
    }

    /**
     * A wrong pack size shows up as an absurd normalized price. Check the most
     * recent real purchase: unitPrice scaled to R$/kg (or R$/l) must land in a
     * grocery-plausible band. Unknown units or no price history pass through.
     */
    private boolean packSizePassesPriceSanity(Product product, BigDecimal packSize, String packUnit) {
        var gramsPerUnit = switch (packUnit) {
            case "g", "ml" -> BigDecimal.ONE;
            case "kg", "l" -> new BigDecimal("1000");
            default -> null;
        };
        if (gramsPerUnit == null) return true;
        var latestItem = receiptItemRepository.findFirstByProductIdOrderByCreatedAtDesc(product.getId());
        if (latestItem.isEmpty() || latestItem.get().getUnitPrice() == null) return true;
        var grams = packSize.multiply(gramsPerUnit);
        if (grams.signum() <= 0) return false;
        var pricePerKg = latestItem.get().getUnitPrice()
                .multiply(new BigDecimal("1000"))
                .divide(grams, 2, RoundingMode.HALF_UP);
        return pricePerKg.compareTo(MIN_PLAUSIBLE_PRICE_PER_KG) >= 0
                && pricePerKg.compareTo(MAX_PLAUSIBLE_PRICE_PER_KG) <= 0;
    }

    private void maybeWriteBackDictionaryRule(Product product, JsonNode result) {
        var keyword = textOrNull(result, "dictionary_keyword");
        var category = parseCategory(result.path("category").asText(null));
        var genericName = textOrNull(result, "generic_name");
        if (keyword == null || category == null) return;
        // Normalize the match key the same way descriptions are (accent-strip + lowercase +
        // SEFAZ expansion), so the curated rule matches at lookup time.
        keyword = DescriptionNormalizer.normalize(keyword);
        if (keyword.isBlank()) return;
        if (!LearnableTokenFilter.isLearnable(keyword)) return;
        if (!DescriptionNormalizer.normalize(product.getNormalizedName()).contains(keyword)) return;
        if (curatedRepository.findByKeyword(keyword).isPresent()) return;
        curatedRepository.save(CuratedDictionaryEntry.builder()
                .keyword(keyword)
                .genericName(genericName != null ? genericName : keyword)
                .category(category)
                .origin("LLM")
                .build());
        log.info("llm_enrich.dictionary_rule_added keyword='{}' category={}", keyword, category);
    }

    private void recordDisagreement(Product product, String field, String currentValue,
                                    String suggestedValue, BigDecimal confidence) {
        if (disagreementRepository.existsByProductIdAndFieldAndResolvedAtIsNull(product.getId(), field)) return;
        disagreementRepository.save(LlmDisagreement.builder()
                .product(product)
                .field(field)
                .currentValue(currentValue)
                .currentSource(product.getCategorizationSource().name())
                .suggestedValue(suggestedValue)
                .confidence(confidence)
                .build());
        log.info("llm_enrich.disagreement product={} field={} current={} suggested={}",
                product.getId(), field, currentValue, suggestedValue);
    }

    private void bumpAttempts(List<Product> products) {
        transactionTemplate.executeWithoutResult(txStatus -> {
            for (var product : products) {
                productRepository.findById(product.getId()).ifPresent(fresh -> {
                    fresh.setLlmEnrichmentAttempts(fresh.getLlmEnrichmentAttempts() + 1);
                    productRepository.save(fresh);
                });
            }
        });
    }

    private static ProductCategory parseCategory(String name) {
        if (name == null) return null;
        try {
            return ProductCategory.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        var value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        var text = value.asText().trim();
        return text.isEmpty() || text.equalsIgnoreCase("null") ? null : text;
    }
}
