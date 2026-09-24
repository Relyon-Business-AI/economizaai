package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Brand lookup over the brand_registry_entries table (managed via the admin
 * import endpoint). Phrase-scans the normalized description, longest phrase
 * first, so "tio joao" beats "tio".
 *
 * <p>When the exact scan misses, a gated fuzzy fallback catches how a receipt
 * abbreviates a known brand. Two shapes only, both precision-first (a dev dry-run
 * showed a Jaro-Winkler typo branch produced mostly false matches — "verde" →
 * Verdemar — so it was dropped):
 * <ol>
 *   <li><b>Anchored multi-token</b> ("d benta" → "Dona Benta"): each token is a
 *       prefix of the matching brand token, with at least one full-word exact
 *       token anchoring the match.</li>
 *   <li><b>Single-token prefix</b> ("antarc" → "Antártica"): a long prefix of a
 *       clearly-longer brand token that isn't a common descriptor word.</li>
 * </ol>
 * Bounded to candidates sharing the first character; every hit is logged. Off by
 * default (brand-fuzzy-enabled) until re-measured on real data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrandExtractor {

    private static final int MAX_PHRASE_TOKENS = 3;

    /** Anchored match: an exact full-word token this long or longer anchors it. */
    private static final int MIN_ANCHOR_LENGTH = 3;
    /** Anchored match: the abbreviated form must carry at least this many chars ("d b" is too weak). */
    private static final int MIN_ABBREV_CHARS = 4;
    /** Single-token prefix: the prefix must be at least this long (blocks short words like "verde"). */
    private static final int MIN_SINGLE_PREFIX = 6;
    /** Single-token prefix: the brand token must exceed the prefix by at least this much (blocks "essencia"→"essencial"). */
    private static final int MIN_BRAND_EXTRA = 2;

    /**
     * Common descriptor words that are prefixes of real brands but almost never
     * MEAN that brand on a receipt line. Backstop for the length guards.
     */
    private static final Set<String> COMMON_WORDS = Set.of(
            "verde", "branco", "preto", "preta", "amarelo", "vermelho", "natural", "integral",
            "premium", "especial", "tradicional", "organico", "cremoso", "classico", "gourmet",
            "familia", "familiar", "original", "selecao", "primeira", "segunda", "dourado",
            "dourada", "essencia", "floral", "fresco", "fresca", "caseiro", "suave", "brasil");

    @Value("${economizaai.categorization.brand-fuzzy-enabled:false}")
    private boolean fuzzyEnabled = false;

    private final BrandRegistryEntryRepository brandRepository;
    private final CuratedDictionaryEntryRepository curatedRepository;
    private final AtomicReference<Map<String, String>> brandsRef = new AtomicReference<>(Map.of());
    // Fuzzy candidates grouped by the first character of their first token, so a
    // miss only compares against a small slice instead of the whole registry.
    private final AtomicReference<Map<Character, List<BrandKey>>> fuzzyIndexRef = new AtomicReference<>(Map.of());
    // Generic product words (from the curated dictionary) — never the intended brand
    // on a single-token prefix match, e.g. "biscoito" (expanded from "bisc") must
    // not resolve to the brand "Biscoitone".
    private final AtomicReference<Set<String>> productWordsRef = new AtomicReference<>(Set.of());

    @PostConstruct
    void load() {
        reload();
    }

    /** Reloads from the database — at startup and after every admin bulk-import. */
    public void reload() {
        var brands = new LinkedHashMap<String, String>();
        var fuzzyIndex = new LinkedHashMap<Character, List<BrandKey>>();
        for (var entry : brandRepository.findAll()) {
            var key = entry.getNormalizedKey();
            brands.put(key, entry.getDisplayName());
            if (key.isBlank()) continue;
            var tokens = key.split("\\s+");
            fuzzyIndex.computeIfAbsent(key.charAt(0), ignored -> new ArrayList<>())
                    .add(new BrandKey(tokens, entry.getDisplayName()));
        }
        brandsRef.set(Map.copyOf(brands));
        fuzzyIndexRef.set(Map.copyOf(fuzzyIndex));
        productWordsRef.set(loadProductWords());
        log.info("Loaded {} brand entries", brands.size());
    }

    /** Every token of every curated dictionary keyword — the generic-word blocklist for fuzzy. */
    private Set<String> loadProductWords() {
        var words = new LinkedHashSet<String>();
        for (var entry : curatedRepository.findAll()) {
            var normalized = DescriptionNormalizer.normalize(entry.getKeyword());
            if (normalized.isBlank()) continue;
            for (var token : normalized.split("\\s+")) words.add(token);
        }
        return Set.copyOf(words);
    }

    public String find(String rawDescription) {
        var normalized = DescriptionNormalizer.normalize(rawDescription);
        if (normalized.isBlank()) return null;
        var tokens = normalized.split("\\s+");
        var exact = findExact(tokens);
        if (exact != null) return exact;
        return fuzzyEnabled ? findFuzzy(tokens) : null;
    }

    private String findExact(String[] tokens) {
        var brands = brandsRef.get();
        for (var size = MAX_PHRASE_TOKENS; size >= 1; size--) {
            for (var i = 0; i + size <= tokens.length; i++) {
                var phrase = String.join(" ", Arrays.copyOfRange(tokens, i, i + size));
                var match = brands.get(phrase);
                if (match != null) return match;
            }
        }
        return null;
    }

    /** Longest window first so a fuzzy hit prefers the most specific brand phrase. */
    private String findFuzzy(String[] tokens) {
        var index = fuzzyIndexRef.get();
        for (var size = MAX_PHRASE_TOKENS; size >= 1; size--) {
            for (var i = 0; i + size <= tokens.length; i++) {
                var window = Arrays.copyOfRange(tokens, i, i + size);
                var candidates = index.get(window[0].charAt(0));
                if (candidates == null) continue;
                for (var candidate : candidates) {
                    if (abbreviates(window, candidate.tokens())) {
                        log.info("brand.matched_by_fuzzy window='{}' brand='{}'",
                                String.join(" ", window), candidate.displayName());
                        return candidate.displayName();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Description sub-phrases (normalized) that abbreviate {@code brandDisplayName}
     * but aren't already exact registry keys. Used to PROMOTE confirmed fuzzy hits
     * into curated aliases: a product whose brand we already know (e.g. via EAN)
     * yields "d benta" from "ferm bio d benta 10g" → a deterministic alias for
     * "Dona Benta". Returns empty when the brand is blank or nothing abbreviates it.
     */
    public List<String> abbreviationCandidates(String rawDescription, String brandDisplayName) {
        if (brandDisplayName == null) return List.of();
        var brandKey = DescriptionNormalizer.normalize(brandDisplayName);
        if (brandKey.isBlank()) return List.of();
        var brandTokens = brandKey.split("\\s+");
        var normalized = DescriptionNormalizer.normalize(rawDescription);
        if (normalized.isBlank()) return List.of();
        var tokens = normalized.split("\\s+");
        var brands = brandsRef.get();
        var candidates = new LinkedHashSet<String>();
        for (var size = MAX_PHRASE_TOKENS; size >= 1; size--) {
            for (var i = 0; i + size <= tokens.length; i++) {
                var window = Arrays.copyOfRange(tokens, i, i + size);
                if (!abbreviates(window, brandTokens)) continue;
                var key = String.join(" ", window);
                if (!brands.containsKey(key)) candidates.add(key); // skip existing exact keys
            }
        }
        return new ArrayList<>(candidates);
    }

    /** A window abbreviates a brand via the anchored multi-token OR guarded single-token rule. */
    private boolean abbreviates(String[] window, String[] brandTokens) {
        return matchesAnchored(window, brandTokens) || matchesSingleTokenPrefix(window, brandTokens);
    }

    /**
     * True when {@code window} is an abbreviation of {@code brandTokens}: same token
     * count, each window token is a prefix of the corresponding brand token, at
     * least one token is a full-word exact match (the anchor), and the abbreviated
     * form carries enough characters to be meaningful.
     */
    private static boolean matchesAnchored(String[] window, String[] brandTokens) {
        if (window.length != brandTokens.length || window.length < 2) return false;
        var hasAnchor = false;
        var abbrevChars = 0;
        for (var position = 0; position < window.length; position++) {
            var windowToken = window[position];
            var brandToken = brandTokens[position];
            if (!brandToken.startsWith(windowToken)) return false;
            abbrevChars += windowToken.length();
            if (windowToken.equals(brandToken) && brandToken.length() >= MIN_ANCHOR_LENGTH) hasAnchor = true;
        }
        var hasAbbreviation = abbrevChars < totalLength(brandTokens);
        return hasAnchor && hasAbbreviation && abbrevChars >= MIN_ABBREV_CHARS;
    }

    /**
     * True when a single long token is a clear prefix of a clearly-longer single-token
     * brand and isn't a common descriptor word ("antarc" → "Antártica", but never
     * "verde" → "Verdemar" or "essencia" → "Essencial").
     */
    private boolean matchesSingleTokenPrefix(String[] window, String[] brandTokens) {
        if (window.length != 1 || brandTokens.length != 1) return false;
        var windowToken = window[0];
        var brandToken = brandTokens[0];
        if (windowToken.length() < MIN_SINGLE_PREFIX) return false;
        if (brandToken.length() < windowToken.length() + MIN_BRAND_EXTRA) return false;
        if (!brandToken.startsWith(windowToken)) return false;
        return !COMMON_WORDS.contains(windowToken) && !productWordsRef.get().contains(windowToken);
    }

    private static int totalLength(String[] tokens) {
        var total = 0;
        for (var token : tokens) total += token.length();
        return total;
    }

    private record BrandKey(String[] tokens, String displayName) {}
}
