package com.relyon.economizaai.service.canonicalization;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;

public final class DescriptionNormalizer {

    private DescriptionNormalizer() {}

    private static final int MIN_KEYWORD_TOKEN_LENGTH = 3;
    // ≥3-char packaging/unit/quantity words that are noise as a category keyword
    // (shorter units like l/ml/kg/un/pc are already dropped by the min-length rule).
    private static final Set<String> KEYWORD_NOISE_TOKENS = Set.of(
            "pct", "ltr", "und", "unid", "unidade", "kit", "emb", "caixa", "caixas",
            "frasco", "pote", "pacote", "sache", "garrafa", "lata", "saco", "rolo");

    /**
     * Cleans a would-be curated-rule keyword: normalizes it, then trims leading and
     * trailing noise tokens — single/double letters, pure numbers and unit/packaging
     * words (l, ml, 900, pct, und…). Returns "" when nothing meaningful survives, so a
     * garbage suggestion like "l" (from "L ROUPA OMO 900ML LA") can't become a rule
     * that matches every item carrying a stray "l". A usable keyword must keep at least
     * one real product token (≥{@value #MIN_KEYWORD_TOKEN_LENGTH} letters, not a unit,
     * not numeric).
     */
    public static String sanitizeRuleKeyword(String raw) {
        var normalized = normalize(raw);
        if (normalized.isBlank()) return "";
        var tokens = new ArrayList<>(Arrays.asList(normalized.split(" ")));
        while (!tokens.isEmpty() && isNoiseToken(tokens.get(0))) tokens.remove(0);
        while (!tokens.isEmpty() && isNoiseToken(tokens.get(tokens.size() - 1))) tokens.remove(tokens.size() - 1);
        return tokens.isEmpty() ? "" : String.join(" ", tokens);
    }

    private static boolean isNoiseToken(String token) {
        if (token.length() < MIN_KEYWORD_TOKEN_LENGTH) return true;
        if (token.chars().allMatch(Character::isDigit)) return true;
        return KEYWORD_NOISE_TOKENS.contains(token);
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        var stripped = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        var cleaned = stripped.toLowerCase()
                .replaceAll("[^a-z0-9 ]", " ")
                // Split glued digit↔letter boundaries so "detox350ml" tokenizes as
                // "detox 350 ml" — without this the dictionary/alias token matching
                // never sees the product word or the size.
                .replaceAll("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])", " ")
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
