package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.ProductCategory;
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
 * Golden-set row for the categorization benchmark: a real NFC-e description
 * with hand-verified truth (category always; brand/pack optional). Managed at
 * runtime via the admin bulk-import endpoint (formerly
 * seed/categorization-benchmark.csv), so the set can grow without a deploy.
 */
@Entity
@Table(name = "categorization_benchmark_entries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CategorizationBenchmarkEntry extends BaseEntity {

    @Column(nullable = false, unique = true, length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "expected_category", nullable = false, length = 30)
    private ProductCategory expectedCategory;

    @Column(name = "expected_brand", length = 120)
    private String expectedBrand;

    @Column(name = "expected_pack_size", precision = 12, scale = 3)
    private BigDecimal expectedPackSize;

    @Column(name = "expected_pack_unit", length = 10)
    private String expectedPackUnit;
}
