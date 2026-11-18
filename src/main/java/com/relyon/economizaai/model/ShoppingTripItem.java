package com.relyon.economizaai.model;

import com.relyon.economizaai.dto.response.ShoppingPlanResponse.PlanItem.PriceSource;
import com.relyon.economizaai.model.enums.ShoppingTripItemStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** One product of a {@link ShoppingTripStop}: what was predicted and what happened to it. */
@Entity
@Table(name = "shopping_trip_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingTripItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stop_id", nullable = false)
    private ShoppingTripStop stop;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    /** Predicted unit price at the stop the item ended up at; null when there was none. */
    @Column(name = "planned_unit_price", precision = 12, scale = 2)
    private BigDecimal plannedUnitPrice;

    /** Where the predicted price came from; null when the item did not come from the plan. */
    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", length = 20)
    private PriceSource priceSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ShoppingTripItemStatus status;

    @Column(name = "in_receipt", nullable = false)
    private boolean inReceipt;

    /** Unit price on the nota; null when the item was not on it. */
    @Column(name = "paid_unit_price", precision = 12, scale = 2)
    private BigDecimal paidUnitPrice;

    /**
     * The stop the plan put the item at. Different from the stop's own position = the shopper
     * took it to another stop ("não achei"). Null = added during the trip.
     */
    @Column(name = "planned_stop_position")
    private Integer plannedStopPosition;

    @Column(name = "added_during_trip", nullable = false)
    private boolean addedDuringTrip;
}
