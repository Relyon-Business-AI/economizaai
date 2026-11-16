package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What the household saved WITH THE APP'S HELP in a period: purchases that came out cheaper
 * than the household's previous one after the app showed that price. {@code from} is null when
 * the period has no start (everything since the household joined).
 */
public record AppSavingsResponse(
        LocalDateTime from,
        LocalDateTime to,
        BigDecimal saved,
        int purchaseCount,
        BigDecimal largestSaving
) {}
