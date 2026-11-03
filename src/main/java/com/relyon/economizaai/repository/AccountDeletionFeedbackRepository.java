package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.AccountDeletionFeedback;
import com.relyon.economizaai.model.enums.AccountDeletionReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AccountDeletionFeedbackRepository extends JpaRepository<AccountDeletionFeedback, UUID> {

    /** Every deletion (reason given or skipped) in the window — the panel's total. */
    long countByCreatedAtAfter(OffsetDateTime since);

    /** Deletions grouped by reason in the window; a null reason is the "didn't say" bucket. */
    @Query("""
            SELECT feedback.reason AS reason, count(feedback) AS total
            FROM AccountDeletionFeedback feedback
            WHERE feedback.createdAt >= :since
            GROUP BY feedback.reason
            """)
    List<ReasonCount> reasonCountsSince(@Param("since") OffsetDateTime since);

    /** Most recent free-text notes in the window (for the "what people wrote" list). */
    List<AccountDeletionFeedback> findTop20ByDetailsIsNotNullAndCreatedAtAfterOrderByCreatedAtDesc(OffsetDateTime since);

    interface ReasonCount {
        AccountDeletionReason getReason();
        long getTotal();
    }
}
