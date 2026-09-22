package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;

public record NotificationPreferenceResponse(
        NotificationType type,
        NotificationChannel channel
) {}
