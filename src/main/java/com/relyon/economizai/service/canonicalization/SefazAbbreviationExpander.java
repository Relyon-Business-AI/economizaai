package com.relyon.economizai.service.canonicalization;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Expands common, UNAMBIGUOUS SEFAZ receipt abbreviations to their full form, so
 * categorization AND canonicalization see one canonical spelling. Runs inside
 * {@link DescriptionNormalizer}: "MANT ELEGE" and "MANTEIGA ELEGE" both normalize
 * to "manteiga elege" — matching the SAME product (no duplicate) and the SAME
 * dictionary entry ("manteiga"), instead of splitting.
 *
 * <p>Only high-confidence abbreviations are listed. Ambiguous ones are intentionally
 * omitted (e.g. "cond" = condensado|condicionador, "sab" = sabor|sabao, "refrig" =
 * refrigerante|refrigerado, "pres" = presunto|presidente). Whole-token matching is
 * used so a real word that merely starts with an abbreviation (e.g. "mantiqueira")
 * is never expanded. Extend the maps as new patterns show up in the miss stream.
 */
final class SefazAbbreviationExpander {

    private SefazAbbreviationExpander() {}

    /** Multi-word abbreviations, matched on whole-phrase boundaries. */
    private static final Map<String, String> PHRASES = Map.ofEntries(
            Map.entry("leite po", "leite em po")
    );

    /** Single whole-token abbreviations. */
    private static final Map<String, String> TOKENS = Map.ofEntries(
            Map.entry("mant", "manteiga"),
            Map.entry("marg", "margarina"),
            Map.entry("mortad", "mortadela"),
            Map.entry("bisc", "biscoito"),
            Map.entry("desinf", "desinfetante"),
            Map.entry("amac", "amaciante"),
            Map.entry("deterg", "detergente"),
            Map.entry("refri", "refrigerante"),
            Map.entry("choc", "chocolate"),
            Map.entry("achoc", "achocolatado"),
            Map.entry("iog", "iogurte"),
            Map.entry("req", "requeijao"),
            Map.entry("sorv", "sorvete"),
            Map.entry("presunt", "presunto"),
            Map.entry("mussar", "mussarela"),
            Map.entry("parmes", "parmesao"),
            Map.entry("cerv", "cerveja"),
            Map.entry("sabonet", "sabonete")
    );

    /** {@code normalized} must already be lowercase, accent-free and single-spaced. */
    static String expand(String normalized) {
        if (normalized == null || normalized.isEmpty()) return normalized == null ? "" : normalized;
        var text = " " + normalized + " ";
        for (var phrase : PHRASES.entrySet()) {
            text = text.replace(" " + phrase.getKey() + " ", " " + phrase.getValue() + " ");
        }
        return Arrays.stream(text.trim().split(" "))
                .map(token -> TOKENS.getOrDefault(token, token))
                .collect(Collectors.joining(" "));
    }
}
