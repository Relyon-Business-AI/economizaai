package com.relyon.economizaai.service.extraction;

import com.relyon.economizaai.repository.BrandRegistryEntryRepository;
import com.relyon.economizaai.service.canonicalization.DescriptionNormalizer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Brand lookup over the brand_registry_entries table (managed via the admin
 * import endpoint). Phrase-scans the normalized description, longest phrase
 * first, so "tio joao" beats "tio".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrandExtractor {

    @Value("${economizaai.categorization.max-phrase-tokens:3}")
    private int maxPhraseTokens = 3;

    private final BrandRegistryEntryRepository brandRepository;
    private final AtomicReference<Map<String, String>> brandsRef = new AtomicReference<>(Map.of());

    @PostConstruct
    void load() {
        reload();
    }

    /** Reloads from the database — at startup and after every admin bulk-import. */
    public void reload() {
        var brands = new LinkedHashMap<String, String>();
        for (var entry : brandRepository.findAll()) {
            brands.put(entry.getNormalizedKey(), entry.getDisplayName());
        }
        brandsRef.set(Map.copyOf(brands));
        log.info("Loaded {} brand entries", brands.size());
    }

    public String find(String rawDescription) {
        return find(rawDescription, maxPhraseTokens);
    }

    /** Same lookup as {@link #find(String)} with an explicit phrase-window size (for simulation). */
    public String find(String rawDescription, int maxTokens) {
        var normalized = DescriptionNormalizer.normalize(rawDescription);
        if (normalized.isBlank()) return null;
        var tokens = normalized.split("\\s+");
        var brands = brandsRef.get();
        for (var size = Math.min(maxTokens, tokens.length); size >= 1; size--) {
            for (var i = 0; i + size <= tokens.length; i++) {
                var phrase = String.join(" ", Arrays.copyOfRange(tokens, i, i + size));
                var match = brands.get(phrase);
                if (match != null) return match;
            }
        }
        return null;
    }
}
