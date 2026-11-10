package com.relyon.economizaai.service.priceindex;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Robust statistics for price samples — resistant to outliers that a plain
 * median still inherits when the sample is tiny.
 *
 * <p>Why: a community promo's "% mais barato" is computed against a baseline and
 * a recent median. A single bogus low observation (e.g. one R$2,98 scan when the
 * real typical is ~R$5,49) can BE the median of a 1–2 row recent window and
 * manufacture a "42% mais barato" that never existed. The median-absolute-
 * deviation (MAD) filter drops values that sit more than {@code threshold}
 * robust deviations from the median before the drop is computed.
 */
public final class RobustStats {

    private RobustStats() {}

    /** Scale factor making MAD a consistent estimator of the standard deviation for normal data. */
    private static final BigDecimal MAD_TO_SIGMA = BigDecimal.valueOf(1.4826);

    /** Default number of robust deviations beyond which a value is an outlier. */
    public static final double DEFAULT_MAD_THRESHOLD = 3.0;

    /**
     * Returns the values within {@code madThreshold} robust deviations of the
     * median. Samples of fewer than 4 points are returned unchanged — MAD is
     * meaningless on 1–3 points, and over-trimming a tiny sample would itself
     * distort the result. When MAD is zero (≥ half the values are identical) the
     * sample is already tight, so everything is kept.
     */
    public static List<BigDecimal> withoutOutliers(List<BigDecimal> values, double madThreshold) {
        if (values == null || values.size() < 4) return values;
        var median = PriceIndexService.median(values);
        var deviations = values.stream()
                .map(value -> value.subtract(median).abs())
                .toList();
        var mad = PriceIndexService.median(deviations);
        if (mad == null || mad.signum() == 0) return values;
        var bound = mad.multiply(MAD_TO_SIGMA).multiply(BigDecimal.valueOf(madThreshold))
                .setScale(6, RoundingMode.HALF_UP);
        var kept = values.stream()
                .filter(value -> value.subtract(median).abs().compareTo(bound) <= 0)
                .toList();
        // Never return an empty list — if the bound excluded everything (shouldn't
        // happen, the median itself is always within bound), fall back to the input.
        return kept.isEmpty() ? values : kept;
    }

    /** Overload using {@link #DEFAULT_MAD_THRESHOLD}. */
    public static List<BigDecimal> withoutOutliers(List<BigDecimal> values) {
        return withoutOutliers(values, DEFAULT_MAD_THRESHOLD);
    }
}
