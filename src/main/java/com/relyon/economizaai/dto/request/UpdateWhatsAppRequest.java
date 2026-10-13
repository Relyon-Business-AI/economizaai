package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record UpdateWhatsAppRequest(
        @Schema(description = "Enable/disable the weekly spending report on WhatsApp.", example = "true")
        @NotNull
        Boolean weeklyReportEnabled
) {}
