package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

public record UpdatePushTokenRequest(
        @Schema(description = "FCM device token from the mobile app. Send null or empty to clear " +
                "(e.g. on logout or when the user disables push notifications).",
                example = "fcm-token-from-firebase-messaging-sdk")
        @Size(max = 500) String pushDeviceToken
) {}
