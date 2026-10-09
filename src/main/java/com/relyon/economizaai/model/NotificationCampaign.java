package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.CampaignStatus;
import com.relyon.economizaai.model.enums.NotificationType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * An admin-authored notification send over a {@link NotificationAudience}.
 * The title/body are literal text written by the admin (not i18n keys); the
 * {@code type} drives the FE deep-link destination exactly like organic
 * notifications. Every outbox row produced by a campaign carries its id
 * ({@code notifications.campaign_id}), which is what makes per-campaign
 * metrics plain aggregations over existing tables.
 */
@Entity
@Table(name = "notification_campaigns")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class NotificationCampaign extends BaseEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    @lombok.Builder.Default
    private NotificationType type = NotificationType.SYSTEM;

    /** Free-form JSON extras forwarded to the push payload (deeplink etc). */
    @Column(columnDefinition = "TEXT")
    private String extras;

    /** Filter-based target. Null when the campaign targets an explicit user list instead. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "audience_id")
    private NotificationAudience audience;

    /** Hand-picked recipients (exactly one source per campaign: audience OR this list). */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "notification_campaign_recipients",
            joinColumns = @JoinColumn(name = "campaign_id"))
    @Column(name = "user_id")
    @lombok.Builder.Default
    private Set<UUID> recipientUserIds = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "recipients_total", nullable = false)
    @lombok.Builder.Default
    private int recipientsTotal = 0;

    @Column(name = "recipients_sent", nullable = false)
    @lombok.Builder.Default
    private int recipientsSent = 0;

    @Column(name = "recipients_failed", nullable = false)
    @lombok.Builder.Default
    private int recipientsFailed = 0;

    @Column(name = "created_by_email", length = 255)
    private String createdByEmail;
}
