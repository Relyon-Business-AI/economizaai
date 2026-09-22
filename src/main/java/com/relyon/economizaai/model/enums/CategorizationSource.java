package com.relyon.economizaai.model.enums;

/**
 * Tracks where a Product's category/genericName came from. Used for:
 * - ML training (DICTIONARY, USER, and CONSENSUS are trusted signal — ML
 *   never trains on its own predictions to avoid feedback contamination).
 * - Auto-promotion (LEARNED_DICTIONARY entries come from stable ML
 *   predictions promoted by AutoPromotionService).
 * - Audit ("why is this product categorized as X?").
 * - Reset targeting: CONSENSUS products can be reverted to NONE by
 *   the admin reset-consensus endpoint, leaving USER/DICTIONARY intact.
 */
public enum CategorizationSource {
    NONE,                // not categorized yet
    DICTIONARY,          // curated_dictionary_entries table (admin-managed)
    LEARNED_DICTIONARY,  // auto-promoted from stable ML predictions
    ML,                  // multinomial NB inference (Phase 2.5b)
    MERCHANT,            // inferred from the merchant type (e.g. pharmacy) when otherwise OTHER
    USER,                // explicit PATCH/create from an admin user
    CONSENSUS,           // graduated by ConsensusPromotionService (≥N households agreed)
    LLM                  // enriched by the LLM teacher layer (LlmEnrichmentService)
}
