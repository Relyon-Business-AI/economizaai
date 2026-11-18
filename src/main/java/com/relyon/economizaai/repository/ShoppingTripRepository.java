package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.ShoppingTrip;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ShoppingTripRepository extends JpaRepository<ShoppingTrip, UUID> {

    /** The trip a device already sent, so a retry does not record it twice. */
    Optional<ShoppingTrip> findByHouseholdIdAndClientTripId(UUID householdId, String clientTripId);
}
