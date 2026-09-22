package com.relyon.economizaai.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Items the household is predicted to need soon — union of "ran out" and
 * "running low" predictions, in priority order. Empty when the household
 * lacks enough purchase history.
 */
public record SuggestedShoppingListResponse(
        List<ConsumptionPredictionResponse> items,
        LocalDateTime generatedAt
) {}
