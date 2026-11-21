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

import java.time.LocalDate;

/** How much data backs one month of the community price change in one state. */
@Entity
@Table(name = "community_price_coverage", uniqueConstraints = @UniqueConstraint(
        name = "uq_community_price_coverage", columnNames = {"reference_month", "state"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommunityPriceCoverage extends BaseEntity {

    @Column(name = "reference_month", nullable = false)
    private LocalDate referenceMonth;

    @Column(nullable = false, length = 2)
    private String state;

    @Column(name = "pair_count", nullable = false)
    private int pairCount;

    @Column(name = "store_count", nullable = false)
    private int storeCount;
}
