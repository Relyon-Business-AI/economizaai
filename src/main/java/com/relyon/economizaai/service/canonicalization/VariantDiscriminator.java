package com.relyon.economizaai.service.canonicalization;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * Decides whether two product descriptions describe the SAME variant of a
 * product, or two DIFFERENT variants that merely share a base name.
 *
 * <p>Background (real bug): "CHOC DIVINE 50% ZERO 70G" (placeholder EAN) was
 * merged into "CHOCOLATE DIVINE 70G AO LEITE SGLUT" (real EAN) because the
 * metadata dedup + fuzzy name match ignored the DISTINGUISHING tokens — one is
 * a 50% zero-sugar bar, the other a milk gluten-free bar; same brand + pack,
 * different products. They must never canonicalize to one row.
 *
 * <p>Distinguishing tokens come in two strengths, detected by PATTERN rather
 * than by enumerating every value:
 * <ul>
 *   <li><b>STRONG</b> — sugar/intensity ({@code \d+%}, "zero", "diet", "light",
 *       "sem acucar"/"s/acucar"/"zero acucar") and dietary claims ("sem gluten"/
 *       "sglut", "sem lactose"/"zero lactose"). These are rarely dropped from a
 *       receipt name, so STRONG present-on-one-side-only IS a real difference.
 *       Also covers variant/type/line markers that behave the same way — sugar
 *       type ("demerara" vs refined), detergent lines ("platinum", "titanium"),
 *       pasta shapes ("parafuso", "espaguete", …).</li>
 *   <li><b>WEAK</b> — flavor words (chocolate, morango, baunilha, …) and soft
 *       descriptors ("integral", "desnatado", "tradicional"). A receipt often
 *       abbreviates these away ("LEITE ITAMBE INT" vs "…INTEGRAL"), so WEAK is a
 *       difference ONLY when both sides carry a (different) weak token — never
 *       when one side simply omits it.</li>
 * </ul>
 *
 * <p>Two descriptions may merge when their STRONG signatures are equal AND their
 * WEAK signatures don't directly contradict (both non-empty and unequal).
 *
 * <p>Pure, stateless, thread-safe. Operates on the output of
 * {@link DescriptionNormalizer#normalize} (accent-stripped, lowercased,
 * punctuation→space, digit↔letter split, SEFAZ-expanded). Normalization drops
 * the literal {@code %}, so the percentage pattern runs on the RAW description.
 */
public final class VariantDiscriminator {

    private VariantDiscriminator() {}

    /** A number immediately followed by % — "50%", "0 %", "70 %". Runs on the raw (un-normalized) text. */
    private static final Pattern PERCENTAGE = Pattern.compile("(\\d+)\\s*%");

    /** STRONG single-token sugar/intensity markers — presence vs absence is always distinguishing. */
    private static final Set<String> STRONG_SINGLE_TOKENS = Set.of(
            "zero", "diet", "light", "sglut", "slac");

    /**
     * STRONG variant/type/line markers — a distinct sub-line of the same brand that is
     * (almost) never dropped from a receipt name, so presence-on-one-side-only IS a real
     * difference (treated like a STRONG token, not a WEAK flavor). Covers:
     * <ul>
     *   <li>sugar TYPE — "demerara" vs the implicit "refinado" (a demerara item must never
     *       merge into a refined-sugar product);</li>
     *   <li>detergent/soap PERFORMANCE LINES — "platinum", "titanium" (Ypê/ODD premium
     *       lines sit alongside the regular bottles under the same brand);</li>
     *   <li>pasta SHAPES — different cuts are different products (owner-approved split):
     *       "parafuso", "pena", "espaguete"/"spaghetti", "tricolor", "lasanha".</li>
     * </ul>
     * Normalized form (accent-stripped, lowercase).
     */
    private static final Set<String> VARIANT_LINE_TOKENS = Set.of(
            "demerara",
            "platinum", "titanium",
            "parafuso", "pena", "espaguete", "spaghetti", "tricolor", "lasanha");

    /**
     * STRONG multi-word markers normalized to a canonical tag so "s/acucar",
     * "sem acucar" and "zero acucar" all collapse to the same signature token.
     * Key = normalized phrase to look for; value = canonical tag emitted.
     */
    private static final Map<String, String> STRONG_PHRASES = Map.ofEntries(
            entry("sem acucar", "no_sugar"),
            entry("s acucar", "no_sugar"),
            entry("zero acucar", "no_sugar"),
            entry("sem gluten", "no_gluten"),
            entry("zero gluten", "no_gluten"),
            entry("sem lactose", "no_lactose"),
            entry("s lactose", "no_lactose"),
            entry("zero lactose", "no_lactose"),
            entry("sem sal", "no_salt"));

    /** Words that, following "zero", form a STRONG phrase already tagged (so "zero" isn't double-counted). */
    private static final Set<String> SUGAR_PHRASE_TAILS = Set.of("acucar", "lactose", "gluten", "sal");

