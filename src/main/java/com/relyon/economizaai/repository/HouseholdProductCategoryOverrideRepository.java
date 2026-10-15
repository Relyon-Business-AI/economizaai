package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.HouseholdProductCategoryOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Override-aware spend resolution reads custom category (+ its parents) and product per row —
     *  fetch them in one query to avoid N+1 lazy loads inside the cached aggregation. */
    @Query("""
            SELECT o FROM HouseholdProductCategoryOverride o
            LEFT JOIN FETCH o.customCategory customCategory
            LEFT JOIN FETCH customCategory.parentCategory
            LEFT JOIN FETCH customCategory.parentCustomCategory
            LEFT JOIN FETCH o.product
            WHERE o.household.id = :householdId AND o.product.id IN :productIds
            """)
    List<HouseholdProductCategoryOverride> findByHouseholdIdAndProductIdIn(
            @Param("householdId") UUID householdId, @Param("productIds") List<UUID> productIds);

    List<HouseholdProductCategoryOverride> findByHouseholdIdAndCustomCategoryId(UUID householdId, UUID customCategoryId);
}
