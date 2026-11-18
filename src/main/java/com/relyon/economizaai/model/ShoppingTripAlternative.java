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
import java.util.UUID;

/**
 * What the items BOUGHT on a {@link ShoppingTrip} would have cost at one single nearby market.
 * Only the items that market has a price for are compared: {@code basketTotal} is what the trip
 * predicted for those same items, {@code alternativeTotal} what they cost there.
 */
@Entity
@Table(name = "shopping_trip_alternatives")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingTripAlternative {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private ShoppingTrip trip;

    @Column(name = "market_cnpj", nullable = false, length = 14)
    private String marketCnpj;

    @Column(name = "market_name")
    private String marketName;

    @Column(name = "distance_km", precision = 8, scale = 3)
    private BigDecimal distanceKm;

    @Column(name = "matched_item_count", nullable = false)
    private int matchedItemCount;

    @Column(name = "bought_item_count", nullable = false)
    private int boughtItemCount;

    @Column(name = "basket_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal basketTotal;

    @Column(name = "alternative_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal alternativeTotal;
}
