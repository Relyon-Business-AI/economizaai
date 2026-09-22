package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.NotificationEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One user-feedback / lifecycle signal in the notification telemetry log.
 * Append-only and immutable (no updated_at) — this is training data for a
 * future ranking/learning engine; nothing consumes it yet.
 *
 * <p>{@code notificationId} and {@code productId} are stored as raw UUIDs
 * (loose references with ON DELETE SET NULL) so a purged push or product never
 * deletes the behavioral signal.
 */
@Entity
@Table(name = "notification_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "notification_id")
    private UUID notificationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private NotificationEventType eventType;

    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "market_cnpj", length = 14)
    private String marketCnpj;

    @Column(length = 20)
    private String channel;

    /** Small JSON payload serialized as a string (e.g. {"discountFraction":0.22}). */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    /**
     * Realized R$ savings carried on a {@code CONVERTED} event so the north-star
     * metric sums in SQL without parsing {@link #metadata}. Null for other types.
     */
    @Column(name = "savings_amount", precision = 12, scale = 2)
    private BigDecimal savingsAmount;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = OffsetDateTime.now();
        }
    }
}
