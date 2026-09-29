package com.relyon.economizaai.model.enums;

/**
 * Tracks where a Product's category/genericName came from. Used for:
 * - Auto-promotion (LEARNED_DICTIONARY entries are consolidated from consistent
 *   LLM classifications by AutoPromotionService).
 * - Audit ("why is this product categorized as X?").
 * - Reset targeting: CONSENSUS products can be reverted to NONE by
 *   the admin reset-consensus endpoint, leaving USER/DICTIONARY intact.
 */
public enum CategorizationSource {
    NONE,                // not categorized yet
    DICTIONARY,          // curated_dictionary_entries table (admin-managed)
    LEARNED_DICTIONARY,  // auto-promoted from consistent LLM classifications
    ML,                  // RETIRED tombstone — trained ML classifier scaffolded but never
                         // activated (see HELP.md). No product ever carries this; kept only
                         // because removing a persisted enum value is risky and pointless.
    MERCHANT,            // inferred from the merchant type (e.g. pharmacy) when otherwise OTHER
    USER,                // explicit PATCH/create from an admin user
    CONSENSUS,           // graduated by ConsensusPromotionService (≥N households agreed)
    LLM                  // enriched by the LLM teacher layer (LlmEnrichmentService)
}
