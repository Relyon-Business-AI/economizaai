package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.CommunityPriceCoverage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface CommunityPriceCoverageRepository extends JpaRepository<CommunityPriceCoverage, UUID> {

    List<CommunityPriceCoverage> findAllByReferenceMonthBetween(LocalDate fromMonth, LocalDate toMonth);

    @Modifying
    @Query("DELETE FROM CommunityPriceCoverage coverage WHERE coverage.referenceMonth = :referenceMonth")
    int deleteMonth(@Param("referenceMonth") LocalDate referenceMonth);
}
