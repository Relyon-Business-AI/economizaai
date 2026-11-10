package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.HouseholdProductSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HouseholdProductSnapshotRepository extends JpaRepository<HouseholdProductSnapshot, UUID> {

    Optional<HouseholdProductSnapshot> findByHouseholdIdAndSnapshotKey(UUID householdId, String snapshotKey);

    /** Household's consulted products, newest consult first — the history list (capped by Pageable). */
    List<HouseholdProductSnapshot> findByHouseholdIdOrderByLastConsultedAtDesc(UUID householdId, Pageable pageable);
}
