package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.PaidApiCall;
import com.relyon.economizaai.model.enums.PaidApiService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface PaidApiCallRepository extends JpaRepository<PaidApiCall, UUID> {

    /** How many calls a user has made to a paid service since {@code since} — the daily-cap counter. */
    long countByUserIdAndServiceAndCreatedAtGreaterThanEqual(UUID userId, PaidApiService service, OffsetDateTime since);

    /** Global (all-users) daily count for a service — caps system workers like LLM enrichment. */
    long countByServiceAndCreatedAtGreaterThanEqual(PaidApiService service, OffsetDateTime since);

    /** Total estimated spend (cents) across all users since {@code since} — the global kill-switch gauge. */
    @Query("SELECT COALESCE(SUM(call.estimatedCostCents), 0) FROM PaidApiCall call WHERE call.createdAt >= :since")
    long sumCostCentsSince(@Param("since") OffsetDateTime since);

    /** Spend + volume grouped by service since {@code since} — the cost report's service breakdown. */
    @Query("""
        SELECT call.service AS service,
               COUNT(call) AS calls,
               COALESCE(SUM(call.estimatedCostCents), 0) AS costCents,
               COALESCE(SUM(CASE WHEN call.success THEN 0 ELSE 1 END), 0) AS failures
        FROM PaidApiCall call
        WHERE call.createdAt >= :since
        GROUP BY call.service
    """)
    List<ServiceSpend> spendByService(@Param("since") OffsetDateTime since);

    /** Spend + volume grouped by UF since {@code since} — where the money goes by state. */
    @Query("""
        SELECT call.uf AS uf,
               COUNT(call) AS calls,
               COALESCE(SUM(call.estimatedCostCents), 0) AS costCents
        FROM PaidApiCall call
        WHERE call.createdAt >= :since
        GROUP BY call.uf
        ORDER BY SUM(call.estimatedCostCents) DESC
    """)
    List<StateSpend> spendByState(@Param("since") OffsetDateTime since);

    interface ServiceSpend {
        PaidApiService getService();
        long getCalls();
        long getCostCents();
        long getFailures();
    }

    interface StateSpend {
        String getUf();
        long getCalls();
        long getCostCents();
    }
}
