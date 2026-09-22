package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.HouseholdProductAlias;
import com.relyon.economizaai.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HouseholdProductAliasRepository extends JpaRepository<HouseholdProductAlias, UUID> {

    Optional<HouseholdProductAlias> findByHouseholdIdAndProductId(UUID householdId, UUID productId);

    List<HouseholdProductAlias> findAllByHouseholdId(UUID householdId);

    List<HouseholdProductAlias> findAllByOriginHouseholdId(UUID householdId);

    List<HouseholdProductAlias> findAllByHouseholdIdAndProductIdIn(UUID householdId, List<UUID> productIds);

    /** Products this household renamed to something matching the query (case-insensitive substring). */
    @Query("""
        SELECT alias.product FROM HouseholdProductAlias alias
        WHERE alias.household.id = :householdId
          AND LOWER(alias.friendlyName) LIKE LOWER(CONCAT('%', :query, '%'))
    """)
    List<Product> findProductsByFriendlyNameContaining(@Param("householdId") UUID householdId,
                                                       @Param("query") String query);

    long countByProduct(Product product);

    @Modifying
    @Query("""
        DELETE FROM HouseholdProductAlias h
        WHERE h.product = :absorbed
          AND h.household.id IN (SELECT h2.household.id FROM HouseholdProductAlias h2 WHERE h2.product = :survivor)
    """)
    int deleteAbsorbedConflictsWithSurvivor(@Param("absorbed") Product absorbed, @Param("survivor") Product survivor);

    @Modifying
    @Query("UPDATE HouseholdProductAlias h SET h.product = :survivor WHERE h.product = :absorbed")
    int repointProduct(@Param("absorbed") Product absorbed, @Param("survivor") Product survivor);
}
