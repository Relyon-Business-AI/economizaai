package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.ConsumptionSnooze;
import com.relyon.economizaai.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsumptionSnoozeRepository extends JpaRepository<ConsumptionSnooze, UUID> {

    List<ConsumptionSnooze> findAllByHouseholdId(UUID householdId);

    List<ConsumptionSnooze> findAllByOriginHouseholdId(UUID householdId);

    Optional<ConsumptionSnooze> findByHouseholdIdAndProductId(UUID householdId, UUID productId);

    List<ConsumptionSnooze> findAllByHouseholdIdAndSnoozedUntilAfter(UUID householdId, LocalDateTime now);

    void deleteByHouseholdIdAndProductId(UUID householdId, UUID productId);

    long countByProduct(Product product);

    /**
     * Deletes absorbed's snoozes for households that already have a snooze
     * on the survivor — would otherwise violate UNIQUE (household_id, product_id).
     * Call before {@link #repointProduct(Product, Product)}.
     */
    @Modifying
    @Query("""
        DELETE FROM ConsumptionSnooze s
        WHERE s.product = :absorbed
          AND s.household.id IN (SELECT s2.household.id FROM ConsumptionSnooze s2 WHERE s2.product = :survivor)
    """)
    int deleteAbsorbedConflictsWithSurvivor(@Param("absorbed") Product absorbed, @Param("survivor") Product survivor);

    @Modifying
    @Query("UPDATE ConsumptionSnooze s SET s.product = :survivor WHERE s.product = :absorbed")
    int repointProduct(@Param("absorbed") Product absorbed, @Param("survivor") Product survivor);
}
