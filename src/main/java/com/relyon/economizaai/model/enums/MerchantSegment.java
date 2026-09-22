package com.relyon.economizaai.model.enums;

/**
 * Business segment of a merchant, verified from its CNPJ's CNAE (economic
 * activity) via an external registry. Drives category fallbacks (e.g. an
 * unrecognized item at a PHARMACY merchant defaults to ProductCategory.HEALTH)
 * and the merchant support gate: recurring food/essentials RETAIL is supported,
 * food SERVICE (one-off meals) is rejected, everything else is a grey zone —
 * ingested for the user but held out of the collaborative index until reviewed.
 */
public enum MerchantSegment {
    UNKNOWN,      // not classified yet (or lookup failed) — grey zone
    SUPERMARKET,  // CNAE 4711* / 4712* (hiper/super/minimercados)
    PHARMACY,     // CNAE 4771* (produtos farmacêuticos)
    FOOD_RETAIL,  // CNAE 4721*-4724* / 4729* (padarias, açougues, bebidas, hortifrúti, conveniência)
    FOOD_SERVICE, // CNAE 56* (restaurantes, bares, lanchonetes) — rejected, non-recurring purchases
    OTHER         // classified, but none of the above — grey zone, admin reviews
}
