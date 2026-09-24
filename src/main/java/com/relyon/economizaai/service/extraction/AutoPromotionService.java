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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
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
 * Auto-promotes stable ML predictions into the learned-dictionary so the
 * faster, deterministic dictionary path catches them next time.
 *
 * <p>Promotion criteria (all must hold for a token, thresholds tunable via
 * {@code economizaai.ml.auto-promote.*}):</p>
 * <ul>
 *   <li>at least <i>min-samples</i> ML-categorized Products contain the token</li>
 *   <li>at least <i>min-agreement</i> share of those Products share the same
 *       category as the majority class</li>
 *   <li>zero USER-corrected Products contain the token — any human override
 *       blocks promotion</li>
 * </ul>
 *
 * <p>Tokens are 1- to 3-word phrases extracted from each Product's
 * normalizedName, same way DictionaryClassifier looks them up. Curated CSV
 * always wins over learned, so promoting an entry that the curated CSV
 * already has is a no-op at lookup time.</p>
 *
 * <p>Only ML and USER products are fetched — DICTIONARY and LEARNED_DICTIONARY
 * products carry no token stats we need here, and skipping them avoids loading
 * unrelated rows on large catalogs.</p>
 *
 * <p>Runs on app startup (after MlClassifierService training) and on a
 * fixed schedule (default daily). Manual trigger: POST /categorizer/auto-promote.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoPromotionService {

    private static final int MAX_PHRASE_TOKENS = 3;

    private final ProductRepository productRepository;
    private final LearnedDictionaryRepository learnedRepository;
    private final DictionaryClassifier dictionaryClassifier;

    // Self-reference so the scheduled trigger's call to @Transactional promote()
    // goes through the Spring proxy. Defaults to `this` for plain unit tests.
    @Lazy
    @Autowired
    private AutoPromotionService self = this;

    @Value("${economizaai.ml.auto-promote.min-samples:30}")
    private int minSamples;

    @Value("${economizaai.ml.auto-promote.min-agreement:0.90}")
    private double minAgreement;

    @PostConstruct
    void loadOnStartup() {
        refreshClassifierMemory();
    }

    @Scheduled(fixedDelayString = "${economizaai.ml.auto-promote-interval-ms:86400000}",
               initialDelayString = "${economizaai.ml.auto-promote-initial-delay-ms:86400000}")
    public void scheduledPromote() {
        log.info("auto_promote.scheduled");
        self.promote();
    }

    @Transactional
    public synchronized PromotionOutcome promote() {
        // Only ML and human-validated products carry stats relevant to this pass.
        var relevantSources = List.of(CategorizationSource.ML, CategorizationSource.USER, CategorizationSource.CONSENSUS);
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
     *   <li>below the ML sample floor → SKIPPED_SAMPLES;</li>
     *   <li>majority-class agreement below threshold → SKIPPED_AGREEMENT;</li>
     *   <li>otherwise add to toUpsert for batch save → PROMOTED.</li>
     * </ol>
     */
    private TokenDecision evaluateToken(String token, TokenStats stats, Map<String, TokenUpsertRequest> toUpsert) {
        if (stats.userOverrides > 0) {
            return stats.mlSamples >= minSamples ? TokenDecision.SKIPPED_HUMAN : TokenDecision.IGNORED;
        }
        if (stats.mlSamples < minSamples) {
            return TokenDecision.SKIPPED_SAMPLES;
        }
        var topCategory = stats.topCategory();
        var agreement = (double) stats.categoryCounts.get(topCategory) / stats.mlSamples;
        if (agreement < minAgreement) {
            return TokenDecision.SKIPPED_AGREEMENT;
        }
        var topGeneric = stats.topGenericName();
        toUpsert.put(token, new TokenUpsertRequest(topGeneric, topCategory, stats.mlSamples));
        log.info("auto_promote.promoted token='{}' category={} genericName='{}' samples={} agreement={}",
                token, topCategory, topGeneric, stats.mlSamples, String.format("%.2f", agreement));
        return TokenDecision.PROMOTED;
    }

    /**
     * Aggregate per-token stats across all relevant products: user-override counts and ML
     * sample/category/genericName tallies.
     */
    private HashMap<String, TokenStats> aggregateTokenStats(List<Product> products) {
        var byToken = new HashMap<String, TokenStats>();
        for (var product : products) {
            if (product.getNormalizedName() == null) continue;
            for (var token : phraseTokens(product.getNormalizedName())) {
                var stats = byToken.computeIfAbsent(token, key -> new TokenStats());
                var src = product.getCategorizationSource();
                if (src == CategorizationSource.USER || src == CategorizationSource.CONSENSUS) {
                    stats.userOverrides++; // human-validated — blocks auto-promotion for this token
                } else if (src == CategorizationSource.ML
                        && product.getCategory() != null) {
                    stats.mlSamples++;
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
        int mlSamples = 0;
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
