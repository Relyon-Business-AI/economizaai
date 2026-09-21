package com.relyon.economizai.service.canonicalization;

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
}
