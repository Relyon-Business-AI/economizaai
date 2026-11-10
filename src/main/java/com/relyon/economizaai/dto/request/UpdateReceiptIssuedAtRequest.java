package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/** Purchase date of a photo nota, typed by the user when the photo didn't show it. */
public record UpdateReceiptIssuedAtRequest(
        @Schema(description = "Purchase date/time, Brazil local time, ISO without zone.", example = "2026-11-08T14:30:00")
        @NotNull LocalDateTime issuedAt
) {}
