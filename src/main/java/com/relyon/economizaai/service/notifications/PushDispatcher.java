package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mobile push channel backed by the Expo Push Service. The FE registers
 * an Expo Push Token (format {@code ExponentPushToken[...]}) via
 * {@code PUT /api/v1/users/me/push-token}; we POST that token plus the
 * payload to Expo, which routes to FCM (Android) or APNs (iOS).
 *
 * <p>Why Expo and not Firebase Admin directly: the FE is a React Native
 * Expo app, so it generates Expo tokens — those don't accept raw FCM
 * sends. Expo's HTTP API is also simpler than wiring firebase-admin
 * (no service-account JSON, no SDK init, no native config required).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushDispatcher implements NotificationDispatcher {

    /**
     * Expo token format: {@code Exp(onent)?PushToken[<base64url-ish>]}.
     * The inner segment must be non-empty and contain no whitespace —
     * loose enough to survive minor Expo format tweaks but strict enough
     * to reject empty brackets, accidental padding, and unicode lookalikes.
     */
    private static final Pattern EXPO_TOKEN_PATTERN =
            Pattern.compile("^Exp(onent)?PushToken\\[\\S+]$");

    private final ExpoPushClient expoPushClient;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public DispatchResult dispatch(NotificationPayload payload) {
        var token = payload.user().getPushDeviceToken();
        if (token == null || token.isBlank()) {
            return DispatchResult.failed("user has no push device token registered");
        }
        var trimmed = token.trim();
        if (!EXPO_TOKEN_PATTERN.matcher(trimmed).matches()) {
            log.warn("notification.push.invalid_token user={} token={}",
                    LogMasker.email(payload.user().getEmail()), LogMasker.token(trimmed));
            return DispatchResult.failed("token is not an Expo push token (expected ExponentPushToken[...])");
        }
        if (payload.title() == null || payload.title().isBlank()
                || payload.body() == null || payload.body().isBlank()) {
            return DispatchResult.failed("title and body are required");
        }
        var result = expoPushClient.send(trimmed, payload.title(), payload.body(), buildData(payload));
        if (result.ok()) {
            log.info("notification.push.sent user={} type={} ticket={}",
                    LogMasker.email(payload.user().getEmail()), payload.type(), result.ticketId());
            return DispatchResult.ok();
        }
        log.warn("notification.push.failed user={} type={} code={} message='{}'",
                LogMasker.email(payload.user().getEmail()), payload.type(),
                result.errorCode(), result.errorMessage());
        return DispatchResult.failed("Expo " + result.errorCode()
                + (result.errorMessage() != null ? ": " + result.errorMessage() : ""));
    }

    private Map<String, String> buildData(NotificationPayload payload) {
        var data = new HashMap<String, String>();
        data.put("type", payload.type().name());
        if (payload.extras() == null) return data;
        for (var entry : payload.extras().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            data.put(entry.getKey(), entry.getValue().toString());
        }
        return data;
    }
}
