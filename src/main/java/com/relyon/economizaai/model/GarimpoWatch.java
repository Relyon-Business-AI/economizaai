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
 * A standing deal-hunt: re-run {@code searchTerm} on {@code provider} on a schedule
 * and alert (webhook) when a product hits the target price and/or the minimum
 * discount. At least one of the two criteria must be set (service-enforced); when
 * both are set, meeting EITHER one is a hit.
 */
@Entity
@Table(name = "garimpo_watches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class GarimpoWatch extends BaseEntity {

    @Column(name = "search_term", nullable = false, length = 255)
    private String searchTerm;

    /** Provider key (matches an EcommerceProvider.key(), e.g. "mercadolivre"). */
    @Column(nullable = false, length = 40)
    @lombok.Builder.Default
    private String provider = "mercadolivre";

    /** Alert when a product's price is at or below this value (R$). */
    @Column(name = "target_price", precision = 12, scale = 2)
    private BigDecimal targetPrice;

    /** Alert when a product's marketplace-reported discount is at least this percent. */
    @Column(name = "min_discount_percent")
    private Integer minDiscountPercent;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** Email of the admin who created it (audit). */
    @Column(name = "created_by", length = 255)
    private String createdBy;
}
