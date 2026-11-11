package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * Idempotency claim for one end-of-launch-promo warning: a user is notified at most once per
 * milestone (days before the promo ends). The row is written BEFORE the notification goes out.
 */
@Entity
@Table(name = "launch_promo_warnings",
        uniqueConstraints = @UniqueConstraint(name = "uq_launch_promo_warnings_user_milestone",
                columnNames = {"user_id", "milestone_days"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class LaunchPromoWarning extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "milestone_days", nullable = false)
    private int milestoneDays;
}
