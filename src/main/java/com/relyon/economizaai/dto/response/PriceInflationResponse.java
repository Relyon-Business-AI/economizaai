package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Official food inflation (IBGE) next to how the prices paid in the markets moved (the app's
 * notas), over one period, for Brasil or one state. Months are "yyyy-MM"; every series has the
 * base point (index 100, the month before {@code from}) plus one point per month.
 * {@code viewerState} is the state of the person asking (null when unknown), whatever was asked for.
 */
public record PriceInflationResponse(
        String scope,
        String state,
        String stateName,
        String viewerState,
        String viewerStateName,
        int requestedMonths,
        String from,
        String to,
        boolean shortened,
        Official official,
        Community community,
        Basket basket,
        BigDecimal differencePoints,
        List<CategoryChange> categories,
        List<FoodChange> foods,
        List<OfficialGroup> officialGroups
) {
    public static final String STATUS_OK = "OK";
    public static final String STATUS_INSUFFICIENT = "INSUFFICIENT";

    public record SeriesPoint(String month, BigDecimal index) {}

    public record Official(String areaName, boolean nationalFallback, BigDecimal changePercent,
                           List<SeriesPoint> series) {}

    public record Community(String status, BigDecimal changePercent, int pairCount, int storeCount,
                            List<SeriesPoint> series) {}

    public record Basket(String status, BigDecimal changePercent, int productCount, List<SeriesPoint> series) {}

    public record CategoryChange(String slug, String name, BigDecimal changePercent, int pairCount) {}

    public record FoodChange(String name, BigDecimal changePercent, int pairCount) {}

    public record OfficialGroup(String code, String name, BigDecimal changePercent) {}
}
