package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The LLM disagreed with a higher-ranked source (or the auditor disputed a
 * stored label). Recorded for admin review — never auto-applied, so the
 * categorization system can't argue with itself.
 */
@Entity
@Table(name = "llm_disagreements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class LlmDisagreement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    /** Which field is disputed: category | brand | pack. */
    @Column(nullable = false, length = 20)
    private String field;

    @Column(name = "current_value", length = 120)
    private String currentValue;

    @Column(name = "current_source", length = 30)
    private String currentSource;

    @Column(name = "suggested_value", length = 120)
    private String suggestedValue;

    @Column(precision = 3, scale = 2)
    private BigDecimal confidence;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}
