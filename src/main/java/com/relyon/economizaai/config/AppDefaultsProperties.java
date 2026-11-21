package com.relyon.economizaai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Starting values the apps use until the person chooses their own: the route rules a shopping
 * plan opens with and the smallest saving worth highlighting on Home. The backend applies none
 * of them by itself — the apps read them from {@code GET /app-config}.
 */
@Component
@ConfigurationProperties(prefix = "economizaai.app-defaults")
public class AppDefaultsProperties {

    private BigDecimal routeMaxDistanceKm = new BigDecimal("10");
    private int routeMaxStops = 2;
    private BigDecimal routeMinSavingPerStop = new BigDecimal("10");
    private BigDecimal homeSavingsMin = new BigDecimal("10");

    public BigDecimal getRouteMaxDistanceKm() { return routeMaxDistanceKm; }
    public void setRouteMaxDistanceKm(BigDecimal routeMaxDistanceKm) { this.routeMaxDistanceKm = routeMaxDistanceKm; }
    public int getRouteMaxStops() { return routeMaxStops; }
    public void setRouteMaxStops(int routeMaxStops) { this.routeMaxStops = routeMaxStops; }
    public BigDecimal getRouteMinSavingPerStop() { return routeMinSavingPerStop; }
    public void setRouteMinSavingPerStop(BigDecimal routeMinSavingPerStop) { this.routeMinSavingPerStop = routeMinSavingPerStop; }
    public BigDecimal getHomeSavingsMin() { return homeSavingsMin; }
    public void setHomeSavingsMin(BigDecimal homeSavingsMin) { this.homeSavingsMin = homeSavingsMin; }
}
