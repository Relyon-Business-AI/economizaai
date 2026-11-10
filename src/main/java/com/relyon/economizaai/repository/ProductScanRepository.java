package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.ProductScan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProductScanRepository extends JpaRepository<ProductScan, UUID> {

    /** Moves a household's scan history to another one (last member leaving absorbs the household). */
    @Modifying
    @Query("UPDATE ProductScan scan SET scan.householdId = :toHouseholdId WHERE scan.householdId = :fromHouseholdId")
    int reassignHousehold(@Param("fromHouseholdId") UUID fromHouseholdId, @Param("toHouseholdId") UUID toHouseholdId);
}
