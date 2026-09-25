package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/** One sweep execution (async) — status + finding count for the admin to follow. */
@Entity
@Table(name = "ai_sweep_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class AiSweepRun extends BaseEntity {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int findings;

    @Column(length = 500)
    private String error;
}
