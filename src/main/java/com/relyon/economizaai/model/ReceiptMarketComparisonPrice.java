package com.relyon.economizaai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** The price of one product of the nota at one market of a {@link ReceiptMarketComparison}. */
@Entity
@Table(name = "receipt_market_comparison_prices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiptMarketComparisonPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comparison_id", nullable = false)
    private ReceiptMarketComparison comparison;

    /** Order of the market in the comparison, from 0 (the one covering the most items first). */
    @Column(name = "market_position", nullable = false)
    private int marketPosition;

    @Column(name = "market_cnpj", nullable = false, length = 14)
    private String marketCnpj;

    @Column(name = "market_name")
    private String marketName;

    @Column(name = "distance_km", precision = 8, scale = 3)
    private BigDecimal distanceKm;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal unitPrice;

    /** When that price was seen at the market. */
    @Column(name = "observed_at")
    private LocalDateTime observedAt;
}
