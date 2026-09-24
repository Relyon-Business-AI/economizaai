package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.LearnedDictionaryEntry;
import com.relyon.economizaai.model.Product;
import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.HouseholdProductCategoryOverrideRepository;
import com.relyon.economizaai.repository.LearnedDictionaryRepository;
import com.relyon.economizaai.repository.ProductRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
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
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns user category corrections into deterministic knowledge — the
 * "evidence → truth on consensus" step. Source #2 of the cascade
 * ({@code learned_dictionary}) is fed here, NOT just by ML auto-promotion.
 *
 * <p>A per-household correction (see {@code HouseholdProductCategoryOverride})
 * is only evidence. When at least {@code min-households} distinct households
 * correct the SAME product to the SAME category, that product <b>graduates</b>:
 * its global category is set (source USER, so everyone sees it). Tokens that
 * recur across consensus products (≥ {@code min-token-products}, all agreeing)
 * are also promoted into the learned dictionary so similar future products
 * inherit the category. A single household never changes anything globally.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsensusPromotionService {

    private static final int MAX_PHRASE_TOKENS = 3;

    private final HouseholdProductCategoryOverrideRepository overrideRepository;
    private final ProductRepository productRepository;
    private final LearnedDictionaryRepository learnedRepository;
    private final DictionaryClassifier dictionaryClassifier;

    // Self-reference so the scheduled trigger's call to @Transactional promote()
    // goes through the Spring proxy. Defaults to `this` for plain unit tests.
    @Lazy
    @Autowired
    private ConsensusPromotionService self = this;

    // Default 3 (was 2): two colluding/coinciding households were enough to
    // graduate a product to GLOBAL truth — too permissive for an irreversible
    // promotion. 3 matches the app's K-anonymity floor.
    @Value("${economizaai.categorizer.consensus.min-households:3}")
    private int minHouseholds;

    @Value("${economizaai.categorizer.consensus.min-token-products:2}")
    private int minTokenProducts;

    @Scheduled(fixedDelayString = "${economizaai.categorizer.consensus.interval-ms:86400000}",
               initialDelayString = "${economizaai.categorizer.consensus.initial-delay-ms:86400000}")
    public void scheduledPromote() {
        log.info("consensus_promote.scheduled");
        self.promote();
    }

    @Transactional
    public synchronized ConsensusOutcome promote() {
        var votesByProduct = tallyHouseholdVotes();
        var consensusByProduct = resolveConsensus(votesByProduct);
        var graduation = graduateConsensusProducts(consensusByProduct);

        var learnedTokens = promoteAgreedTokens(graduation.tokenVotes());
        var totalLearned = reloadLearnedEntries();
        var outcome = new ConsensusOutcome(graduation.productsGraduated(), learnedTokens, totalLearned);
        log.info("consensus_promote.done {}", outcome);
        return outcome;
    }

    /** productId -> (category -> distinct households that corrected it to that category). */
    private Map<UUID, Map<ProductCategory, Integer>> tallyHouseholdVotes() {
        var votesByProduct = new HashMap<UUID, Map<ProductCategory, Integer>>();
        for (var override : overrideRepository.findAll()) {
            // Custom-category overrides are household-specific and never graduate
            // to the global enum — only enum corrections count toward consensus.
            if (override.getCategory() == null) continue;
            votesByProduct
                    .computeIfAbsent(override.getProduct().getId(), key -> new HashMap<>())
                    .merge(override.getCategory(), 1, Integer::sum);
        }
        return votesByProduct;
    }

    /**
     * Resolve consensus first, then load the winning products in one query
     * instead of a findById per product.
     */
    private Map<UUID, ProductCategory> resolveConsensus(Map<UUID, Map<ProductCategory, Integer>> votesByProduct) {
        var consensusByProduct = new HashMap<UUID, ProductCategory>();
        votesByProduct.forEach((productId, votes) -> {
            var consensusCategory = categoryWithConsensus(votes);
            if (consensusCategory != null) consensusByProduct.put(productId, consensusCategory);
        });
        return consensusByProduct;
    }

    /** Graduate winning products to global truth (source CONSENSUS) and collect token votes for generalization. */
    private Graduation graduateConsensusProducts(Map<UUID, ProductCategory> consensusByProduct) {
        var tokenVotes = new HashMap<String, Map<ProductCategory, Integer>>();
        var toSave = new ArrayList<Product>();

        for (var product : productRepository.findAllById(consensusByProduct.keySet())) {
            var consensusCategory = consensusByProduct.get(product.getId());

            if (product.getCategory() != consensusCategory
                    || product.getCategorizationSource() != CategorizationSource.CONSENSUS) {
                product.setCategory(consensusCategory);
                product.setCategorizationSource(CategorizationSource.CONSENSUS);
                toSave.add(product);
                log.info("consensus_promote.product_graduated product={} category={}",
                        product.getId(), consensusCategory);
            }

            // Feed token consensus for generalization to similar future products.
            for (var token : phraseTokens(product.getNormalizedName())) {
                tokenVotes.computeIfAbsent(token, key -> new HashMap<>())
                        .merge(consensusCategory, 1, Integer::sum);
            }
        }

        if (!toSave.isEmpty()) {
            productRepository.saveAll(toSave);
        }
        return new Graduation(toSave.size(), tokenVotes);
    }

    /** Category corrected by enough distinct households; null if no clear winner. */
    private ProductCategory categoryWithConsensus(Map<ProductCategory, Integer> householdsByCategory) {
        ProductCategory winner = null;
        var winnerHouseholds = 0;
        var tie = false;
        for (var entry : householdsByCategory.entrySet()) {
            if (entry.getValue() > winnerHouseholds) {
                winner = entry.getKey();
                winnerHouseholds = entry.getValue();
                tie = false;
            } else if (entry.getValue() == winnerHouseholds) {
                tie = true;
            }
        }
        return (!tie && winnerHouseholds >= minHouseholds) ? winner : null;
    }

    /** Collect agreed tokens then batch-upsert into the learned dictionary. */
    private int promoteAgreedTokens(Map<String, Map<ProductCategory, Integer>> tokenVotes) {
        var toUpsert = new LinkedHashMap<String, ProductCategory>();
        for (var entry : tokenVotes.entrySet()) {
            var categories = entry.getValue();
            if (categories.size() != 1) continue; // any disagreement → skip
            var category = categories.keySet().iterator().next();
            var count = categories.get(category);
            if (count < minTokenProducts) continue; // not recurrent enough
            toUpsert.put(entry.getKey(), category);
            log.info("consensus_promote.token_learned token='{}' category={} products={}",
                    entry.getKey(), category, count);
        }
        if (toUpsert.isEmpty()) return 0;
        batchUpsertLearnedTokens(toUpsert);
        return toUpsert.size();
    }

    private void batchUpsertLearnedTokens(Map<String, ProductCategory> toUpsert) {
        var existingByToken = learnedRepository.findByNormalizedTokenIn(toUpsert.keySet()).stream()
                .collect(Collectors.toMap(LearnedDictionaryEntry::getNormalizedToken, Function.identity()));
        var now = LocalDateTime.now();
        var toSave = new ArrayList<LearnedDictionaryEntry>();
        for (var tokenEntry : toUpsert.entrySet()) {
            var entry = existingByToken.getOrDefault(tokenEntry.getKey(),
                    LearnedDictionaryEntry.builder()
                            .normalizedToken(tokenEntry.getKey())
                            .sampleCount(0)
                            .build());
            entry.setCategory(tokenEntry.getValue());
            entry.setPromotedAt(now);
            toSave.add(entry);
        }
        learnedRepository.saveAll(toSave);
    }

    private int reloadLearnedEntries() {
        var entries = learnedRepository.findAll();
        var map = new LinkedHashMap<String, DictionaryClassifier.DictEntry>();
        for (var entry : entries) {
            map.put(entry.getNormalizedToken(), new DictionaryClassifier.DictEntry(
                    entry.getGenericName(), null, entry.getCategory(), CategorizationSource.LEARNED_DICTIONARY));
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
            for (var start = 0; start + size <= tokens.length; start++) {
                var phrase = String.join(" ", Arrays.copyOfRange(tokens, start, start + size));
                // Size/unit/fragment tokens (500ml, kg, single letters) carry no
                // category signal and poison every product that shares them.
                if (LearnableTokenFilter.isLearnable(phrase)) {
                    phrases.add(phrase);
                }
            }
        }
        return phrases;
    }

    private record Graduation(int productsGraduated, Map<String, Map<ProductCategory, Integer>> tokenVotes) {}

    public record ConsensusOutcome(int productsGraduated, int tokensLearned, int learnedTotal) {}
}
