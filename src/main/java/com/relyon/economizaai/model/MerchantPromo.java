package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.MerchantPromoSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A promo ANNOUNCED by the merchant (docs/MERCHANT_ACCOUNTS.md, Fase 2).
 * Deliberately a separate world from PriceObservation: an announced price is a
 * claim, never an observation — it must not feed the collaborative index nor
 * alter any "cheapest" ranking. Exposure in the community feed is flagged
 * {@code sponsored} and gated by economizaai.merchant.promos-feed-enabled.
 */
@Entity
@Table(name = "merchant_promos")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class MerchantPromo extends BaseEntity {

    @Column(name = "cnpj_root", nullable = false, length = 8)
    private String cnpjRoot;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    /** Canonical product matched by EAN at write time; null when the EAN is unknown to us. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false, length = 14)
    private String ean;

    @Column(length = 255)
    private String description;

    @Column(name = "promo_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal promoPrice;

    @Column(name = "regular_price", precision = 12, scale = 2)
    private BigDecimal regularPrice;

    @Column(name = "starts_at", nullable = false)
    private LocalDate startsAt;

    @Column(name = "ends_at", nullable = false)
    private LocalDate endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MerchantPromoSource source;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    public boolean isLiveOn(LocalDate day) {
        return active && !day.isBefore(startsAt) && !day.isAfter(endsAt);
    }
}
