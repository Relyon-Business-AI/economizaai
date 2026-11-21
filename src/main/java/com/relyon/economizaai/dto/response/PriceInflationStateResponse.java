package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;

/** One state's line in "Preços e inflação": official change, community change and the gap between them. */
public record PriceInflationStateResponse(
        String state,
        String stateName,
        String officialAreaName,
        boolean nationalFallback,
        BigDecimal officialChangePercent,
        String communityStatus,
        BigDecimal communityChangePercent,
        BigDecimal differencePoints,
        String from,
        String to
) {}
