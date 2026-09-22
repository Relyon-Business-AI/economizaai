package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single user-configurable notification trigger. Unifies every alert the
 * user can create or toggle — the "avise-me quando" price drop, a price
 * ceiling, replenishment reminders, a monthly budget cap, and the toggleable
 * system defaults (personal/community promos, cheaper-market, digest).
 *
 * <p>Replaces the former {@code PriceAlert} entity (a PRICE_DROP rule is the
 * old price alert). {@code /api/v1/alerts} remains as a backward-compatible
 * alias over PRICE_DROP rules.
 *
 * <p>Uniqueness is per {@code (user, type, product)}. Default rules carry a
 * null product, so a user has at most one rule per default type.
 */
@Entity
@Table(name = "notification_rules",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "type", "product_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class NotificationRule extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    /** Product the rule watches. Null for product-agnostic rules (BUDGET, defaults). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    /** Price/spend threshold (R$). Used by PRICE_DROP, BUDGET. */
    @Column(name = "threshold_price", precision = 12, scale = 2)
    private BigDecimal thresholdPrice;

    /** Optional geo scope from the user's home (km). Null = anywhere in the network. */
    @Column(name = "radius_km")
    private Double radiusKm;

    /** Replenishment lead time: notify this many days before predicted runout. */
    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    /**
     * Per-rule channel override. Null falls back to the user's
     * {@link NotificationPreference} for the type, then the system default.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private NotificationChannel channel;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;

    /** System-seeded default vs. user-created rule. */
    @Column(name = "is_default", nullable = false)
    @lombok.Builder.Default
    private boolean isDefault = false;

    /** Last time this rule fired — drives the anti-spam cooldown. */
    @Column(name = "last_fired_at")
    private LocalDateTime lastFiredAt;
}
