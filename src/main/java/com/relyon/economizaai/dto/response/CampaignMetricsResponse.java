package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.NotificationEventType;

import java.util.List;
import java.util.UUID;

/**
 * Full metrics drill-down for one campaign: the comparable block from
 * {@link CampaignResponse.Metrics} plus every telemetry event type tied to the
 * campaign's notifications (occurrences + distinct users).
 */
public record CampaignMetricsResponse(
        UUID campaignId,
        String name,
        CampaignResponse.Metrics metrics,
        List<EventLine> eventsByType,
        int conversionWindowDays
) {

    public record EventLine(
            NotificationEventType eventType,
            long occurrences,
            long users
    ) {}
}
