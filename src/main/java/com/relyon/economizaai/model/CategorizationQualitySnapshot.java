package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.CategorizationQualityTrigger;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * One point in the categorization-quality time series. Immutable (insert-only);
 * {@code createdAt} from {@link BaseEntity} is the recorded time. Written on
 * every benchmark run and every re-categorization backfill so the trend is
 * queryable.
 */
@Entity
@Table(name = "categorization_quality_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CategorizationQualitySnapshot extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CategorizationQualityTrigger trigger;

    @Column(name = "accuracy_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal accuracyPct;

    @Column(name = "benchmark_total", nullable = false)
    private int benchmarkTotal;

    @Column(name = "benchmark_correct", nullable = false)
    private int benchmarkCorrect;

    @Column(name = "catalog_products", nullable = false)
    private int catalogProducts;

    @Column(name = "catalog_categorized", nullable = false)
    private int catalogCategorized;

    @Column(name = "catalog_coverage_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal catalogCoveragePct;

    @Column(name = "brand_accuracy_pct", nullable = false, precision = 5, scale = 2)
    @lombok.Builder.Default
    private BigDecimal brandAccuracyPct = BigDecimal.ZERO;

    @Column(name = "quantity_accuracy_pct", nullable = false, precision = 5, scale = 2)
    @lombok.Builder.Default
    private BigDecimal quantityAccuracyPct = BigDecimal.ZERO;

    // Kept for DB column compatibility — no longer populated.
    @Column(name = "ml_accuracy_pct", nullable = false, precision = 5, scale = 2)
    @lombok.Builder.Default
    private BigDecimal mlAccuracyPct = BigDecimal.ZERO;

    @Column(name = "ml_ready", nullable = false)
    private boolean mlReady;
}
