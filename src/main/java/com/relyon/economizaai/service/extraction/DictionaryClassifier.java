package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.model.enums.CategorizationSource;
import com.relyon.economizaai.model.enums.ProductCategory;
import com.relyon.economizaai.repository.CuratedDictionaryEntryRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
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

    private static final int MAX_PHRASE_TOKENS = 3;

    private final CuratedDictionaryEntryRepository curatedRepository;
    private final AtomicReference<Map<String, DictEntry>> curatedRef = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, DictEntry>> learnedRef = new AtomicReference<>(Map.of());

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
        for (var entry : curatedRepository.findAll()) {
            entries.put(entry.getKeyword(), new DictEntry(
                    entry.getGenericName(), entry.getCategory(), CategorizationSource.DICTIONARY));
        }
        curatedRef.set(Map.copyOf(entries));
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
        var normalized = DescriptionNormalizer.normalize(rawDescription);
        if (normalized.isBlank()) return DictEntry.EMPTY;
        var tokens = normalized.split("\\s+");
        // single read per tier — consistent snapshots throughout this call
        var curated = curatedRef.get();
        var learned = learnedRef.get();
        for (var size = MAX_PHRASE_TOKENS; size >= 1; size--) {
            for (var i = 0; i + size <= tokens.length; i++) {
                var phrase = String.join(" ", Arrays.copyOfRange(tokens, i, i + size));
                var curatedEntry = curated.get(phrase);
                if (curatedEntry != null) return curatedEntry;
                var learnedEntry = learned.get(phrase);
                if (learnedEntry != null) return learnedEntry;
            }
        }
        return DictEntry.EMPTY;
    }

    public record DictEntry(String genericName, ProductCategory category, CategorizationSource source) {
        public static final DictEntry EMPTY = new DictEntry(null, null, CategorizationSource.NONE);
    }
}
