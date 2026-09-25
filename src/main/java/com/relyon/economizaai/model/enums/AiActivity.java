package com.relyon.economizaai.model.enums;

/** Activity label on every AI call — the spend panel groups cost/tokens by this. */
public enum AiActivity {
    RULE_SUGGESTION,
    BRAND_SUGGESTION,
    CATEGORY_REVIEW,
    DUPLICATE_JUDGE,
    CONSENSUS_JUDGE,
    MERCHANT_CLASSIFY,
    FRIENDLY_NAMES,
    ANOMALY_SCAN,
    TEST_CLASSIFY,
    ITEM_CLASSIFY,  // real-time fallback when the deterministic categorizer misses an item at scan time
    ITEM_CORRECTION // user-triggered correction of a misclassified receipt item
}
