package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * An online offer for a product, keyed by EAN. MVP rows are admin-CURATED (precision
 * first); the same table can later cache provider-fetched offers ({@code curated=false}).
 * A scanned receipt item resolves to the cheapest active offer with a matching EAN.
 */
@Entity
@Table(name = "ecommerce_offers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class EcommerceOffer extends BaseEntity {

    @Column(length = 14, nullable = false)
    private String ean;

    /** Optional link to our catalog product (for reporting); the EAN is the match key. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    /** Provider key (matches an EcommerceProvider.key(), e.g. "mercadolivre"). */
    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "external_url", length = 1024)
    private String externalUrl;

    @Column(name = "affiliate_url", length = 1024)
    private String affiliateUrl;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal price;

    @Column(precision = 12, scale = 2)
    private BigDecimal freight;

    @Column(nullable = false, length = 3)
    @lombok.Builder.Default
    private String currency = "BRL";

    @Column(name = "in_stock", nullable = false)
    @lombok.Builder.Default
    private boolean inStock = true;

    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean active = true;

    /** True when a human curated this row (vs auto-fetched from a provider). */
    @Column(nullable = false)
    @lombok.Builder.Default
    private boolean curated = true;

    /** Email of the admin who curated it (audit); null for auto-fetched. */
    @Column(name = "curated_by", length = 255)
    private String curatedBy;

    public BigDecimal total() {
        return freight == null ? price : price.add(freight);
    }
}
