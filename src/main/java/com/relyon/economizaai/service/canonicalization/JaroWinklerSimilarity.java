package com.relyon.economizaai.service.canonicalization;

/**
 * Jaro-Winkler similarity score in [0.0, 1.0]. Higher = more similar.
 * Boosts the score when the strings share a common prefix (up to 4 chars),
 * which works well for grocery item descriptions where the first tokens
 * (product type, brand) carry most of the discriminating signal —
 * "ARROZ TIO J 5KG" and "ARROZ TIO JOAO 5KG" share a long prefix and
 * score high; "ARROZ TIO J 5KG" and "FEIJAO PRETO 1KG" share little
 * and score low.
 *
 * <p>Self-contained (no external dependency). Pure function — safe to
 * call from any thread, no allocation beyond two boolean arrays per call.
 */
public final class JaroWinklerSimilarity {

    private static final double SCALING_FACTOR = 0.1;
    private static final int PREFIX_MAX = 4;

    private JaroWinklerSimilarity() {}

    public static double score(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        if (s1.equals(s2)) return 1.0;
        var len1 = s1.length();
        var len2 = s2.length();
        if (len1 == 0 || len2 == 0) return 0.0;

        var s1Matches = new boolean[len1];
        var s2Matches = new boolean[len2];
        var matches = findMatches(s1, s2, s1Matches, s2Matches);
        if (matches == 0) return 0.0;

        var transpositions = countTranspositions(s1, s2, s1Matches, s2Matches);
        var jaro = jaroScore(matches, transpositions, len1, len2);
        return applyPrefixBoost(s1, s2, jaro);
    }

    /**
     * Mark, in s1Matches/s2Matches, the characters that match within the Jaro
     * match window, and return how many matched.
     */
    private static int findMatches(String s1, String s2, boolean[] s1Matches, boolean[] s2Matches) {
        var matchDistance = Math.max(s1.length(), s2.length()) / 2 - 1;
        var matches = 0;
        for (var i = 0; i < s1.length(); i++) {
            var start = Math.max(0, i - matchDistance);
            var end = Math.min(i + matchDistance + 1, s2.length());
            for (var j = start; j < end; j++) {
                if (s2Matches[j] || s1.charAt(i) != s2.charAt(j)) continue;
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }
        return matches;
    }

    /** Count matched characters that appear in a different order between the strings. */
    private static int countTranspositions(String s1, String s2, boolean[] s1Matches, boolean[] s2Matches) {
        var k = 0;
        var transpositions = 0;
        for (var i = 0; i < s1.length(); i++) {
            if (!s1Matches[i]) continue;
            while (!s2Matches[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }
        return transpositions;
    }

    private static double jaroScore(int matches, int transpositions, int len1, int len2) {
        var m = (double) matches;
        return (m / len1 + m / len2 + (m - transpositions / 2.0) / m) / 3.0;
    }

    /** Boost the Jaro score for a shared leading prefix (up to PREFIX_MAX chars). */
    private static double applyPrefixBoost(String s1, String s2, double jaro) {
        var prefix = 0;
        var prefixLimit = Math.min(Math.min(s1.length(), s2.length()), PREFIX_MAX);
        for (var i = 0; i < prefixLimit; i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }
        return jaro + prefix * SCALING_FACTOR * (1.0 - jaro);
    }
}
