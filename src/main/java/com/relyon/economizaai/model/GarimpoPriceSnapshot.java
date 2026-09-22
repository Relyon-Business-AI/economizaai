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

/**
 * One observed price of a marketplace product (the garimpo price history — the
 * "pulo do gato" that tells a real discount from a fake one). Append-only: a new row
 * is written only when the observed price CHANGED vs the latest snapshot, so the
 * table stays a compact change-log instead of one row per search. {@code createdAt}
 * (BaseEntity) is the capture time.
 */
@Entity
@Table(name = "garimpo_price_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class GarimpoPriceSnapshot extends BaseEntity {

    /** Provider key (matches an EcommerceProvider.key(), e.g. "mercadolivre"). */
    @Column(nullable = false, length = 40)
    private String provider;

    /** Marketplace product id (e.g. "MLB123456789"). */
    @Column(name = "external_id", nullable = false, length = 60)
    private String externalId;

    @Column(nullable = false, length = 512)
    private String title;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal price;

    @Column(name = "original_price", precision = 12, scale = 2)
    private BigDecimal originalPrice;

    @Column(name = "discount_percent")
    private Integer discountPercent;

    @Column(nullable = false, length = 3)
    @lombok.Builder.Default
    private String currency = "BRL";

    @Column(name = "external_url", length = 1024)
    private String externalUrl;

    @Column(name = "affiliate_url", length = 1024)
    private String affiliateUrl;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    @Column(name = "seller_name", length = 255)
    private String sellerName;

    @Column(name = "free_shipping", nullable = false)
    @lombok.Builder.Default
    private boolean freeShipping = false;
}
