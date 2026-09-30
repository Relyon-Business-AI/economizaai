package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Daily reading of the live Infosimples account ({@code GET /api/admin/account}).
 * The provider exposes no history, so these snapshots are our raw material: the
 * month-close job derives the final consumo/varrido of a month from its last
 * snapshot, and the daily job detects recargas by comparing consecutive ones.
 */
@Entity
@Table(name = "infosimples_account_snapshot")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class InfosimplesAccountSnapshot extends BaseEntity {

    @Column(name = "taken_at", nullable = false)
    private OffsetDateTime takenAt;

    @Column(precision = 12, scale = 2)
    private BigDecimal balance;

    @Column(name = "current_usage", precision = 12, scale = 2)
    private BigDecimal currentUsage;

    @Column(name = "min_bill", precision = 12, scale = 2)
    private BigDecimal minBill;
}
