package com.relyon.economizaai.service.sefaz;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalizes the free-text commercial unit (uCom) merchants print on the
 * NFC-e. ERPs suffix or spell it arbitrarily — WMS/Sam's Club prints
 * "UND9"/"KG9"/"UND8" (unit + an internal tax-group digit), others use
 * "UND"/"UNID" for unit — which would fragment price-per-unit comparisons
 * across markets. Applied by every ingestion path (native scrapers and
 * Infosimples) so the stored unit is comparable.
 */
public final class UnitNormalizer {

    private static final Pattern LETTERS_THEN_DIGITS = Pattern.compile("([A-Z]+)\\d+");
    /** Legitimate dimensioned units — the digit is part of the unit, not ERP noise. */
    private static final Set<String> DIMENSIONED_UNITS = Set.of("M2", "M3");
    private static final Map<String, String> ALIASES = Map.of(
            "UND", "UN",
            "UNID", "UN",
            "UNI", "UN");

    private UnitNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var unit = raw.trim().toUpperCase();
        if (!DIMENSIONED_UNITS.contains(unit)) {
            var matcher = LETTERS_THEN_DIGITS.matcher(unit);
            if (matcher.matches()) {
                unit = matcher.group(1);
            }
        }
        return ALIASES.getOrDefault(unit, unit);
    }
}
