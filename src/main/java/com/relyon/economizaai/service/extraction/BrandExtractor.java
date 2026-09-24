package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import com.relyon.economizaai.service.canonicalization.JaroWinklerSimilarity;
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
 * Brand lookup over the brand_registry_entries table (managed via the admin
 * import endpoint). Phrase-scans the normalized description, longest phrase
 * first, so "tio joao" beats "tio".
 *
 * <p>When the exact scan misses, a gated fuzzy fallback catches the two common
 * ways a receipt writes a known brand differently from the registry:
 * <ol>
 *   <li><b>Abbreviation</b> ("d benta" → "Dona Benta"): each description token is
 *       a prefix of the matching brand token, anchored by at least one full-word
 *       exact token so "d b" can't match "dona benta".</li>
 *   <li><b>Typo</b> on a single long token ("nestlee" → "Nestlé") via
 *       Jaro-Winkler above a high threshold.</li>
 * </ol>
 * Both are bounded to candidates sharing the first character (cheap per miss) and
 * every fuzzy hit is logged for audit. Disable with brand-fuzzy-enabled=false.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrandExtractor {

    private static final int MAX_PHRASE_TOKENS = 3;

    /** Abbreviation match: an exact full-word token this long or longer anchors the match. */
    private static final int MIN_ANCHOR_LENGTH = 3;
    /** Abbreviation match: the abbreviated form must carry at least this many chars ("d b" is too weak). */
    private static final int MIN_ABBREV_CHARS = 4;
    /** Typo match: only single tokens this long, both sides, are eligible (short brands mis-fire). */
    private static final int MIN_TYPO_LENGTH = 5;
    /** Typo match: Jaro-Winkler must clear this — deliberately strict, brands are short. */
    private static final double TYPO_THRESHOLD = 0.92;

    @Value("${economizaai.categorization.brand-fuzzy-enabled:true}")
    private boolean fuzzyEnabled = true;

    private final BrandRegistryEntryRepository brandRepository;
    private final AtomicReference<Map<String, String>> brandsRef = new AtomicReference<>(Map.of());
    // Fuzzy candidates grouped by the first character of their first token, so a
    // miss only compares against a small slice instead of the whole registry.
    private final AtomicReference<Map<Character, List<BrandKey>>> fuzzyIndexRef = new AtomicReference<>(Map.of());

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
        log.info("Loaded {} brand entries", brands.size());
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
                    if (matchesAbbreviation(window, candidate.tokens())
                            || matchesTypo(window, candidate.tokens())) {
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
     * True when {@code window} is an abbreviation of {@code brandTokens}: same token
     * count, each window token is a prefix of the corresponding brand token, at
     * least one token is a full-word exact match (the anchor), and the abbreviated
     * form carries enough characters to be meaningful.
     */
    private static boolean matchesAbbreviation(String[] window, String[] brandTokens) {
        if (window.length != brandTokens.length) return false;
        var hasAnchor = false;
        var abbrevChars = 0;
        for (var position = 0; position < window.length; position++) {
            var windowToken = window[position];
            var brandToken = brandTokens[position];
            if (!brandToken.startsWith(windowToken)) return false;
            abbrevChars += windowToken.length();
            if (windowToken.equals(brandToken) && brandToken.length() >= MIN_ANCHOR_LENGTH) hasAnchor = true;
        }
        // A pure exact match is handled by findExact — require at least one abbreviated token here.
        var hasAbbreviation = abbrevChars < totalLength(brandTokens);
        return hasAnchor && hasAbbreviation && abbrevChars >= MIN_ABBREV_CHARS;
    }

    /** True when a single long token is a near-miss (typo) of a single-token brand key. */
    private static boolean matchesTypo(String[] window, String[] brandTokens) {
        if (window.length != 1 || brandTokens.length != 1) return false;
        var windowToken = window[0];
        var brandToken = brandTokens[0];
        if (windowToken.length() < MIN_TYPO_LENGTH || brandToken.length() < MIN_TYPO_LENGTH) return false;
        if (windowToken.equals(brandToken)) return false; // exact is findExact's job
        return JaroWinklerSimilarity.score(windowToken, brandToken) >= TYPO_THRESHOLD;
    }

    private static int totalLength(String[] tokens) {
        var total = 0;
        for (var token : tokens) total += token.length();
        return total;
    }

    private record BrandKey(String[] tokens, String displayName) {}
}
