package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record UpdateNotificationPreferencesRequest(
        @NotEmpty @Valid List<Preference> preferences
) {
    public record Preference(
            @NotNull NotificationType type,
            @NotNull NotificationChannel channel
    ) {}
}
