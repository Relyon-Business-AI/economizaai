package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.CostAlertStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One cost alert owed to the admin (daily budget of a service, or a provider's prepaid
 * balance, at the 80% or 100% threshold). It is both the dedup record — at most one row
 * per (alert, threshold, period) — and the delivery outbox: it stays {@code PENDING}
 * until a provider accepted the message.
 */
@Entity
@Table(name = "cost_alert", uniqueConstraints = @UniqueConstraint(
        name = "uq_cost_alert_key_threshold_period", columnNames = {"alert_key", "threshold", "period_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CostAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** What the alert is about, e.g. {@code BUDGET:INFOSIMPLES} or {@code BALANCE:CAPSOLVER}. */
    @Column(name = "alert_key", nullable = false, length = 64)
    private String alertKey;

    /** 80 or 100. */
    @Column(name = "threshold", nullable = false)
    private int threshold;

    /** The dedup window: the day for a daily budget, the top-up reference for a balance. */
    @Column(name = "period_key", nullable = false, length = 32)
    private String periodKey;

    /** Single-line message (the WhatsApp text and the e-mail subject). */
    @Column(name = "headline", nullable = false, length = 500)
    private String headline;

    @Column(name = "detail", nullable = false, columnDefinition = "TEXT")
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private CostAlertStatus status;

    @Column(name = "whatsapp_attempts", nullable = false)
    private int whatsappAttempts;

    @Column(name = "whatsapp_sent_at")
    private OffsetDateTime whatsappSentAt;

    @Column(name = "email_sent_at")
    private OffsetDateTime emailSentAt;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        var now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (nextAttemptAt == null) nextAttemptAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
