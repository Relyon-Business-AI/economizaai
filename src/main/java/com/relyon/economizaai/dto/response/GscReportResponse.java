package com.relyon.economizaai.dto.response;

import java.time.LocalDate;
import java.util.List;

/**
 * Google Search Console report: organic impressions, clicks, CTR and position
 * for the configured site. {@code configured=false} until env vars are set —
 * the FE shows a "configurar" hint instead of zeros.
 */
public record GscReportResponse(
        boolean configured,
        LocalDate from,
        LocalDate to,
        long totalClicks,
        long totalImpressions,
        double avgCtr,
        double avgPosition,
        List<DailyRow> timeline,
        List<QueryRow> topQueries,
        String note
) {
    /** One day of organic traffic: clicks, impressions, CTR and average position. */
    public record DailyRow(LocalDate date, long clicks, long impressions, double ctr, double position) {}

    /** Top search query driving organic traffic in the window. */
    public record QueryRow(String query, long clicks, long impressions, double ctr, double position) {}
}
