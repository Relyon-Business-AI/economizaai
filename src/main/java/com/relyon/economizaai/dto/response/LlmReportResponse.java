package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Health/cost/quality snapshot of the LLM teacher layer for the admin:
 * {@code overrideRate} (human corrections on LLM labels) is the headline
 * quality KPI; {@code openDisagreements} is the review queue.
 */
public record LlmReportResponse(
        long llmLabeledProducts,
        long pendingEnrichmentCandidates,
        long dictionaryRulesByLlm,
        long userOverridesOnLlmLabels,
        double overrideRate,
        long enrichCallsToday,
        long visionCallsToday,
        List<OpenDisagreement> openDisagreements) {

    public record OpenDisagreement(
            UUID id,
            UUID productId,
            String productName,
            String field,
            String currentValue,
            String currentSource,
            String suggestedValue,
            BigDecimal confidence,
            LocalDateTime createdAt) {}
}
