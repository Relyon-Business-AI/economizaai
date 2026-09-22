package com.relyon.economizaai.service.notifications;

import java.math.BigDecimal;

/**
 * Progressive relevance threshold for "this got cheaper" signals: the required
 * drop fraction scales with price. A 20% cut on a R$1 item matters; on a R$200
 * item even 5% is real money. The required drop is interpolated on log-price
 * between the anchors below and clamped to [{@link #MIN_DROP}, {@link #MAX_DROP}].
 *
 * <p>Shared by the {@link NotificationRuleEngine} (cheaper-market rule firing)
 * and the deals screen ({@code DealsService}) so both apply the exact same
 * "is this discount worth surfacing?" bar.
 */
public final class RelevanceThreshold {

    private static final double MAX_DROP = 0.20;                          // cheap items need a deep cut
    private static final double MIN_DROP = 0.05;                          // pricey items: small % still matters
    private static final BigDecimal LOW_PRICE_ANCHOR = new BigDecimal("1");    // R$1  -> 20% required
    private static final BigDecimal HIGH_PRICE_ANCHOR = new BigDecimal("200"); // R$200 -> 5% required

    private RelevanceThreshold() {}

    /**
     * Required drop fraction for a price, scaled by that price: a deep cut on a
     * cheap item, a shallow one on a pricey item.
     */
    public static double requiredDropFraction(BigDecimal price) {
        var value = price.doubleValue();
        var low = LOW_PRICE_ANCHOR.doubleValue();
        var high = HIGH_PRICE_ANCHOR.doubleValue();
        if (value <= low) return MAX_DROP;
        if (value >= high) return MIN_DROP;
        var position = Math.log(value / low) / Math.log(high / low); // 0 at low, 1 at high
        return MAX_DROP - (MAX_DROP - MIN_DROP) * position;
    }
}
