package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.AnnouncementDestination;
import com.relyon.economizaai.model.enums.AnnouncementKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * Create/update payload for an announcement (an update replaces every field).
 * {@code actionLabel} and {@code destination} come together or not at all.
 * {@code startsAt}/{@code endsAt} are Brasília wall-clock, ISO without offset;
 * {@code startsAt} null means "now", {@code endsAt} null means "until switched off",
 * {@code active} null means true.
 */
public record SaveAnnouncementRequest(
        @NotNull AnnouncementKind kind,
        @NotBlank @Size(max = 80) String title,
        @Size(max = 160) String body,
        @Size(max = 30) String actionLabel,
        AnnouncementDestination destination,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Boolean active
) {}
