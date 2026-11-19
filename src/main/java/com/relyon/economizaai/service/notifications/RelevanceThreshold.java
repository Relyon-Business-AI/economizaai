package com.relyon.economizaai.service.notifications;

import java.util.concurrent.atomic.AtomicReference;
import java.math.BigDecimal;

/**
 * Progressive relevance threshold for "this got cheaper" signals: the required
 * drop fraction scales with price. A 20% cut on a R$1 item matters; on a R$200
 * item even 5% is real money. The required drop is interpolated on log-price
 * between the two ends of the {@link Curve} in effect and clamped to its two drops.
 *
 * <p>Shared by the {@link NotificationRuleEngine} (cheaper-market rule firing)
 * and the deals screen ({@code DealsService}) so both apply the exact same
 * "is this discount worth surfacing?" bar.
 */
public final class RelevanceThreshold {

    /** The bar in effect: the drop required at each end of the price range. */
    public record Curve(int maxDropPct, int minDropPct, BigDecimal lowPrice, BigDecimal highPrice) {

        /** R$1 needs a 20% cut, R$200 or more needs 5%. */
        public static final Curve DEFAULT = new Curve(20, 5, new BigDecimal("1"), new BigDecimal("200"));

        public Curve withMaxDropPct(int pct) { return new Curve(pct, minDropPct, lowPrice, highPrice); }
        public Curve withMinDropPct(int pct) { return new Curve(maxDropPct, pct, lowPrice, highPrice); }
        public Curve withLowPrice(BigDecimal price) { return new Curve(maxDropPct, minDropPct, price, highPrice); }
        public Curve withHighPrice(BigDecimal price) { return new Curve(maxDropPct, minDropPct, lowPrice, price); }
    }

    private static final AtomicReference<Curve> CURVE = new AtomicReference<>(Curve.DEFAULT);

    private RelevanceThreshold() {}

    public static Curve curve() {
        return CURVE.get();
    }

    /** Replaces the bar for every reader (changed from the admin settings). */
    public static void useCurve(Curve curve) {
        CURVE.set(curve);
    }

    /**
     * Required drop fraction for a price, scaled by that price: a deep cut on a
     * cheap item, a shallow one on a pricey item.
     */
    public static double requiredDropFraction(BigDecimal price) {
        var curve = CURVE.get();
        var value = price.doubleValue();
        var low = curve.lowPrice().doubleValue();
        var high = curve.highPrice().doubleValue();
        var maxDrop = curve.maxDropPct() / 100.0;
        var minDrop = curve.minDropPct() / 100.0;
        if (value <= low || high <= low) return maxDrop;
        if (value >= high) return minDrop;
        var position = Math.log(value / low) / Math.log(high / low); // 0 at low, 1 at high
        return maxDrop - (maxDrop - minDrop) * position;
    }
}
