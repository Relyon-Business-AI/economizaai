package com.relyon.economizaai.model.enums;

public enum EanCatalogSource {
    OPEN_FOOD_FACTS,  // imported from Open Food Facts data dump
    CURATED_IMPORT,   // manually uploaded by admin (known-good data)
    USER_CONFIRMED,   // confirmed by a user correction / consensus
    LIVE_API          // fetched on-demand from the live OFF-family API (marks the
                      // barcode as already checked, so we don't re-query it even
                      // when the API had no category / didn't know it)
}
