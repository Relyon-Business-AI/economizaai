package com.relyon.economizaai.service.sefaz;

import org.jsoup.nodes.Element;

import java.util.regex.Pattern;

/**
 * Heuristic detector for "this item was on promo" signals in NFC-e HTML.
 * Two complementary checks:
 * <ol>
 *   <li><b>Structural</b> — presence of a discount cell / class on the item
 *       row (SEFAZ portals carry these inconsistently per state).</li>
 *   <li><b>Lexical</b> — Portuguese promo keywords in the item description
 *       (catches markets that bake "OFERTA" / "PROMO" into the line text).</li>
 * </ol>
 *
 * <p>False positives are tolerable here: a lexical hit only excludes the
 * row from baseline-price calcs and surfaces a "oferta" tag in the user's
 * receipt detail. Worst case we under-count baseline samples slightly.
 *
 * <p>Single responsibility: classify one row. No side effects, no IO.
 */
public final class PromoMarkerDetector {

    /**
     * Compiled once. Matches the most common PT-BR promo stems
     * case-insensitively, with trailing inflection (promo/promoção/promocao,
     * oferta/ofertas, etc.). UNICODE_CHARACTER_CLASS is on so `ç` and `ã`
     * count as word chars — otherwise "PROMO\b" would match inside
     * "PROMOÇÃO" and we'd lose the boundary check.
     */
    private static final Pattern PROMO_KEYWORDS = Pattern.compile(
            "(?iU)\\b(promo\\w*|oferta\\w*|liquida\\w*|desconto\\w*|leve\\s*\\d|combo\\w*)\\b");

    /** CSS selectors observed on SEFAZ portals when an item carries a discount. */
    private static final String DISCOUNT_SELECTORS = ".RvDesc, .qDes, .Rdesc, .desconto, td.desc, span.desc";

    private PromoMarkerDetector() {}

    public static boolean isPromo(Element row, String description) {
        return hasStructuralMarker(row) || hasLexicalMarker(description);
    }

    private static boolean hasStructuralMarker(Element row) {
        if (row == null) return false;
        return row.selectFirst(DISCOUNT_SELECTORS) != null;
    }

    private static boolean hasLexicalMarker(String description) {
        if (description == null || description.isBlank()) return false;
        return PROMO_KEYWORDS.matcher(description).find();
    }
}
