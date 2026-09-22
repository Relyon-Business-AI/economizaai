package com.relyon.economizaai.model.enums;

/**
 * A volume-gated home-screen feature whose availability the FE needs in order to
 * render a "coming soon / building" lock instead of a bare empty state on a
 * brand-new account. See {@code HomeAvailabilityService}.
 */
public enum HomeFeature {
    // Personal gates — unlock as the household scans more receipts.
    CONSUMPTION_PREDICTIONS,
    SUGGESTED_LIST,
    PERSONAL_PROMOS,
    PREFERENCES,
    // Collaborative gates — unlock as more households contribute to the index.
    COMMUNITY_DEALS,
    COMMUNITY_PROMOS,
    BEST_MARKETS,
    REFERENCE_PRICE
}
