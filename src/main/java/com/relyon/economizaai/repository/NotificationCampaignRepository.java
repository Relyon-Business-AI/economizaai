package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.NotificationCampaign;
import com.relyon.economizaai.model.enums.CampaignStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface NotificationCampaignRepository extends JpaRepository<NotificationCampaign, UUID> {

    @Query(value = "SELECT campaign FROM NotificationCampaign campaign JOIN FETCH campaign.audience "
            + "ORDER BY campaign.createdAt DESC",
            countQuery = "SELECT COUNT(campaign) FROM NotificationCampaign campaign")
    Page<NotificationCampaign> findAllWithAudience(Pageable pageable);

    List<NotificationCampaign> findByStatusAndScheduledAtLessThanEqual(CampaignStatus status, OffsetDateTime cutoff);

    /** SENDING rows stranded by a restart mid-dispatch — swept to FAILED. */
    List<NotificationCampaign> findByStatusAndUpdatedAtBefore(CampaignStatus status, LocalDateTime cutoff);

    boolean existsByAudienceId(UUID audienceId);

    /**
     * Atomic claim for dispatch: only one instance (or scheduler tick) wins the
     * SCHEDULED → SENDING transition; the loser sees 0 rows updated and skips.
     */
    @Modifying
    @Query("""
        UPDATE NotificationCampaign campaign
        SET campaign.status = :sending, campaign.startedAt = :now, campaign.updatedAt = :nowLocal
        WHERE campaign.id = :id AND campaign.status = :scheduled
    """)
    int claimForSending(@Param("id") UUID id,
                        @Param("sending") CampaignStatus sending,
                        @Param("scheduled") CampaignStatus scheduled,
                        @Param("now") OffsetDateTime now,
                        @Param("nowLocal") LocalDateTime nowLocal);
}
