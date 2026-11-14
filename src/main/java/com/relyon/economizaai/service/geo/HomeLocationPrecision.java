package com.relyon.economizaai.service.geo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Precision kept for a user's home point. Three decimal places of a degree is a grid of roughly
 * 110 m: enough for distances and the nearby radius, not enough to single out one house.
 */
public final class HomeLocationPrecision {

    public static final int DECIMAL_PLACES = 3;

    private HomeLocationPrecision() {}

    public static BigDecimal coarsen(BigDecimal coordinate) {
        return coordinate == null ? null : coordinate.setScale(DECIMAL_PLACES, RoundingMode.HALF_UP);
    }
}
