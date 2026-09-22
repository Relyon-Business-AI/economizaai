package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.NotificationEvent;
import com.relyon.economizaai.model.enums.NotificationEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, UUID> {

    /** All notification-telemetry events for a user — used by the LGPD data export. */
    List<NotificationEvent> findAllByUserId(UUID userId);

    /**
     * North-star rollup (Phase D): total realized R$ savings and conversion count
     * from {@code CONVERTED} events across a household's users, optionally since a
     * cutoff. {@code COALESCE} keeps the sum at zero when there are no rows.
     */
    @Query("""
        SELECT COALESCE(SUM(event.savingsAmount), 0) AS totalSavings, COUNT(event) AS conversions
        FROM NotificationEvent event
        WHERE event.user.id IN :userIds
          AND event.eventType = 'CONVERTED'
          AND (CAST(:since AS java.time.OffsetDateTime) IS NULL OR event.occurredAt >= :since)
    """)
    SavingsRollup sumConvertedSavings(@Param("userIds") Collection<UUID> userIds,
                                      @Param("since") OffsetDateTime since);

    /** Projection for {@link #sumConvertedSavings}. */
    interface SavingsRollup {
        BigDecimal getTotalSavings();
        long getConversions();
    }

    /**
     * A user's own deal-feedback signals (DISMISSED / MUTED with a product)
     * inside the suppression horizon — input for {@code DealFeedbackService}.
     */
    @Query("""
        SELECT event
        FROM NotificationEvent event
        WHERE event.user.id = :userId
          AND event.eventType IN ('DISMISSED', 'MUTED')
          AND event.productId IS NOT NULL
          AND event.occurredAt >= :since
    """)
    List<NotificationEvent> findFeedbackSignals(@Param("userId") UUID userId,
                                                          @Param("since") OffsetDateTime since);

    /** Event volume per type in a window — the relevance report's engagement block. */
    @Query("""
        SELECT event.eventType AS eventType, COUNT(event) AS occurrences,
               COALESCE(SUM(event.savingsAmount), 0) AS savings
        FROM NotificationEvent event
        WHERE event.occurredAt >= :since
        GROUP BY event.eventType
    """)
    List<TypeCount> countByTypeSince(@Param("since") OffsetDateTime since);

    /** Projection for {@link #countByTypeSince}. */
    interface TypeCount {
        NotificationEventType getEventType();
        long getOccurrences();
        BigDecimal getSavings();
    }

    /**
     * Product-keyed signals for the regret analysis: suppression signals
     * (DISMISSED/MUTED) plus the engagement events a suppression could have
     * prevented (DEAL_TAPPED / ADDED_TO_LIST / CONVERTED), joined in code.
     */
    @Query("""
        SELECT event.user.id AS userId, event.productId AS productId,
               event.eventType AS eventType, event.occurredAt AS occurredAt,
               event.savingsAmount AS savingsAmount
        FROM NotificationEvent event
        WHERE event.occurredAt >= :since
          AND event.productId IS NOT NULL
          AND event.eventType IN ('DISMISSED', 'MUTED', 'DEAL_TAPPED', 'ADDED_TO_LIST', 'CONVERTED')
    """)
    List<RelevanceSignal> findRelevanceSignals(@Param("since") OffsetDateTime since);

    /** Projection for {@link #findRelevanceSignals}. */
    interface RelevanceSignal {
        UUID getUserId();
        UUID getProductId();
        NotificationEventType getEventType();
        OffsetDateTime getOccurredAt();
        BigDecimal getSavingsAmount();
    }
}
