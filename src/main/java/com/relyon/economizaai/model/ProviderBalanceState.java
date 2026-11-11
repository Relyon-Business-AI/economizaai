package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Last prepaid-balance reading of one paid provider plus the reference the "80% consumed"
 * alert is measured against: the balance right after the last detected top-up.
 */
@Entity
@Table(name = "provider_balance_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProviderBalanceState {

    @Id
    @Column(name = "provider", nullable = false, length = 32)
    private String provider;

    @Column(name = "reference_balance", nullable = false, precision = 14, scale = 4)
    private BigDecimal referenceBalance;

    @Column(name = "reference_at", nullable = false)
    private OffsetDateTime referenceAt;

    @Column(name = "last_balance", nullable = false, precision = 14, scale = 4)
    private BigDecimal lastBalance;

    @Column(name = "last_checked_at", nullable = false)
    private OffsetDateTime lastCheckedAt;
}
