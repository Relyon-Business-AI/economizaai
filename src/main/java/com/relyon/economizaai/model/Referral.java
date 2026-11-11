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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One invited account and who invited it. An account has at most one referrer, ever
 * ({@code invited} is unique). {@code qualifiedAt} is set when the invited person confirms a
 * receipt that contributed to the collaborative index; {@code subscribedAt} on their first
 * paid Premium activation.
 */
@Entity
@Table(name = "referrals")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Referral extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inviter_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User inviter;

    /** Null once the invited account is deleted (the row stays so the inviter's counters hold). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_id", unique = true)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User invited;

    @Column(name = "qualified_at")
    private LocalDateTime qualifiedAt;

    @Column(name = "qualifying_receipt_id")
    private UUID qualifyingReceiptId;

    @Column(name = "subscribed_at")
    private LocalDateTime subscribedAt;
}
