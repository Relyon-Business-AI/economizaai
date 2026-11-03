package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.StoreAnalyticsDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoreAnalyticsDailyRepository extends JpaRepository<StoreAnalyticsDaily, UUID> {

    Optional<StoreAnalyticsDaily> findByPlatformAndReportDateAndSourceType(
            String platform, LocalDate reportDate, String sourceType);

    List<StoreAnalyticsDaily> findByPlatformAndReportDateBetweenOrderByReportDateDesc(
            String platform, LocalDate from, LocalDate to);

    @Query("SELECT MAX(r.reportDate) FROM StoreAnalyticsDaily r WHERE r.platform = :platform")
    Optional<LocalDate> findLatestReportDate(String platform);
}
