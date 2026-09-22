package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Client-reported notification telemetry event. {@code type} is taken as a raw
 * string and validated against the client-reportable subset of
 * {@link com.relyon.economizaai.model.enums.NotificationEventType} server-side
 * (unknown or server-only types are rejected with 400).
 */
public record RecordNotificationEventRequest(
        @Schema(description = "Client-reportable event type.", requiredMode = Schema.RequiredMode.REQUIRED,
                example = "DEAL_TAPPED")
        @NotBlank String type,

        @Schema(description = "Originating notification UUID, if any.")
        UUID notificationId,

        @Schema(description = "Canonical product UUID the event is about, if any.")
        UUID productId,

        @Schema(description = "Market CNPJ (14 digits) the event is about, if any.")
        String marketCnpj
) {}
