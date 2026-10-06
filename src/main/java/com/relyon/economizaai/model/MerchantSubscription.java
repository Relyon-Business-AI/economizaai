package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.MerchantSubscriptionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Marketing-account subscription of a CHAIN (cnpj_root) — not of a user, since
 * several MERCHANT users can manage the same chain. Launch promo: every chain
 * that claims before the configured date gets PROMO status free until
 * {@code freeUntil}. Payment integration is still INERT (DEV_NOTES.md).
 */
@Entity
@Table(name = "merchant_subscriptions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class MerchantSubscription extends BaseEntity {

    @Column(name = "cnpj_root", nullable = false, unique = true, length = 8)
    private String cnpjRoot;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String plan = "MARKETING";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MerchantSubscriptionStatus status;

    @Column(name = "free_until")
    private LocalDate freeUntil;

    @Column(name = "activated_at", nullable = false)
    private LocalDateTime activatedAt;

    public boolean isActiveOn(LocalDate day) {
        if (status == MerchantSubscriptionStatus.ACTIVE) return true;
        return status == MerchantSubscriptionStatus.PROMO && freeUntil != null && !day.isAfter(freeUntil);
    }
}
