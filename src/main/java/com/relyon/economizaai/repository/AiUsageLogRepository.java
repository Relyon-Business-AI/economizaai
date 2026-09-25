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

    /** day, calls, costUsd — daily trend line. */
    @Query(value = """
        SELECT to_char(created_at, 'YYYY-MM-DD') AS day, count(*), coalesce(sum(cost_usd),0)
        FROM ai_usage_log
        WHERE created_at >= :since
        GROUP BY 1 ORDER BY 1
    """, nativeQuery = true)
    List<Object[]> summarizeByDay(@Param("since") LocalDateTime since);
}
