package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.AiActivity;
import com.relyon.economizaai.model.enums.AiFindingStatus;
import com.relyon.economizaai.model.enums.AiFindingType;
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
import java.util.UUID;

/**
 * One AI-proposed finding awaiting human review (the sweep's output). The
 * {@code payload} carries the machine-applicable proposal as JSON — on approval
 * the applier routes it to the EXISTING admin services (curated import, brand
 * import, product patch, merge), so the AI feeds the same levers a human uses.
 */
@Entity
@Table(name = "ai_findings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class AiFinding extends BaseEntity {

    @Column(name = "sweep_run_id", nullable = false)
    private UUID sweepRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AiFindingType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @lombok.Builder.Default
    private AiFindingStatus status = AiFindingStatus.PENDING;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    /** Which activity produced it — lets the spend panel tie findings to cost. */
    @Enumerated(EnumType.STRING)
    @Column(name = "activity", length = 40)
    private AiActivity activity;
}
