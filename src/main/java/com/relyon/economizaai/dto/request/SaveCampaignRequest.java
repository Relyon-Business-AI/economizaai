package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Create/update payload for a notification campaign. {@code scheduledAt} null
 * keeps it a DRAFT; a future instant schedules it. {@code type} defaults to
 * SYSTEM (inbox deep-link); other types reuse their organic FE destination.
 * {@code extras} is forwarded verbatim to the push payload (deeplink etc).
 */
public record SaveCampaignRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Size(max = 4000) String body,
        NotificationType type,
        @NotNull UUID audienceId,
        OffsetDateTime scheduledAt,
        Map<String, Object> extras
) {}
