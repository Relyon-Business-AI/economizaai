package com.relyon.economizai.repository;

import com.relyon.economizai.model.RevenueEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface RevenueEventRepository extends JpaRepository<RevenueEvent, UUID> {

    boolean existsByProviderAndProviderRef(String provider, String providerRef);

    /** Realized revenue (sum of real amounts) since the window start, excluding internal accounts by default. */
    @Query("SELECT COALESCE(sum(event.amount), 0) FROM RevenueEvent event JOIN event.user user "
            + "WHERE event.occurredAt >= :since "
            + "AND (:includeInternal = TRUE OR (user.role <> 'ADMIN' AND user.excludedFromMetrics = FALSE "
            + "AND lower(user.email) NOT LIKE '%@economizaai.app' "
            + "AND lower(user.email) NOT LIKE '%@cloudtestlabaccounts.com'))")
    BigDecimal totalRealizedSince(LocalDateTime since, boolean includeInternal);

    /** (acquisitionChannel, realizedRevenue) since the window start. */
    @Query("SELECT user.acquisitionChannel, COALESCE(sum(event.amount), 0) "
            + "FROM RevenueEvent event JOIN event.user user "
            + "WHERE event.occurredAt >= :since "
            + "AND (:includeInternal = TRUE OR (user.role <> 'ADMIN' AND lower(user.email) NOT LIKE '%@economizaai.app')) "
            + "GROUP BY user.acquisitionChannel")
    List<Object[]> realizedRevenueByChannelSince(LocalDateTime since, boolean includeInternal);
}
