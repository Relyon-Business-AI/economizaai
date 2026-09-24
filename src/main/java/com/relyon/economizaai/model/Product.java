package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.CategorizationSource;
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
import java.time.LocalDateTime;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Product extends BaseEntity {

    @Column(length = 14, unique = true)
    private String ean;

    @Column(name = "normalized_name", nullable = false, length = 255)
    private String normalizedName;

    @Column(name = "generic_name", length = 100)
    private String genericName;

    // Normalized (accent-stripped, lowercased, SEFAZ-expanded) mirror of genericName,
    // used for dedup/matching so "Fermento Biológico" == "fermento biologico".
    // Display always uses genericName; logic uses this. Kept in sync on every write.
    @Column(name = "generic_name_norm", length = 160)
    private String genericNameNorm;

    @Column(length = 100)
    private String brand;

    /** Normalized mirror of brand — see {@link #genericNameNorm}. */
    @Column(name = "brand_norm", length = 160)
    private String brandNorm;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ProductCategory category;

    @Column(length = 10)
    private String unit;

    @Column(name = "pack_size", precision = 10, scale = 3)
    private BigDecimal packSize;

    @Column(name = "pack_unit", length = 10)
    private String packUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "categorization_source", nullable = false, length = 30)
    @lombok.Builder.Default
    private CategorizationSource categorizationSource = CategorizationSource.NONE;

    /** LLM teacher-layer bookkeeping — attempts capped so a failing product isn't retried forever. */
    @Column(name = "llm_enrichment_attempts", nullable = false)
    @lombok.Builder.Default
    private int llmEnrichmentAttempts = 0;

    @Column(name = "llm_enriched_at")
    private LocalDateTime llmEnrichedAt;

    @Column(name = "llm_audited_at")
    private LocalDateTime llmAuditedAt;
}
