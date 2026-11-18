package com.relyon.economizaai.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One market of a {@link ShoppingTrip}, in route order, with the nota that closed it (if any). */
@Entity
@Table(name = "shopping_trip_stops")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingTripStop {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private ShoppingTrip trip;

    /** Order of the stop in the route, from 0. */
    @Column(nullable = false)
    private int position;

    @Column(name = "market_cnpj", nullable = false, length = 14)
    private String marketCnpj;

    @Column(name = "market_name")
    private String marketName;

    @Column(name = "distance_km", precision = 8, scale = 3)
    private BigDecimal distanceKm;

    @Column(nullable = false)
    private boolean concluded;

    @Column(name = "concluded_at")
    private OffsetDateTime concludedAt;

    /** Predicted total of the items that ended up at this stop. */
    @Column(name = "planned_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal plannedTotal;

    /** The nota that closed the stop; null when it was concluded without one. */
    @Column(name = "receipt_id")
    private UUID receiptId;

    /** Paid and predicted totals of the stop's items that were on the nota. */
    @Column(name = "receipt_paid_total", precision = 12, scale = 2)
    private BigDecimal receiptPaidTotal;

    @Column(name = "receipt_predicted_total", precision = 12, scale = 2)
    private BigDecimal receiptPredictedTotal;

    @OneToMany(mappedBy = "stop", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<ShoppingTripItem> items = new ArrayList<>();

    public void addItem(ShoppingTripItem item) {
        items.add(item);
        item.setStop(this);
    }
}
