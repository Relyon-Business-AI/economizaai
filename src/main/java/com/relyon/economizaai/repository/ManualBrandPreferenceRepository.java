package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.ManualBrandPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManualBrandPreferenceRepository extends JpaRepository<ManualBrandPreference, UUID> {

    List<ManualBrandPreference> findAllByHouseholdId(UUID householdId);

    List<ManualBrandPreference> findAllByOriginHouseholdId(UUID householdId);

    Optional<ManualBrandPreference> findByHouseholdIdAndGenericName(UUID householdId, String genericName);
}
