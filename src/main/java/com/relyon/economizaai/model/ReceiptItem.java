package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.ProductCategory;
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

@Entity
@Table(name = "receipt_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class ReceiptItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false)
    private Receipt receipt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @Column(name = "raw_description", nullable = false, columnDefinition = "TEXT")
    private String rawDescription;

    @Column(name = "friendly_description", length = 500)
    private String friendlyDescription;

    @Column(length = 14)
    private String ean;

    @Column(precision = 12, scale = 3, nullable = false)
    private BigDecimal quantity;

    @Column(length = 10)
    private String unit;

    @Column(name = "unit_price", precision = 12, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "total_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalPrice;

    @Column(nullable = false)
    @Builder.Default
    private boolean excluded = false;

    /**
     * "Not mine" — a shared-purchase line the household attributes to someone
     * else. Excluded from the household's PERSONAL spend / consumption / savings
     * / reports, but (unlike {@link #excluded}) STILL contributes to the shared
     * price index and canonicalization: the price was really paid at that store,
     * whoever the item belonged to. Editable at any status (personal layer).
     */
    @Column(name = "excluded_from_personal", nullable = false)
    @Builder.Default
    private boolean excludedFromPersonal = false;

    /**
     * True when the SEFAZ HTML signaled this item was on promo / discount.
     * Surfaces in the user's history ("você pegou uma oferta") and tells
     * the price-index pipeline NOT to use this row as a baseline price.
     */
    @Column(name = "nfce_promo_flag", nullable = false)
    @Builder.Default
    private boolean nfcePromoFlag = false;

    /**
     * User-entered price actually paid for this line when it was bought on
     * promotion. The NFC-e carries no per-item discount, so these are captured
     * manually in the review screen. {@link #unitPrice}/{@link #totalPrice} stay
     * as-printed (shelf price, still the price-index baseline); these hold what
     * was really paid. Null when the line has no manual discount. A line is
     * "promotional" when {@code paidTotalPrice} is present and below
     * {@code totalPrice}.
     */
    @Column(name = "paid_unit_price", precision = 12, scale = 4)
    private BigDecimal paidUnitPrice;

    @Column(name = "paid_total_price", precision = 12, scale = 2)
    private BigDecimal paidTotalPrice;

    /**
     * Category snapshot frozen at confirmation — "new knowledge only affects
     * new entries". The linked product's category keeps evolving (consensus,
     * dictionary backfills) for FUTURE purchases; confirmed history displays
     * and aggregates by this snapshot. Household overrides (explicit user
     * choice) still win over both. Null for unmatched/uncategorized items.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category_at_confirmation", length = 30)
    private ProductCategory categoryAtConfirmation;

    /** True when a manual paid total is recorded below the as-printed total — i.e. the line was on promotion. */
    public boolean isPromotional() {
        return paidTotalPrice != null
                && totalPrice != null
                && paidTotalPrice.compareTo(totalPrice) < 0;
    }
}
