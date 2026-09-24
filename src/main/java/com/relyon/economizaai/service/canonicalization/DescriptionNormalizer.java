package com.relyon.economizaai.service.canonicalization;

import java.text.Normalizer;

public final class DescriptionNormalizer {

    private DescriptionNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return "";
        var stripped = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        var cleaned = stripped.toLowerCase()
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // Expand SEFAZ abbreviations so abbreviated and spelled-out receipts converge
        // to the same product + category (e.g. "MANT ELEGE" == "MANTEIGA ELEGE").
        return SefazAbbreviationExpander.expand(cleaned);
    }

    /**
     * Normalized mirror for dedup/matching that preserves null: {@code null} in →
     * {@code null} out, and a value that normalizes to blank → {@code null}. Used
     * to fill the {@code *_norm} columns/keys without turning "no value" into "".
     */
    public static String normalizeOrNull(String raw) {
        if (raw == null) return null;
        var normalized = normalize(raw);
        return normalized.isBlank() ? null : normalized;
    }
}
