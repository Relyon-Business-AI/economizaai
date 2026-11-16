package com.relyon.economizaai.model.enums;

/** Where in the app the person saw the price before buying cheaper. */
public enum AppSavingSource {
    /** A deal the app surfaced ("Ofertas pra você" and its digest). */
    DEALS,
    /** A price alert the household set. */
    PRICE_ALERT,
    /** The cheapest-markets comparison of a product. */
    BEST_MARKETS,
    /** Community promotions near the person. */
    COMMUNITY_PROMOS,
    /** The shopping list split across markets. */
    SHOPPING_PLAN,
    /** The prices of one market (market screen). */
    MARKET_PRICES;

    /** The source a screen's exposure turns into — the two enums share their names. */
    public static AppSavingSource of(PriceExposureSurface surface) {
        return valueOf(surface.name());
    }
}
