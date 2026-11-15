package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.AnnouncementDismissal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AnnouncementDismissalRepository extends JpaRepository<AnnouncementDismissal, UUID> {

    boolean existsByUserIdAndAnnouncementId(UUID userId, UUID announcementId);

    /** How many users dismissed each announcement of a batch; one never dismissed has no row. */
    @Query("""
        SELECT dismissal.announcement.id AS announcementId,
               COUNT(dismissal) AS dismissals
        FROM AnnouncementDismissal dismissal
        WHERE dismissal.announcement.id IN :announcementIds
        GROUP BY dismissal.announcement.id
    """)
    List<DismissalTally> tallyByAnnouncements(@Param("announcementIds") Collection<UUID> announcementIds);

    /** Projection for {@link #tallyByAnnouncements}. */
    interface DismissalTally {
        UUID getAnnouncementId();
        long getDismissals();
    }
}
