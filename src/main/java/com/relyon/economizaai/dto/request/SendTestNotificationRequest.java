package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendTestNotificationRequest(
        @Schema(description = "Target user's email — must already exist.", example = "user@example.com")
        @NotBlank @Email String email,

        @Schema(description = "Notification title shown on the device. Defaults to 'economizai test'.")
        @Size(max = 120) String title,

        @Schema(description = "Notification body. Defaults to a canned message.")
        @Size(max = 500) String body,

        @Schema(description = "NotificationType to record. Defaults to SYSTEM.", example = "SYSTEM")
        NotificationType type
) {}
