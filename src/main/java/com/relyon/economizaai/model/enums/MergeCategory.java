package com.relyon.economizaai.model.enums;

/**
 * The user-selectable categories of data a joining member can bring into the target
 * household (Phase 1). Each maps to one movable {@code HouseholdScoped} entity type.
 * The joiner ticks which of these to merge; everything unticked stays on their
 * original household (restorable on split).
 */
public enum MergeCategory {
    RECEIPTS,
    SHOPPING_LISTS,
    MANUAL_PURCHASES,
    CONSUMPTION_SNOOZES,
    CATEGORY_OVERRIDES,
    CUSTOM_CATEGORIES,
    PRODUCT_ALIASES,
    MARKET_ALIASES,
    BRAND_PREFERENCES
}
