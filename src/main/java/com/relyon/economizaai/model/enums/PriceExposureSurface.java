package com.relyon.economizaai.model.enums;

/** The screen (or flow) of the app that showed a product's price at a market to a person. */
public enum PriceExposureSurface {
    /** "Ofertas pra você". */
    DEALS,
    /** Cheapest markets of one product (product screen, barcode scan). */
    BEST_MARKETS,
    /** Community promotions near the person. */
    COMMUNITY_PROMOS,
    /** Shopping list split across markets. */
    SHOPPING_PLAN,
    /** Prices of one market (market screen). */
    MARKET_PRICES
}
