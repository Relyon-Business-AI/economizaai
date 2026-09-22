package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Dedup-by-state-change bookkeeping for the (future) deals digest: tracks the
 * last discount/price we surfaced for a given (user, product, market) so a
 * digest only re-surfaces a deal when it meaningfully changes. Created for
 * Phase A; nothing reads or writes it yet.
 */
@Entity
@Table(name = "deal_surface_state",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "product_id", "market_cnpj"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DealSurfaceState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "market_cnpj", nullable = false, length = 14)
    private String marketCnpj;

    @Column(name = "last_discount_fraction", precision = 6, scale = 4)
    private BigDecimal lastDiscountFraction;

    @Column(name = "last_unit_price", precision = 12, scale = 2)
    private BigDecimal lastUnitPrice;

    @Column(name = "last_surfaced_at", nullable = false)
    private OffsetDateTime lastSurfacedAt;

    /**
     * When this surfacing was attributed to a realized purchase (Phase D). Null
     * until a confirmed receipt converts it; a fresh re-surface clears it back to
     * null so the same (user, product, market) deal can be attributed again later.
     */
    @Column(name = "converted_at")
    private OffsetDateTime convertedAt;

    @PrePersist
    void onCreate() {
        if (lastSurfacedAt == null) {
            lastSurfacedAt = OffsetDateTime.now();
        }
    }
}
