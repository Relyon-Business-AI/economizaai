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

import java.util.UUID;

/**
 * Append-only audit of consensus graduations: which households' corrections
 * promoted which product to which category. Makes a bad consensus (colluding or
 * coinciding households) traceable and reversible — before this, graduations
 * were logged once and lost.
 */
@Entity
@Table(name = "consensus_graduation_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ConsensusGraduationAudit extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProductCategory category;

    /** Comma-separated household UUIDs that voted for the winning category. */
    @Column(name = "household_ids", nullable = false, columnDefinition = "text")
    private String householdIds;

    @Column(nullable = false)
    private int votes;
}
