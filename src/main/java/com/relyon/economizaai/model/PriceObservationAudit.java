package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.util.UUID;

/**
 * Internal-only audit row linking an anonymized PriceObservation back to
 * the contributing receipt + household. Used for k-anonymity counting and
 * right-to-deletion. Never exposed via the public API.
 */
@Entity
@Table(name = "price_observation_audits")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PriceObservationAudit extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "observation_id", nullable = false, unique = true)
    private PriceObservation observation;

    @Column(name = "receipt_id", nullable = false)
    private UUID receiptId;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(name = "contributed_at", nullable = false)
    private LocalDateTime contributedAt;
}
