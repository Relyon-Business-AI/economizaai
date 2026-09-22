package com.relyon.economizaai.service.extraction;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Guards what the learned dictionary is allowed to memorize. Package-size and
 * unit tokens carry zero category signal but appear on every kind of product —
 * the live dev DB actually learned {@code 500ml → CLEANING} (from a detergent)
 * and then classified olive oil as cleaning ("AZEIT GALLO 500ml"). Same for
 * bare units ({@code kg → PRODUCE}) and single letters shed by truncated
 * NFC-e descriptions.
 *
 * <p>A phrase is learnable only when EVERY word survives (no unit/size/number
 * words, no 1-2 letter fragments): a poisoned word poisons the phrase
 * ("zip 1kg" is as generic as "1kg").
 */
public final class LearnableTokenFilter {

    // 500ml, 1l, 2kg, 165g, 350, 12x1, 1.5l — any number-led size expression.
    // Repetition is bounded ({0,10}, real size words have ≤3 segments) so a
    // pathologically long token can't trigger regex-engine stack overflow.
    private static final Pattern SIZE_WORD = Pattern.compile("^\\d+([.,x]\\d+){0,10}[a-z]{0,3}$");
    // bare packaging/measure units as standalone words
    private static final Set<String> UNIT_WORDS = Set.of(
            "g", "kg", "mg", "ml", "l", "lt", "un", "und", "unid", "cx", "pct", "pc",
            "dz", "fd", "pt", "kit", "leve", "gratis");

    private LearnableTokenFilter() {
    }

    /** True when the (already normalized, space-separated) phrase is worth learning. */
    public static boolean isLearnable(String phrase) {
        if (phrase == null || phrase.isBlank()) return false;
        var words = phrase.trim().split("\\s+");
        for (var word : words) {
            if (word.length() < 3) return false;
            if (UNIT_WORDS.contains(word)) return false;
            if (SIZE_WORD.matcher(word).matches()) return false;
        }
        return true;
    }
}
