package com.relyon.economizaai.repository;

import com.relyon.economizaai.model.Announcement;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

    /**
     * Announcements the user can see at {@code brasiliaNow} (active, started, not ended, not
     * dismissed by them), oldest start first — so the next one shows once the first is dismissed.
     */
    @Query("""
        SELECT announcement
        FROM Announcement announcement
        WHERE announcement.active = true
          AND announcement.startsAt <= :brasiliaNow
          AND (announcement.endsAt IS NULL OR announcement.endsAt > :brasiliaNow)
          AND NOT EXISTS (
              SELECT dismissal.id
              FROM AnnouncementDismissal dismissal
              WHERE dismissal.announcement = announcement
                AND dismissal.user.id = :userId)
        ORDER BY announcement.startsAt ASC, announcement.createdAt ASC
    """)
    List<Announcement> findVisibleTo(@Param("userId") UUID userId,
                                     @Param("brasiliaNow") LocalDateTime brasiliaNow,
                                     Limit limit);

    List<Announcement> findAllByOrderByCreatedAtDesc();
}
