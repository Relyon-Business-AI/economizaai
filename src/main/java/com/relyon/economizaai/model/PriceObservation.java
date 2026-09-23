package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.ReceiptChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.time.LocalDateTime;

/**
 * The atomic fact of the collaborative price index.
 *
 * <strong>LGPD-critical:</strong> this entity intentionally does NOT carry
 * user_id or household_id. Any link back to a contributor lives in the
 * separate, internal-only PriceObservationAudit table. Public aggregate
 * queries are k-anonymity-guarded at the query layer.
 */
@Entity
@Table(name = "price_observations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class PriceObservation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * Physical (IN_STORE) vs online (ONLINE), copied from the source receipt's channel.
     * Splits the index into two independent series — reads never mix them: the physical
     * near-you index filters IN_STORE; the national online index filters ONLINE.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @lombok.Builder.Default
    private ReceiptChannel channel = ReceiptChannel.IN_STORE;

    @Column(name = "market_cnpj", nullable = false, length = 14)
    private String marketCnpj;

    /** First 8 digits of the CNPJ — identifies the chain (e.g., all Zaffari units share a root). */
    @Column(name = "market_cnpj_root", nullable = false, length = 8)
    private String marketCnpjRoot;

    @Column(name = "market_name", length = 255)
    private String marketName;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "pack_size", precision = 10, scale = 3)
    private BigDecimal packSize;

    @Column(name = "pack_unit", length = 10)
    private String packUnit;

    @Column(name = "observed_at", nullable = false)
    private LocalDateTime observedAt;

    @Column(name = "is_outlier", nullable = false)
    @lombok.Builder.Default
    private boolean outlier = false;

    /**
     * Mirrors {@link com.relyon.economizaai.model.ReceiptItem#isNfcePromoFlag}
     * at the moment of write. Lets the panel exclude promo prices from
     * baseline median calcs without joining back through the audit table
     * and the source receipt item.
     */
    @Column(name = "promo_flag", nullable = false)
    @lombok.Builder.Default
    private boolean promoFlag = false;

    /**
     * Price per base unit (e.g. R$/L, R$/kg, R$/UN) — see
     * {@link com.relyon.economizaai.service.priceindex.UnitConverter}.
     * Null when the item's unit + product pack info don't map to a known
     * base; consumers fall back to {@link #unitPrice}.
     */
    @Column(name = "normalized_unit_price", precision = 12, scale = 4)
    private BigDecimal normalizedUnitPrice;

    @Column(name = "normalized_unit", length = 8)
    private String normalizedUnit;

    @Column(length = 120)
    private String city;

    @Column(length = 2)
    private String state;

    /** Official IBGE municipality code (7 digits) — stable key for regional aggregation. */
    @Column(name = "ibge_city_code", length = 7)
    private String ibgeCityCode;
}
