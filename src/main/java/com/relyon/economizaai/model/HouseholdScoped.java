package com.relyon.economizaai.model;

/**
 * A household-scoped entity that can MOVE between households on merge/split.
 * `household` is where the row currently lives (rewritten on merge); `originHousehold`
 * is its immutable home (set at creation, used to restore on split). Implemented by
 * every movable entity so {@link com.relyon.economizaai.service.HouseholdMergeService}
 * can move them generically instead of duplicating logic per type.
 */
public interface HouseholdScoped {

    Household getHousehold();

    void setHousehold(Household household);

    Household getOriginHousehold();

    void setOriginHousehold(Household originHousehold);

    /**
     * The value that, together with household_id, forms this entity's per-household
     * UNIQUE key — i.e. what makes two rows "the same thing" during a merge collision.
     * Two rows with the same collisionKey in the same household can't coexist (the DB
     * constraint), so the merge keeps the host's and parks the joiner's as shadowed.
     * Entities with NO per-household unique constraint (e.g. ShoppingList) return null,
     * meaning "never collides — always movable".
     */
    String collisionKey();
}
