package com.relyon.economizaai.model;

import com.relyon.economizaai.model.enums.ShoppingTripOutcome;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
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

/**
 * One shopping trip done through the app's "modo de compra": what the plan predicted (route,
 * rules, prices) and what happened at each stop. Written once by the app when the trip ends.
 * Internal only — it exists so "predicted vs paid" can be analysed later and is never exposed
 * by an endpoint.
 */
@Entity
@Table(name = "shopping_trips")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShoppingTrip {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    /** The list the plan came from; kept even if the list is deleted later. */
    @Column(name = "shopping_list_id")
    private UUID shoppingListId;

    /** Generated on the device, so re-sending the same trip never creates a second row. */
    @Column(name = "client_trip_id", nullable = false, length = 64)
    private String clientTripId;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "finished_at", nullable = false)
    private OffsetDateTime finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShoppingTripOutcome outcome;

    @Column(name = "max_distance_km", precision = 6, scale = 1)
    private BigDecimal maxDistanceKm;

    @Column(name = "max_stops")
    private Integer maxStops;

    @Column(name = "min_saving_per_stop", precision = 12, scale = 2)
    private BigDecimal minSavingPerStop;

    @Column(name = "planned_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal plannedTotal;

    @Column(name = "planned_stop_count", nullable = false)
    private int plannedStopCount;

    @Column(name = "unpriced_item_count", nullable = false)
    private int unpricedItemCount;

    @Column(name = "skipped_saving_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal skippedSavingTotal;

    @Column(name = "price_lookup_count", nullable = false)
    private int priceLookupCount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    @BatchSize(size = 50)
    @Builder.Default
    private List<ShoppingTripStop> stops = new ArrayList<>();

    public void addStop(ShoppingTripStop stop) {
        stops.add(stop);
        stop.setTrip(this);
    }

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    @Builder.Default
    private List<ShoppingTripAlternative> alternatives = new ArrayList<>();

    public void addAlternative(ShoppingTripAlternative alternative) {
        alternatives.add(alternative);
        alternative.setTrip(this);
    }
}
