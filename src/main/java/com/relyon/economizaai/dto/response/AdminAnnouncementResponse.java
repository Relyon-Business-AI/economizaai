package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.Announcement;
import com.relyon.economizaai.model.enums.AnnouncementDestination;
import com.relyon.economizaai.model.enums.AnnouncementKind;
import com.relyon.economizaai.model.enums.AnnouncementStatus;
import com.relyon.economizaai.time.BrazilClock;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One announcement as the admin sees it. Every date-time is Brasília wall-clock, ISO without
 * offset. {@code status} is computed at read time; {@code dismissalCount} is how many users
 * closed the card.
 */
public record AdminAnnouncementResponse(
        UUID id,
        AnnouncementKind kind,
        String title,
        String body,
        String actionLabel,
        AnnouncementDestination destination,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        boolean active,
        AnnouncementStatus status,
        long dismissalCount,
        LocalDateTime createdAt
) {
    public static AdminAnnouncementResponse from(Announcement announcement, LocalDateTime brasiliaNow, long dismissalCount) {
        return new AdminAnnouncementResponse(
                announcement.getId(),
                announcement.getKind(),
                announcement.getTitle(),
                announcement.getBody(),
                announcement.getActionLabel(),
                announcement.getDestination(),
                announcement.getStartsAt(),
                announcement.getEndsAt(),
                announcement.isActive(),
                announcement.statusAt(brasiliaNow),
                dismissalCount,
                BrazilClock.fromUtc(announcement.getCreatedAt()));
    }
}
