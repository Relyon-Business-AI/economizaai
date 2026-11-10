package com.relyon.economizaai.service.canonicalization;

import java.util.regex.Pattern;

/**
 * Tells a REAL (authoritative) GTIN apart from a PLACEHOLDER / internal
 * pseudo-code. Small markets emit internal codes for the same physical product
 * — commonly an all-zeros-prefixed sequence like {@code 00000001124676} — which
 * must NOT be treated as a trustworthy GTIN when deciding merges.
 *
 * <p>Why it matters: a placeholder-EAN item ("CHOC DIVINE 50% ZERO 70G") must
 * never be absorbed into a product carrying a DIFFERENT real EAN
 * ("CHOCOLATE DIVINE 70G AO LEITE SGLUT", EAN 7899516211450) on name alone. EAN
 * is the primary match key ONLY when it is authoritative.
 *
 * <p>Convention (matches the parse-time GTIN gating): an EAN is all-digits,
 * 8–14 long. A code that is all zeros, or a long run of leading zeros followed
 * by a short tail, is a placeholder — a genuine GTIN-8/12/13/14 does not begin
 * with six or more zeros.
 */
public final class EanAuthority {

    private EanAuthority() {}

    /** Six or more leading zeros ⇒ internal/placeholder code, never a real GTIN. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^0{6,}\\d*$");

    private static final int MIN_GTIN_LENGTH = 8;
    private static final int MAX_GTIN_LENGTH = 14;

    /** True when {@code ean} is a real, trustworthy GTIN (not blank, not a placeholder). */
    public static boolean isReal(String ean) {
        if (ean == null) return false;
        var trimmed = ean.trim();
        if (trimmed.length() < MIN_GTIN_LENGTH || trimmed.length() > MAX_GTIN_LENGTH) return false;
        if (!trimmed.chars().allMatch(Character::isDigit)) return false;
        return !PLACEHOLDER.matcher(trimmed).matches();
    }

    /** True when {@code ean} is present but NOT authoritative (placeholder/internal). */
    public static boolean isPlaceholder(String ean) {
        if (ean == null || ean.isBlank()) return false;
        return !isReal(ean);
    }
}
