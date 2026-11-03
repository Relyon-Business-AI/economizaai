package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.AccountDeletionReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One account-deletion survey answer, kept for churn analysis. Written in the same
 * transaction that hard-deletes the user, BEFORE the user row goes — and deliberately
 * ANONYMIZED: no user_id, no email. Only a non-identifying snapshot (tier, how old the
 * account was, how many notas it had) so we can read churn by segment without PII.
 * {@code reason} is nullable because giving a reason is optional (deletion must never
 * be blocked on it); {@code details} holds the free text for {@link AccountDeletionReason#OTHER}.
 */
@Entity
@Table(name = "account_deletion_feedback")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountDeletionFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 32)
    private AccountDeletionReason reason;

    @Column(name = "details", length = 1000)
    private String details;

    // Non-identifying snapshots for segmenting churn.
    @Column(name = "subscription_tier", length = 16)
    private String subscriptionTier;

    @Column(name = "account_age_days")
    private Integer accountAgeDays;

    @Column(name = "receipt_count")
    private Integer receiptCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
