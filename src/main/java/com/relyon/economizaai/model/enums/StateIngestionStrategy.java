package com.relyon.economizaai.model.enums;

/**
 * A layer of the experimental-state fallback chain, ordered by cost: first the
 * portal URL carried by the QR itself (free), then Infosimples (paid).
 */
public enum StateIngestionStrategy {

    /** Direct GET of the QR's own portal URL, parsed with the shared responsive-DANFE parser. */
    QR_PORTAL,

    /** Paid Infosimples by-chave lookup (works for every UF). */
    INFOSIMPLES,

    /**
     * A state's dedicated/verified adapter (SVRS-shared, MS, SC, …). Only its
     * PARSE_FAILED outcomes are recorded — the regression signal that a
     * previously-working portal changed its HTML format.
     */
    VERIFIED_ADAPTER
}
