package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.HouseholdProductCategoryOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HouseholdProductCategoryOverrideRepository
        extends JpaRepository<HouseholdProductCategoryOverride, UUID> {

    /** Quality KPI input: how many household corrections target LLM-labeled products. */
    @Query("SELECT COUNT(o) FROM HouseholdProductCategoryOverride o WHERE o.product.categorizationSource = 'LLM'")
    long countOverridesOnLlmLabeledProducts();


    List<HouseholdProductCategoryOverride> findAllByOriginHouseholdId(UUID householdId);

    Optional<HouseholdProductCategoryOverride> findByHouseholdIdAndProductId(UUID householdId, UUID productId);

    List<HouseholdProductCategoryOverride> findAllByHouseholdId(UUID householdId);

    List<HouseholdProductCategoryOverride> findByHouseholdIdAndProductIdIn(UUID householdId, List<UUID> productIds);

    List<HouseholdProductCategoryOverride> findByHouseholdIdAndCustomCategoryId(UUID householdId, UUID customCategoryId);
}
