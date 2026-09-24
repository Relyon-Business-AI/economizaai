package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Two-tier dictionary lookup:
 *   - "curated" entries from the curated_dictionary_entries table
 *     (hand-maintained via the admin import endpoint, highest priority)
 *   - "learned" entries auto-promoted by AutoPromotionService from stable
 *     ML predictions (Phase 2.5c) — populated at runtime via replaceLearnedEntries()
 *
 * Curated wins on key collision. Both contribute to dictionary coverage at
 * inference time. Returned DictEntry carries the source so callers know
 * whether the answer came from human curation (DICTIONARY) or
 * auto-promoted ML (LEARNED_DICTIONARY).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DictionaryClassifier {

    /**
     * Longest phrase (in tokens) the lookup will assemble and test against the
     * dictionary. Configurable so the effect of a wider window can be simulated
     * and tuned without a redeploy — see PhraseTokenSimulationService and the
     * env var ECONOMIZAAI_CATEGORIZATION_MAX_PHRASE_TOKENS. Defaulted in-field so
     * plain (non-Spring) unit tests keep the historical window of 3.
     */
    @Value("${economizaai.categorization.max-phrase-tokens:3}")
    private int maxPhraseTokens = 3;

    /**
     * Abbreviation fallback for the generic/category side: when the exact phrase
     * lookup misses, try prefix-matching the description tokens against CURATED
     * keywords ("shamp" → "shampoo"). Guards: per-token minimum lengths, and the
     * match is dropped when candidate keywords DISAGREE on category (the
     * "cond" = condensado|condicionador ambiguity). Off by default until measured.
     */
    @Value("${economizaai.categorization.dictionary-fuzzy-enabled:false}")
    private boolean abbreviationFallbackEnabled = false;

    /** Single-token abbreviation must be at least this long ("gel" is too ambiguous). */
    private static final int MIN_SINGLE_ABBREV = 4;
    /** Every token of a multi-token abbreviation must be at least this long. */
    private static final int MIN_MULTI_TOKEN = 3;

    private final CuratedDictionaryEntryRepository curatedRepository;
    private final AtomicReference<Map<String, DictEntry>> curatedRef = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, DictEntry>> learnedRef = new AtomicReference<>(Map.of());
    // Curated keys tokenized and grouped by first character — the abbreviation
    // fallback's candidate index (same bounding trick as BrandExtractor's fuzzy).
    private final AtomicReference<Map<Character, List<KeyedEntry>>> abbrevIndexRef = new AtomicReference<>(Map.of());

    @PostConstruct
    void load() {
        reloadCuratedEntries();
    }

    /**
     * Reloads the curated tier from the database. Called at startup and after
     * every admin bulk-import, using the same lock-free atomic swap as the
     * learned tier so classify() always reads a consistent snapshot.
     */
    public void reloadCuratedEntries() {
        var entries = new LinkedHashMap<String, DictEntry>();
        var abbrevIndex = new LinkedHashMap<Character, List<KeyedEntry>>();
        for (var entry : curatedRepository.findAll()) {
            var dictEntry = new DictEntry(
                    entry.getGenericName(), entry.getBrand(), entry.getCategory(), CategorizationSource.DICTIONARY);
            entries.put(entry.getKeyword(), dictEntry);
            var keyword = entry.getKeyword();
            if (keyword != null && !keyword.isBlank()) {
                abbrevIndex.computeIfAbsent(keyword.charAt(0), ignored -> new ArrayList<>())
                        .add(new KeyedEntry(keyword.split("\\s+"), dictEntry));
            }
        }
        curatedRef.set(Map.copyOf(entries));
        abbrevIndexRef.set(Map.copyOf(abbrevIndex));
        log.info("Loaded {} curated dictionary entries", entries.size());
    }

    /**
     * Replaces the in-memory learned-entries map atomically. Called by
     * AutoPromotionService and ConsensusPromotionService after each promotion
     * pass. Lock-free: the reference swap is atomic, so classify() always reads
     * a consistent snapshot even when a reload is in progress.
     */
    public void replaceLearnedEntries(Map<String, DictEntry> entries) {
        learnedRef.set(Map.copyOf(entries));
        log.info("Loaded {} learned dictionary entries", entries.size());
    }

    public DictEntry classify(String rawDescription) {
        return classify(rawDescription, maxPhraseTokens);
    }

    /**
     * Same lookup as {@link #classify(String)} but with an explicit phrase-window
     * size — used by PhraseTokenSimulationService to measure what a wider window
     * would recover before it's promoted to the live env var.
     */
    public DictEntry classify(String rawDescription, int maxTokens) {
        var normalized = DescriptionNormalizer.normalize(rawDescription);
        if (normalized.isBlank()) return DictEntry.EMPTY;
        var tokens = normalized.split("\\s+");
        // single read per tier — consistent snapshots throughout this call
        var curated = curatedRef.get();
        var learned = learnedRef.get();
        for (var size = Math.min(maxTokens, tokens.length); size >= 1; size--) {
            for (var i = 0; i + size <= tokens.length; i++) {
                var phrase = String.join(" ", Arrays.copyOfRange(tokens, i, i + size));
                var curatedEntry = curated.get(phrase);
                if (curatedEntry != null) return curatedEntry;
                var learnedEntry = learned.get(phrase);
                if (learnedEntry != null) return learnedEntry;
            }
        }
        return abbreviationFallbackEnabled ? abbreviationLookup(tokens, maxTokens) : DictEntry.EMPTY;
    }

    /**
     * Prefix-abbreviation fallback over CURATED keys only (highest-trust tier).
     * Longest window first; a window's candidate matches must all AGREE on
     * category, otherwise the window is skipped as ambiguous. Every hit is
     * logged so precision can be audited from the logs.
     */
    private DictEntry abbreviationLookup(String[] tokens, int maxTokens) {
        var index = abbrevIndexRef.get();
        for (var size = Math.min(maxTokens, tokens.length); size >= 1; size--) {
            for (var i = 0; i + size <= tokens.length; i++) {
                var window = Arrays.copyOfRange(tokens, i, i + size);
                var candidates = index.get(window[0].charAt(0));
                if (candidates == null) continue;
                DictEntry match = null;
                var ambiguous = false;
                for (var candidate : candidates) {
                    if (!abbreviates(window, candidate.keyTokens())) continue;
                    if (match == null) {
                        match = candidate.entry();
                    } else if (match.category() != candidate.entry().category()) {
                        ambiguous = true;
                        break;
                    }
                }
                if (match != null && !ambiguous) {
                    log.info("dictionary.matched_by_abbreviation window='{}' generic='{}' category={}",
                            String.join(" ", window), match.genericName(), match.category());
                    return match;
                }
            }
        }
        return DictEntry.EMPTY;
    }

    /**
     * True when every window token is a prefix of the corresponding keyword token,
     * at least one token is genuinely shorter (a real abbreviation — exact matches
     * are the main lookup's job), and length floors hold: single-token windows
     * need ≥{@value MIN_SINGLE_ABBREV} chars, multi-token ones ≥{@value MIN_MULTI_TOKEN} per token.
     */
    private static boolean abbreviates(String[] window, String[] keyTokens) {
        if (window.length != keyTokens.length) return false;
        var minLength = window.length == 1 ? MIN_SINGLE_ABBREV : MIN_MULTI_TOKEN;
        var hasAbbreviation = false;
        for (var position = 0; position < window.length; position++) {
            var windowToken = window[position];
            var keyToken = keyTokens[position];
            if (windowToken.length() < minLength) return false;
            if (!keyToken.startsWith(windowToken)) return false;
            if (windowToken.length() < keyToken.length()) hasAbbreviation = true;
        }
        return hasAbbreviation;
    }

    private record KeyedEntry(String[] keyTokens, DictEntry entry) {}

    public record DictEntry(String genericName, String brand, ProductCategory category, CategorizationSource source) {
        public static final DictEntry EMPTY = new DictEntry(null, null, null, CategorizationSource.NONE);
    }
}
