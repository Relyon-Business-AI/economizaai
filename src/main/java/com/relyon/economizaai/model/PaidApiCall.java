package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.PaidApiService;
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
 * One paid external call (Infosimples query, captcha solve). The ledger behind
 * the cost audit: it's both the reconciliation trail (match against the provider
 * invoice) and the counter the per-user daily cap reads. {@code userId} is
 * nullable — internal/admin re-consults and reparses have no user.
 */
@Entity
@Table(name = "paid_api_call")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaidApiCall {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "service", nullable = false, length = 32)
    private PaidApiService service;

    @Column(name = "uf", length = 2)
    private String uf;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "estimated_cost_cents", nullable = false)
    private int estimatedCostCents;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }
}
