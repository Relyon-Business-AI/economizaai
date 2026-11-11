package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.PremiumGrantKind;
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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Premium time a user earned outside billing (referral rewards): a {@code [startsAt, endsAt)}
 * window. The user is Premium while a {@link Subscription} is ACTIVE <em>or</em> any grant window
 * is running, so a reward never replaces or shortens a paid subscription — it queues after the
 * user's current entitlement instead.
 */
@Entity
@Table(name = "premium_grants",
        uniqueConstraints = @UniqueConstraint(name = "uq_premium_grants_user_source_kind",
                columnNames = {"user_id", "source_user_id", "kind"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PremiumGrant extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PremiumGrantKind kind;

    /** The invited account that produced this reward (null once that account is deleted). */
    @Column(name = "source_user_id")
    private UUID sourceUserId;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    /** Exclusive end of the window. */
    @Column(name = "ends_at", nullable = false)
    private LocalDateTime endsAt;
}
