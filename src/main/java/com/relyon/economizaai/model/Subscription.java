package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.SubscriptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * A user's PRO billing record. One per user. The {@code provider} /
 * {@code providerRef} pair links to the external payment provider's
 * subscription so a webhook can find and update this row; both are null when
 * an admin flips the tier manually (promos / ops). {@code status} mirrors the
 * provider lifecycle; the user's {@code subscriptionTier} is kept in sync by
 * {@link com.relyon.economizaai.service.subscription.SubscriptionService}.
 */
@Entity
@Table(name = "subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Subscription extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    /** Payment provider name (e.g. "stripe", "mercadopago", "manual"). Null for admin flips. */
    @Column(length = 40)
    private String provider;

    /** Provider's subscription identifier. Null for admin flips. */
    @Column(name = "provider_ref", length = 255)
    private String providerRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status;

    /** End of the currently-paid period. Null = open-ended (e.g. manual grant). */
    @Column(name = "current_period_end")
    private LocalDateTime currentPeriodEnd;
}
