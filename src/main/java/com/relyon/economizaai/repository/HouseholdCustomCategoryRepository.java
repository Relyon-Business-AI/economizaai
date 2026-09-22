package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.HouseholdCustomCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HouseholdCustomCategoryRepository extends JpaRepository<HouseholdCustomCategory, UUID> {

    List<HouseholdCustomCategory> findAllByHouseholdId(UUID householdId);

    List<HouseholdCustomCategory> findAllByOriginHouseholdId(UUID householdId);

    List<HouseholdCustomCategory> findByHouseholdIdOrderByName(UUID householdId);

    Optional<HouseholdCustomCategory> findByHouseholdIdAndNameIgnoreCase(UUID householdId, String name);

    Optional<HouseholdCustomCategory> findByIdAndHouseholdId(UUID id, UUID householdId);
}
