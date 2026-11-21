package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.OfficialInflationMonth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OfficialInflationMonthRepository extends JpaRepository<OfficialInflationMonth, UUID> {

    List<OfficialInflationMonth> findAllByReferenceMonthGreaterThanEqual(LocalDate fromMonth);

    /** Every item of one area over a range of months. */
    List<OfficialInflationMonth> findAllByAreaCodeAndReferenceMonthBetween(String areaCode, LocalDate fromMonth,
                                                                         LocalDate toMonth);

    /** One item in every area over a range of months. */
    List<OfficialInflationMonth> findAllByItemCodeAndReferenceMonthBetween(String itemCode, LocalDate fromMonth,
                                                                         LocalDate toMonth);

    Optional<OfficialInflationMonth> findByAreaCodeAndItemCodeAndReferenceMonth(String areaCode, String itemCode,
                                                                               LocalDate referenceMonth);

    @Query("""
            SELECT MAX(official.referenceMonth) FROM OfficialInflationMonth official
            WHERE official.areaCode = :areaCode AND official.itemCode = :itemCode
            """)
    Optional<LocalDate> latestMonth(@Param("areaCode") String areaCode, @Param("itemCode") String itemCode);

    /** The areas that belong to a state: (state, areaCode, areaName). */
    @Query("""
            SELECT DISTINCT official.state, official.areaCode, official.areaName
            FROM OfficialInflationMonth official
            WHERE official.state IS NOT NULL
            ORDER BY official.state, official.areaCode
            """)
    List<Object[]> stateAreas();
}
