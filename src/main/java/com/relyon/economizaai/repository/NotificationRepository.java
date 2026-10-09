package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findAllByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** Cross-user sent notifications since a cutoff — the admin "notificações enviadas" list. */
    @Query("SELECT n FROM Notification n JOIN FETCH n.user WHERE n.createdAt >= :since ORDER BY n.createdAt DESC")
    Page<Notification> findSentSince(@Param("since") LocalDateTime since, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.user.id = :userId AND n.readAt IS NULL")
    int markAllReadForUser(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /** Per-type sent/delivered/read tallies since a cutoff — the effectiveness report's byType block. */
    @Query("""
        SELECT notification.type AS type,
               COUNT(notification) AS sent,
               SUM(CASE WHEN notification.delivered = true THEN 1 ELSE 0 END) AS delivered,
               SUM(CASE WHEN notification.readAt IS NOT NULL THEN 1 ELSE 0 END) AS read
        FROM Notification notification
        WHERE notification.createdAt >= :since
        GROUP BY notification.type
    """)
    List<TypeTally> tallyByTypeSince(@Param("since") LocalDateTime since);

    /** Projection for {@link #tallyByTypeSince}. */
    interface TypeTally {
        NotificationType getType();
        long getSent();
        long getDelivered();
        long getRead();
    }

    /** Top notification titles by send volume since a cutoff (page-limited). */
    @Query("""
        SELECT notification.title AS title,
               COUNT(notification) AS sent,
               SUM(CASE WHEN notification.readAt IS NOT NULL THEN 1 ELSE 0 END) AS read
        FROM Notification notification
        WHERE notification.createdAt >= :since
          AND notification.title IS NOT NULL
        GROUP BY notification.title
        ORDER BY COUNT(notification) DESC
    """)
    List<TitleTally> topTitlesSince(@Param("since") LocalDateTime since, Pageable pageable);

    /** Projection for {@link #topTitlesSince}. */
    interface TitleTally {
        String getTitle();
        long getSent();
        long getRead();
    }

    /**
     * Sent/read counts bucketed by Brasília hour-of-day (0..23). created_at is a
     * UTC wall-clock timestamp (BaseEntity writes LocalDateTime.now() on UTC
     * hosts), so interpret it as UTC before converting to São Paulo local time.
     */
    @Query(value = """
        SELECT EXTRACT(HOUR FROM (created_at AT TIME ZONE 'UTC') AT TIME ZONE 'America/Sao_Paulo')::int AS bucket,
               COUNT(*) AS sent,
               COUNT(*) FILTER (WHERE read_at IS NOT NULL) AS read
        FROM notifications
        WHERE created_at >= :since
        GROUP BY bucket
    """, nativeQuery = true)
    List<TimeBucketTally> tallyByHourSince(@Param("since") LocalDateTime since);

    /**
     * Sent/read counts bucketed by Brasília ISO day-of-week (1=Mon..7=Sun). See
     * {@link #tallyByHourSince} for the UTC→São Paulo conversion rationale.
     */
    @Query(value = """
        SELECT EXTRACT(ISODOW FROM (created_at AT TIME ZONE 'UTC') AT TIME ZONE 'America/Sao_Paulo')::int AS bucket,
               COUNT(*) AS sent,
               COUNT(*) FILTER (WHERE read_at IS NOT NULL) AS read
        FROM notifications
        WHERE created_at >= :since
        GROUP BY bucket
    """, nativeQuery = true)
    List<TimeBucketTally> tallyByDayOfWeekSince(@Param("since") LocalDateTime since);

    /** Projection for the hour / day-of-week native tallies. */
    interface TimeBucketTally {
        int getBucket();
        long getSent();
        long getRead();
    }

    /** Outbox funnel per campaign (sent/delivered/read) for a batch of campaigns. */
    @Query("""
        SELECT notification.campaignId AS campaignId,
               COUNT(notification) AS sent,
               COALESCE(SUM(CASE WHEN notification.delivered = true THEN 1 ELSE 0 END), 0) AS delivered,
               COALESCE(SUM(CASE WHEN notification.readAt IS NOT NULL THEN 1 ELSE 0 END), 0) AS read
        FROM Notification notification
        WHERE notification.campaignId IN :campaignIds
        GROUP BY notification.campaignId
    """)
    List<CampaignTally> tallyByCampaigns(@Param("campaignIds") Collection<UUID> campaignIds);

    /** Projection for {@link #tallyByCampaigns}. */
    interface CampaignTally {
        UUID getCampaignId();
        long getSent();
        long getDelivered();
        long getRead();
    }
}
