package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.Announcement;
import com.relyon.economizaai.model.enums.AnnouncementDestination;
import com.relyon.economizaai.model.enums.AnnouncementKind;

import java.util.UUID;

/** The announcement card the app shows on Home. {@code actionLabel}/{@code destination} come together or not at all. */
public record AnnouncementResponse(
        UUID id,
        AnnouncementKind kind,
        String title,
        String body,
        String actionLabel,
        AnnouncementDestination destination
) {
    public static AnnouncementResponse from(Announcement announcement) {
        return new AnnouncementResponse(
                announcement.getId(),
                announcement.getKind(),
                announcement.getTitle(),
                announcement.getBody(),
                announcement.getActionLabel(),
                announcement.getDestination());
    }
}
