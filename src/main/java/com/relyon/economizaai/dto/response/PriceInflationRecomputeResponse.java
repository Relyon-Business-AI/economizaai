package com.relyon.economizaai.dto.response;

/** What an on-demand load + recompute of "Preços e inflação" did. */
public record PriceInflationRecomputeResponse(
        int months,
        int officialReadingsFetched,
        int officialRowsStored,
        String newestOfficialMonth,
        int communityMonthsRecomputed,
        int communityProductRows,
        int communityPairs
) {}