    /**
     * WEAK flavor + descriptor tokens (the catalog-audit "sabor" vocabulary plus
     * soft dietary descriptors). A WEAK difference blocks a merge only when BOTH
     * sides carry a (different) weak token — one side omitting it is ambiguous.
     * Normalized form (accent-stripped, lowercase).
     */
    private static final Set<String> WEAK_TOKENS = Set.of(
            "chocolate", "baunilha", "morango", "coco", "limao", "laranja",
            "uva", "maracuja", "abacaxi", "manga", "pessego", "ameixa",
            "banana", "goiaba", "caramelo", "menta", "canela", "cafe",
            "avela", "amendoim", "doce de leite", "leite condensado",
            "frutas vermelhas", "chocolate branco", "meio amargo",
            "tradicional", "original", "natural",
            "integral", "desnatado", "semidesnatado", "organico", "defumado",
            // powdered-juice (Tang) + biscuit/dairy flavours seen mis-merged in prod
            "guarana", "tangerina", "framboesa", "cacau", "maca",
            "proteina", "lavanda", "orquidea",
            // tomato-sauce lines ("molho … manjericao" / "… pizza")
            "manjericao", "pizza",
            // savoury biscuit flavours
            "tomate", "queijo", "bacon", "provolone", "crostini");

    /**
     * True when the two raw descriptions may safely merge into one canonical
     * product (see class javadoc). When this returns false the callers keep them
     * as separate products even if base name, brand and pack size all match.
     */
    public static boolean sameVariant(String rawLeft, String rawRight) {
        var leftStrong = strongSignature(rawLeft);
        var rightStrong = strongSignature(rawRight);
        if (!leftStrong.equals(rightStrong)) return false;
        var leftWeak = weakSignature(rawLeft);
        var rightWeak = weakSignature(rawRight);
        // WEAK conflicts only when both sides are decorated AND disagree.
        if (!leftWeak.isEmpty() && !rightWeak.isEmpty() && !leftWeak.equals(rightWeak)) return false;
        return true;
    }

    /**
     * Full distinguishing-token signature (strong ∪ weak) as an ordered set —
     * for the audit report / logging. {@link #sameVariant} uses the strong/weak
     * split directly; this is the human-readable union.
     */
    public static Set<String> signature(String rawDescription) {
        var tokens = new LinkedHashSet<String>();
        tokens.addAll(strongSignature(rawDescription));
        tokens.addAll(weakSignature(rawDescription));
        return tokens;
    }

    private static Set<String> strongSignature(String rawDescription) {
        var tokens = new LinkedHashSet<String>();
        if (rawDescription == null || rawDescription.isBlank()) return tokens;
        addPercentages(rawDescription, tokens);
        var normalized = DescriptionNormalizer.normalize(rawDescription);
        addStrongPhrases(normalized, tokens);
        addStrongSingleTokens(normalized, tokens);
        addVariantLineTokens(normalized, tokens);
        return tokens;
    }

    private static Set<String> weakSignature(String rawDescription) {
        var tokens = new LinkedHashSet<String>();
        if (rawDescription == null || rawDescription.isBlank()) return tokens;
        var normalized = DescriptionNormalizer.normalize(rawDescription);
        for (var word : normalized.split(" ")) {
            if (WEAK_TOKENS.contains(word)) tokens.add(word);
        }
        var padded = " " + normalized + " ";
        for (var weak : WEAK_TOKENS) {
            if (weak.contains(" ") && padded.contains(" " + weak + " ")) tokens.add(weak);
        }
        return tokens;
    }

    private static void addPercentages(String rawDescription, Set<String> tokens) {
        var matcher = PERCENTAGE.matcher(rawDescription);
        while (matcher.find()) {
            tokens.add("pct" + matcher.group(1));
        }
    }

    private static void addStrongPhrases(String normalized, Set<String> tokens) {
        var padded = " " + normalized + " ";
        for (var entry : STRONG_PHRASES.entrySet()) {
            if (padded.contains(" " + entry.getKey() + " ")) {
                tokens.add(entry.getValue());
            }
        }
    }

    private static void addStrongSingleTokens(String normalized, Set<String> tokens) {
        var words = normalized.split(" ");
        for (var index = 0; index < words.length; index++) {
            var word = words[index];
            // "zero acucar" / "zero lactose" are already a phrase tag — don't also
            // emit a bare "zero" (it would spuriously differ from "sem acucar").
            if (word.equals("zero") && index + 1 < words.length
                    && SUGAR_PHRASE_TAILS.contains(words[index + 1])) {
                continue;
            }
            if (STRONG_SINGLE_TOKENS.contains(word)) {
                tokens.add(word);
            }
        }
    }

    /**
     * Emits the STRONG variant/line markers (sugar type, detergent line, pasta shape).
     * Tagged {@code line_} so they share the STRONG "present-on-one-side-is-a-difference"
     * semantics and never collide with a same-spelling weak flavour.
     */
    private static void addVariantLineTokens(String normalized, Set<String> tokens) {
        for (var word : normalized.split(" ")) {
            if (VARIANT_LINE_TOKENS.contains(word)) tokens.add("line_" + word);
        }
    }
}
