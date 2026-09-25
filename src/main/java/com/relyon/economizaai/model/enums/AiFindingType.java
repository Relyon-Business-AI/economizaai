package com.relyon.economizaai.model.enums;

/** What kind of proposal an AI finding carries (drives the applier + FE rendering). */
public enum AiFindingType {
    /** Unmatched description → proposed curated rule (keyword/generic/brand/category). */
    MISSING_RULE,
    /** Brand text seen on receipts but absent from the registry → proposed alias. */
    MISSING_BRAND,
    /** Product whose current category looks wrong → proposed category. */
    SUSPECT_CATEGORY,
    /** Two products that look like the same physical item → proposed merge. */
    DUPLICATE,
    /** A consensus graduation that looks wrong/gamed → flagged for human review. */
    CONSENSUS_REVIEW,
    /** A merchant whose segment classification looks wrong (pharmacy vs market). */
    MERCHANT_REVIEW,
    /** Product missing a friendly generic name → proposed name. */
    FRIENDLY_NAME,
    /** Receipt-level anomaly (absurd price, duplicated line) — informational. */
    ANOMALY
}
