package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.NotificationCampaign;
import com.relyon.economizaai.model.enums.CampaignStatus;
import com.relyon.economizaai.model.enums.NotificationType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Admin view of a campaign. {@code metrics} is the comparable performance
 * block (same shape on list rows and detail) so texts/times/types can be
 * ranked against each other at a glance.
 */
public record CampaignResponse(
        UUID id,
        String name,
        String title,
        String body,
        NotificationType type,
        UUID audienceId,
        String audienceName,
        CampaignStatus status,
        OffsetDateTime scheduledAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        int recipientsTotal,
        int recipientsSent,
        int recipientsFailed,
        String createdByEmail,
        LocalDateTime createdAt,
        Metrics metrics
) {

    /** Funnel + engagement + conversion rollup; rates are fractions of sent (4 decimals). */
    public record Metrics(
            long sent,
            long delivered,
            long read,
            long pushOpened,
            long tapped,
            long dismissed,
            long convertedUsers,
            long conversions,
            BigDecimal attributedSavings,
            BigDecimal deliveryRate,
            BigDecimal readRate,
            BigDecimal openRate,
            BigDecimal conversionRate
    ) {}

    public static CampaignResponse from(NotificationCampaign campaign, Metrics metrics) {
        return new CampaignResponse(
                campaign.getId(),
                campaign.getName(),
                campaign.getTitle(),
                campaign.getBody(),
                campaign.getType(),
                campaign.getAudience().getId(),
                campaign.getAudience().getName(),
                campaign.getStatus(),
                campaign.getScheduledAt(),
                campaign.getStartedAt(),
                campaign.getCompletedAt(),
                campaign.getRecipientsTotal(),
                campaign.getRecipientsSent(),
                campaign.getRecipientsFailed(),
                campaign.getCreatedByEmail(),
                campaign.getCreatedAt(),
                metrics);
    }
}
