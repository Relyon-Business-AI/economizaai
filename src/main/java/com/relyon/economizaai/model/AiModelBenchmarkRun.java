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
import java.time.LocalDateTime;

/**
 * Uma rodada do benchmark de ACURÁCIA de um modelo contra o golden set: classifica
 * N descrições com o modelo e compara a categoria à verdade. Mede qualidade PURA
 * (independente da revisão humana dos achados). Custo/latência dessas chamadas
 * caem no ai_usage_log (activity=MODEL_BENCHMARK) e aparecem no painel de comparação.
 */
@Entity
@Table(name = "ai_model_benchmark_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class AiModelBenchmarkRun extends BaseEntity {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @Column(nullable = false, length = 80)
    private String model;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "sample_size", nullable = false)
    private int sampleSize;

    @Column(nullable = false)
    @lombok.Builder.Default
    private int correct = 0;

    @Column(name = "uncategorized", nullable = false)
    @lombok.Builder.Default
    private int uncategorized = 0;

    @Column(name = "accuracy_pct", precision = 5, scale = 1)
    private BigDecimal accuracyPct;

    @Column(length = 500)
    private String error;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
