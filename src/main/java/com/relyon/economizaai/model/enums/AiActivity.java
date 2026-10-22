package com.relyon.economizaai.model.enums;

/** Activity label on every AI call — the spend panel groups cost/tokens by this. */
public enum AiActivity {
    RULE_SUGGESTION(AiModelTier.EXTRACTOR),
    BRAND_SUGGESTION(AiModelTier.EXTRACTOR),
    CATEGORY_REVIEW(AiModelTier.EXTRACTOR),
    DUPLICATE_JUDGE(AiModelTier.CURATOR),
    CONSENSUS_JUDGE(AiModelTier.CURATOR),
    MERCHANT_CLASSIFY(AiModelTier.EXTRACTOR),
    FRIENDLY_NAMES(AiModelTier.EXTRACTOR),
    ANOMALY_SCAN(AiModelTier.CURATOR),
    TEST_CLASSIFY(AiModelTier.EXTRACTOR),
    ITEM_CLASSIFY(AiModelTier.EXTRACTOR),  // real-time fallback when the deterministic categorizer misses an item at scan time
    ITEM_CORRECTION(AiModelTier.EXTRACTOR), // user-triggered correction of a misclassified receipt item
    RECEIPT_RESCUE(AiModelTier.EXTRACTOR), // parse-rescue: extracts the receipt from fetched SEFAZ HTML the state parser failed on
    FINE_CATEGORY_CLASSIFY(AiModelTier.CURATOR), // classifies products into the fine 2-level taxonomy leaf when keywords are low-confidence
    PHOTO_EXTRACT(AiModelTier.EXTRACTOR), // vision: extracts items from a photographed printed receipt (origin=PHOTO fallback)
    MODEL_BENCHMARK(AiModelTier.EXTRACTOR); // classifica o golden set com um modelo explícito pra medir acurácia pura

    private final AiModelTier tier;

    AiActivity(AiModelTier tier) {
        this.tier = tier;
    }

    /** Qual tier (logo, qual provedor/modelo configurado) serve esta atividade. */
    public AiModelTier tier() {
        return tier;
    }
}
