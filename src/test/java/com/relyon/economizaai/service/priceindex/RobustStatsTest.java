package com.relyon.economizaai.service.priceindex;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RobustStatsTest {

    private static List<BigDecimal> prices(String... values) {
        return Arrays.stream(values).map(BigDecimal::new).toList();
    }

    @Test
    void dropsExtremeLowOutlier() {
        // Typical ~5.49 with one bogus 2.98 — the outlier must be trimmed.
        var kept = RobustStats.withoutOutliers(
                prices("5.49", "5.50", "5.45", "5.60", "5.40", "2.98"));
        assertFalse(kept.contains(new BigDecimal("2.98")), "the 2.98 outlier is removed");
        assertTrue(kept.contains(new BigDecimal("5.49")));
    }

    @Test
    void keepsTightSampleUnchanged() {
        var input = prices("5.49", "5.50", "5.45", "5.60");
        var kept = RobustStats.withoutOutliers(input);
        assertEquals(input.size(), kept.size());
    }

    @Test
    void tinySampleReturnedUnchanged() {
        // Fewer than 4 points → MAD is meaningless, return as-is.
        var input = prices("2.98", "5.49", "5.50");
        assertEquals(input, RobustStats.withoutOutliers(input));
    }

    @Test
    void neverReturnsEmpty() {
        var input = prices("1.00", "2.00", "3.00", "100.00");
        var kept = RobustStats.withoutOutliers(input);
        assertFalse(kept.isEmpty());
    }

    @Test
    void nullAndEmptyReturnedAsIs() {
        assertEquals(null, RobustStats.withoutOutliers(null));
        assertTrue(RobustStats.withoutOutliers(List.of()).isEmpty());
    }
}
