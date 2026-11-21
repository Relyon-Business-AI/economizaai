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
import java.util.UUID;

/**
 * How the community's prices of one product moved in one state from the previous month to this
 * one: the spend-weighted sum of ln(price this month / price last month) over the matched
 * (product, store, unit basis) pairs, the sum of the weights and how many pairs there were.
 * Carries no user or household — it is derived from the anonymous price observations.
 */
@Entity
@Table(name = "community_price_changes", uniqueConstraints = @UniqueConstraint(
        name = "uq_community_price_change", columnNames = {"reference_month", "state", "product_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommunityPriceChange extends BaseEntity {

    /** First day of the month the change arrives at. */
    @Column(name = "reference_month", nullable = false)
    private LocalDate referenceMonth;

    @Column(nullable = false, length = 2)
    private String state;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "weighted_log_sum", nullable = false)
    private double weightedLogSum;

    @Column(name = "weight_sum", nullable = false)
    private double weightSum;

    @Column(name = "pair_count", nullable = false)
    private int pairCount;
}
