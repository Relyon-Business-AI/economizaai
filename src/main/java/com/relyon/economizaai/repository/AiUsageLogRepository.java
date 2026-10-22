package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.AiUsageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AiUsageLogRepository extends JpaRepository<AiUsageLog, UUID> {

    long countByCreatedAtAfter(LocalDateTime since);

    /** activity, calls, inputTokens, outputTokens, costUsd — the spend panel's aggregation. */
    @Query("""
        SELECT u.activity, count(u), coalesce(sum(u.inputTokens),0), coalesce(sum(u.outputTokens),0), coalesce(sum(u.costUsd),0)
        FROM AiUsageLog u
        WHERE u.createdAt >= :since
        GROUP BY u.activity
        ORDER BY sum(u.costUsd) DESC
    """)
    List<Object[]> summarizeByActivity(@Param("since") LocalDateTime since);

    @Query("""
        SELECT u.activity, count(u), coalesce(sum(u.inputTokens),0), coalesce(sum(u.outputTokens),0), coalesce(sum(u.costUsd),0)
        FROM AiUsageLog u
        GROUP BY u.activity
        ORDER BY sum(u.costUsd) DESC
    """)
    List<Object[]> summarizeByActivityAllTime();

    /** model, calls, inputTokens, outputTokens, costUsd, successCalls, avgDurationMs — comparação entre modelos. */
    @Query("""
        SELECT u.model, count(u), coalesce(sum(u.inputTokens),0), coalesce(sum(u.outputTokens),0),
               coalesce(sum(u.costUsd),0), coalesce(sum(case when u.success then 1 else 0 end),0),
               avg(u.durationMs)
        FROM AiUsageLog u
        WHERE u.model IS NOT NULL AND u.createdAt >= :since
        GROUP BY u.model
        ORDER BY sum(u.costUsd) DESC
    """)
    List<Object[]> summarizeByModel(@Param("since") LocalDateTime since);

    @Query("""
        SELECT u.model, count(u), coalesce(sum(u.inputTokens),0), coalesce(sum(u.outputTokens),0),
               coalesce(sum(u.costUsd),0), coalesce(sum(case when u.success then 1 else 0 end),0),
               avg(u.durationMs)
        FROM AiUsageLog u
        WHERE u.model IS NOT NULL
        GROUP BY u.model
        ORDER BY sum(u.costUsd) DESC
    """)
    List<Object[]> summarizeByModelAllTime();

    /** day, calls, costUsd — daily trend line. */
    @Query(value = """
        SELECT to_char(created_at, 'YYYY-MM-DD') AS day, count(*), coalesce(sum(cost_usd),0)
        FROM ai_usage_log
        WHERE created_at >= :since
        GROUP BY 1 ORDER BY 1
    """, nativeQuery = true)
    List<Object[]> summarizeByDay(@Param("since") LocalDateTime since);

    @Query(value = """
        SELECT to_char(created_at, 'YYYY-MM-DD') AS day, count(*), coalesce(sum(cost_usd),0)
        FROM ai_usage_log
        GROUP BY 1 ORDER BY 1
    """, nativeQuery = true)
    List<Object[]> summarizeByDayAllTime();

    @Query("SELECT coalesce(sum(u.costUsd), 0) FROM AiUsageLog u")
    java.math.BigDecimal totalCostAllTime();

    // ── Per-user COGS (lifetime LLM cost attributed to a user) ──────────────────

    /** Lifetime LLM cost (USD) for one user — the real token-based cost (not the flat paid_api_call estimate). */
    @Query("SELECT coalesce(sum(u.costUsd), 0) FROM AiUsageLog u WHERE u.userId = :userId")
    java.math.BigDecimal costUsdForUser(@Param("userId") UUID userId);

    /** userId, calls, costUsd per user for a batch — the admin list's per-user LLM column. */
    @Query("""
        SELECT u.userId, count(u), coalesce(sum(u.costUsd), 0)
        FROM AiUsageLog u
        WHERE u.userId IN :userIds
        GROUP BY u.userId
    """)
    List<Object[]> costUsdByUserIds(@Param("userIds") List<UUID> userIds);

    /** activity, calls, costUsd for one user — the LLM breakdown in the user detail. */
    @Query("""
        SELECT u.activity, count(u), coalesce(sum(u.costUsd), 0)
        FROM AiUsageLog u
        WHERE u.userId = :userId
        GROUP BY u.activity
        ORDER BY sum(u.costUsd) DESC
    """)
    List<Object[]> costUsdByActivityForUser(@Param("userId") UUID userId);

    /** Platform (non-user-attributable) LLM cost since — categorization + admin sweeps, amortized across users. */
    @Query("SELECT coalesce(sum(u.costUsd), 0) FROM AiUsageLog u WHERE u.userId IS NULL AND u.createdAt >= :since")
    java.math.BigDecimal platformCostUsdSince(@Param("since") LocalDateTime since);

    /** Total LLM cost since (user + platform) — the global AI spend figure. */
    @Query("SELECT coalesce(sum(u.costUsd), 0) FROM AiUsageLog u WHERE u.createdAt >= :since")
    java.math.BigDecimal totalCostUsdSince(@Param("since") LocalDateTime since);
}
