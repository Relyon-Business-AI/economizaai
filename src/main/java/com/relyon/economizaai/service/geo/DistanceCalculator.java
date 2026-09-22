package com.relyon.economizaai.service.geo;

import java.math.BigDecimal;

/**
 * Haversine distance between two points on Earth, in kilometres.
 * Accurate to within ~0.5% at this scale, no external dependency needed.
 */
public final class DistanceCalculator {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    private DistanceCalculator() {}

    public static double kmBetween(BigDecimal lat1, BigDecimal lon1,
                                   BigDecimal lat2, BigDecimal lon2) {
        if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) {
            return Double.POSITIVE_INFINITY;
        }
        var lat1Rad = Math.toRadians(lat1.doubleValue());
        var lat2Rad = Math.toRadians(lat2.doubleValue());
        var deltaLat = Math.toRadians(lat2.subtract(lat1).doubleValue());
        var deltaLon = Math.toRadians(lon2.subtract(lon1).doubleValue());
        var a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        var c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}
