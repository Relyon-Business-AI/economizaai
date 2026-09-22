package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.StateIngestionOutcome;
import com.relyon.economizaai.model.enums.StateIngestionStrategy;
import com.relyon.economizaai.model.enums.UnidadeFederativa;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One attempt of one fallback-chain layer on a state without a verified
 * adapter. This is how we learn state coverage on demand from real users:
 * which UFs are actually being scanned, which layer serves them, and what the
 * portal returned when nothing worked (the evidence for building a dedicated
 * adapter). Surfaced at {@code GET /admin/state-coverage}.
 */
@Entity
@Table(name = "state_ingestion_attempts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StateIngestionAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "uf", nullable = false, length = 2)
    private UnidadeFederativa uf;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, length = 20)
    private StateIngestionStrategy strategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private StateIngestionOutcome outcome;

    /** Host of the QR's portal URL — tells us which portal the state actually uses. */
    @Column(name = "qr_host", length = 160)
    private String qrHost;

    /** Error summary and/or a sanitized HTML snippet of what the portal returned. */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "admin_notified", nullable = false)
    private boolean adminNotified;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
