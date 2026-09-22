package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A real money event from a payment provider (RevenueCat purchase/renewal). One
 * row per provider event, capturing the actual {@code amount} paid — the atom
 * that makes LTV/ROAS real instead of a modeled proxy. Deduped on
 * ({@code provider}, {@code providerRef}) where providerRef is the provider's
 * event id, so webhook retries don't double-count.
 */
@Entity
@Table(name = "revenue_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class RevenueEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "provider_ref", length = 255)
    private String providerRef;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "product_id", length = 255)
    private String productId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;
}
