package com.relyon.economizaai.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Store funnel data from App Store Connect (iOS) — impressions, page views,
 * and installs broken down by traffic source. Distinct from our own signup
 * attribution: these numbers count store visitors who may never have registered.
 *
 * <p>{@code configured=false} means the ASC integration is not enabled; the
 * FE should show a setup prompt. Otherwise the data reflects what Apple has
 * published for the requested window (24-72h delay is normal).
 */
public record StoreAnalyticsResponse(
        boolean configured,
        String dataDelayNote,
        OffsetDateTime syncedAt,
        LocalDate dataFrom,
        LocalDate dataTo,
        Totals totals,
        List<SourceRow> bySource,
        List<DailyRow> dailyRows
) {

    public record Totals(long impressions, long pageViews, long installs) {}

    public record SourceRow(
            String sourceType,
            String displayName,
            long impressions,
            long pageViews,
            long installs
    ) {}

    public record DailyRow(LocalDate date, long impressions, long pageViews, long installs) {}

    public static StoreAnalyticsResponse unconfigured() {
        return new StoreAnalyticsResponse(
                false,
                "dados das lojas têm atraso de 24-72h",
                null, null, null,
                new Totals(0, 0, 0),
                List.of(), List.of()
        );
    }
}
