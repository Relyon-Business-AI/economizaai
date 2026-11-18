package com.relyon.economizaai.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "Esta compra em outros mercados", frozen when the nota was confirmed: the price of each item
 * at up to three nearby markets on that day. Reads use this snapshot instead of pricing again,
 * so the comparison of a purchase does not drift as market prices change. What the household
 * paid is NOT stored here — it always comes from the nota's current items.
 */
@Entity
@Table(name = "receipt_market_comparisons")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptMarketComparison {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "receipt_id", nullable = false, unique = true)
    private UUID receiptId;

    @Column(name = "computed_at", nullable = false)
    private OffsetDateTime computedAt;

    @OneToMany(mappedBy = "comparison", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<ReceiptMarketComparisonPrice> prices = new ArrayList<>();

    public void addPrice(ReceiptMarketComparisonPrice price) {
        prices.add(price);
        price.setComparison(this);
    }
}
