package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.LearnedDictionaryEntry;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.LearnedDictionaryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Consolidates consistently-classified products into the LEARNED dictionary so the
 * fast deterministic path catches them next time — a self-healing loop that reduces
 * how often the LLM fallback is needed. NOTE: this is NOT a trained ML model; that
 * layer was scaffolded but never trained and has been retired (see DEV_NOTES.md).
 * The {@code economizaai.categorizer.auto-promote.*} config drives it.
 *
 * <p>Promotion criteria (all must hold for a token):</p>
 * <ul>
 *   <li>at least <i>min-samples</i> auto-classified Products contain the token;</li>
 *   <li>at least <i>min-agreement</i> share of them share the majority category;</li>
 *   <li>zero USER-corrected Products contain the token — any human override blocks it.</li>
 * </ul>
 *
 * <p>Tokens are 1- to 3-word phrases from each Product's normalizedName, the same way
 * DictionaryClassifier looks them up. Curated entries always win over learned, so
 * promoting something the curated set already has is a no-op at lookup time. LLM,
 * USER and CONSENSUS products are fetched: LLM ones are the promotable samples,
 * USER/CONSENSUS ones block promotion of their token.</p>
 *
 * <p>Triggered daily by {@code CategorizerMaintenanceJob}; manual: POST /categorizer/auto-promote.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoPromotionService {

    private static final int MAX_PHRASE_TOKENS = 3;

    private final ProductRepository productRepository;
    private final LearnedDictionaryRepository learnedRepository;
    private final DictionaryClassifier dictionaryClassifier;

    // 7 amostras + 90% de concordância = na prática exige unanimidade em 7 ocorrências
    // (6/7 = 85% reprova). Baixo o bastante pra ter efeito no volume atual, seguro o
    // bastante pra não promover padrão instável. Suba conforme o volume crescer.
    @Value("${economizaai.categorizer.auto-promote.min-samples:7}")
    private int minSamples;

    @Value("${economizaai.categorizer.auto-promote.min-agreement:0.90}")
    private double minAgreement;

    @PostConstruct
    void loadOnStartup() {
        refreshClassifierMemory();
    }

    @Transactional
    public synchronized PromotionOutcome promote() {
        // LLM-classified products are the promotable samples; USER/CONSENSUS are the
        // human/consensus-validated ones that BLOCK auto-promotion of their token.
        var relevantSources = List.of(CategorizationSource.LLM, CategorizationSource.USER, CategorizationSource.CONSENSUS);
        var byToken = aggregateTokenStats(productRepository.findByCategorizationSourceIn(relevantSources));

        var toUpsert = new LinkedHashMap<String, TokenUpsertRequest>();
        var promoted = 0;
        var skippedHuman = 0;
        var skippedAgreement = 0;
        var skippedSamples = 0;

        for (var entry : byToken.entrySet()) {
            switch (evaluateToken(entry.getKey(), entry.getValue(), toUpsert)) {
                case PROMOTED -> promoted++;
                case SKIPPED_HUMAN -> skippedHuman++;
                case SKIPPED_AGREEMENT -> skippedAgreement++;
                case SKIPPED_SAMPLES -> skippedSamples++;
                case IGNORED -> { /* below sample floor and human-blocked: not a reportable skip */ }
            }
        }

        batchUpsertLearnedEntries(toUpsert);
        var totalLearned = refreshClassifierMemory();
        var outcome = new PromotionOutcome(promoted, skippedHuman, skippedAgreement, skippedSamples, totalLearned);
        log.info("auto_promote.done {}", outcome);
        return outcome;
    }

    /**
     * Decide one token's fate against the promotion criteria, in this order:
     * <ol>
     *   <li>any USER override blocks promotion outright — reported as
     *       SKIPPED_HUMAN only if it otherwise had enough samples (an override
     *       on a token below the sample floor is just IGNORED, not a near-miss);</li>
     *   <li>below the sample floor → SKIPPED_SAMPLES;</li>
     *   <li>majority-class agreement below threshold → SKIPPED_AGREEMENT;</li>
     *   <li>otherwise add to toUpsert for batch save → PROMOTED.</li>
     * </ol>
     */
    private TokenDecision evaluateToken(String token, TokenStats stats, Map<String, TokenUpsertRequest> toUpsert) {
        if (stats.userOverrides > 0) {
            return stats.autoSamples >= minSamples ? TokenDecision.SKIPPED_HUMAN : TokenDecision.IGNORED;
        }
        if (stats.autoSamples < minSamples) {
            return TokenDecision.SKIPPED_SAMPLES;
        }
        var topCategory = stats.topCategory();
        var agreement = (double) stats.categoryCounts.get(topCategory) / stats.autoSamples;
        if (agreement < minAgreement) {
            return TokenDecision.SKIPPED_AGREEMENT;
        }
        var topGeneric = stats.topGenericName();
        toUpsert.put(token, new TokenUpsertRequest(topGeneric, topCategory, stats.autoSamples));
        log.info("auto_promote.promoted token='{}' category={} genericName='{}' samples={} agreement={}",
                token, topCategory, topGeneric, stats.autoSamples, String.format("%.2f", agreement));
        return TokenDecision.PROMOTED;
    }

    /**
     * Aggregate per-token stats across all relevant products: user/consensus-override
     * counts and LLM-classified sample/category/genericName tallies.
     */
    private HashMap<String, TokenStats> aggregateTokenStats(List<Product> products) {
        var byToken = new HashMap<String, TokenStats>();
        for (var product : products) {
            if (product.getNormalizedName() == null) continue;
            for (var token : phraseTokens(product.getNormalizedName())) {
                var stats = byToken.computeIfAbsent(token, key -> new TokenStats());
                var src = product.getCategorizationSource();
                if (src == CategorizationSource.USER || src == CategorizationSource.CONSENSUS) {
                    stats.userOverrides++; // human/consensus-validated — blocks auto-promotion for this token
                } else if (src == CategorizationSource.LLM
                        && product.getCategory() != null) {
                    stats.autoSamples++;
                    stats.categoryCounts.merge(product.getCategory(), 1, Integer::sum);
                    if (product.getGenericName() != null) {
                        stats.genericNameCounts.merge(product.getGenericName(), 1, Integer::sum);
                    }
                }
            }
        }
        return byToken;
    }

    private void batchUpsertLearnedEntries(Map<String, TokenUpsertRequest> toUpsert) {
        if (toUpsert.isEmpty()) return;
        var existingByToken = learnedRepository.findByNormalizedTokenIn(toUpsert.keySet()).stream()
                .collect(Collectors.toMap(LearnedDictionaryEntry::getNormalizedToken, Function.identity()));
        var now = LocalDateTime.now();
        var toSave = new ArrayList<LearnedDictionaryEntry>();
        for (var e : toUpsert.entrySet()) {
            var req = e.getValue();
            var entry = existingByToken.getOrDefault(e.getKey(),
                    LearnedDictionaryEntry.builder()
                            .normalizedToken(e.getKey())
                            .sampleCount(0)
                            .promotedAt(now)
                            .build());
            entry.setGenericName(req.genericName());
            entry.setCategory(req.category());
            entry.setSampleCount(req.samples());
            entry.setPromotedAt(now);
            toSave.add(entry);
        }
        learnedRepository.saveAll(toSave);
    }

    private int refreshClassifierMemory() {
        var entries = learnedRepository.findAll();
        var map = new LinkedHashMap<String, DictionaryClassifier.DictEntry>();
        for (var entry : entries) {
            map.put(entry.getNormalizedToken(), new DictionaryClassifier.DictEntry(
                    entry.getGenericName(),
                    null,
                    entry.getCategory(),
                    CategorizationSource.LEARNED_DICTIONARY));
        }
        dictionaryClassifier.replaceLearnedEntries(map);
        return entries.size();
    }

    private List<String> phraseTokens(String text) {
        var normalized = DescriptionNormalizer.normalize(text);
        if (normalized.isBlank()) return List.of();
        var tokens = normalized.split("\\s+");
        var phrases = new ArrayList<String>();
        for (var size = MAX_PHRASE_TOKENS; size >= 1; size--) {
            for (var i = 0; i + size <= tokens.length; i++) {
                var phrase = String.join(" ", Arrays.copyOfRange(tokens, i, i + size));
                // Size/unit/fragment tokens (500ml, kg, single letters) carry no
                // category signal and poison every product that shares them.
                if (LearnableTokenFilter.isLearnable(phrase)) {
                    phrases.add(phrase);
                }
            }
        }
        return phrases;
    }

    private static class TokenStats {
        int autoSamples = 0;
        int userOverrides = 0;
        Map<ProductCategory, Integer> categoryCounts = new HashMap<>();
        Map<String, Integer> genericNameCounts = new HashMap<>();

        ProductCategory topCategory() {
            return categoryCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }

        String topGenericName() {
            return genericNameCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }
    }

    private record TokenUpsertRequest(String genericName, ProductCategory category, int samples) {}

    /** Per-token outcome of {@link #evaluateToken}, tallied into {@link PromotionOutcome}. */
    private enum TokenDecision { PROMOTED, SKIPPED_HUMAN, SKIPPED_AGREEMENT, SKIPPED_SAMPLES, IGNORED }

    public record PromotionOutcome(int promoted, int skippedDueToHuman, int skippedDueToAgreement,
                                   int skippedDueToSamples, int learnedTotal) {}
}
