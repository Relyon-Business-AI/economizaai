package com.relyon.economizaai.dto.response;

import java.util.List;

/**
 * One page of the household's predictions, ordered by urgency (closest to running out first),
 * plus per-status counts so the FE filter chips show totals without loading every row.
 */
public record ConsumptionPredictionPageResponse(
        List<ConsumptionPredictionResponse> items,
        StatusCounts counts,
        int page,
        int size,
        boolean hasMore
) {
    public record StatusCounts(long ranOut, long runningLow, long ok, long total) {}
}
