package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.ShoppingTrip;
import com.relyon.economizaai.model.ShoppingTripAlternative;
import com.relyon.economizaai.model.ShoppingTripItem;
import com.relyon.economizaai.model.ShoppingTripStop;
import com.relyon.economizaai.model.enums.ShoppingTripItemStatus;
import com.relyon.economizaai.model.enums.ShoppingTripOutcome;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@ActiveProfiles("test")
class ShoppingTripRepositoryTest {

    @Autowired private ShoppingTripRepository tripRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void savesTripWithStopsAndItemsAndFindsItByClientTripId() {
        var householdId = UUID.randomUUID();
        var trip = ShoppingTrip.builder()
                .userId(UUID.randomUUID())
                .householdId(householdId)
                .clientTripId("trip-from-device-1")
                .startedAt(OffsetDateTime.now().minusHours(1))
                .finishedAt(OffsetDateTime.now())
                .outcome(ShoppingTripOutcome.COMPLETED)
                .plannedTotal(new BigDecimal("38.64"))
                .plannedStopCount(1)
                .skippedSavingTotal(BigDecimal.ZERO)
                .createdAt(OffsetDateTime.now())
                .build();
        var stop = ShoppingTripStop.builder()
                .position(0)
                .marketCnpj("11111111000111")
                .concluded(true)
                .plannedTotal(new BigDecimal("38.64"))
                .build();
        stop.addItem(ShoppingTripItem.builder()
                .productId(UUID.randomUUID())
                .quantity(new BigDecimal("6"))
                .plannedUnitPrice(new BigDecimal("5.29"))
                .status(ShoppingTripItemStatus.IN_CART)
                .build());
        trip.addStop(stop);
        trip.addAlternative(ShoppingTripAlternative.builder()
                .marketCnpj("22222222000122")
                .matchedItemCount(1)
                .boughtItemCount(1)
                .basketTotal(new BigDecimal("31.74"))
                .alternativeTotal(new BigDecimal("28.74"))
                .build());
        tripRepository.save(trip);
        entityManager.flush();
        entityManager.clear();

        var found = tripRepository.findByHouseholdIdAndClientTripId(householdId, "trip-from-device-1");

        assertTrue(found.isPresent());
        assertEquals(1, found.get().getStops().size());
        assertEquals(new BigDecimal("28.74"), found.get().getAlternatives().get(0).getAlternativeTotal());
        assertEquals(ShoppingTripItemStatus.IN_CART, found.get().getStops().get(0).getItems().get(0).getStatus());
        assertTrue(tripRepository.findByHouseholdIdAndClientTripId(UUID.randomUUID(), "trip-from-device-1").isEmpty());
    }
}
