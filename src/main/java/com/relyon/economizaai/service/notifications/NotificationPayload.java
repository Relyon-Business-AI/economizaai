package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;

import java.util.Map;
import java.util.UUID;

/**
 * What a service layer hands to NotificationService when something
 * worth telling the user about happens. Channel-agnostic — the
 * service decides how (email, push) based on user preference.
 *
 * <p>{@code campaignId} ties the resulting outbox row to the admin campaign
 * that produced it (null for organic notifications).
 *
 * <p>{@code body} is the canonical text stored on the inbox row and shown inside the app
 * (the notification detail screen). {@code pushBody} is an OPTIONAL variant used only for the
 * mobile push, where a call to action like "abra o app pra tentar de novo" makes sense but
 * would read wrong in the in-app detail. When {@code pushBody} is null the push falls back to
 * {@code body}, so every existing caller keeps a single text for both.
 */
public record NotificationPayload(
        User user,
        NotificationType type,
        String title,
        String body,
        Map<String, Object> extras,
        UUID campaignId,
        String pushBody
) {
    public NotificationPayload(User user, NotificationType type, String title, String body,
                               Map<String, Object> extras, UUID campaignId) {
        this(user, type, title, body, extras, campaignId, null);
    }

    public NotificationPayload(User user, NotificationType type, String title, String body,
                               Map<String, Object> extras) {
        this(user, type, title, body, extras, null, null);
    }

    /** Text for the mobile push — the dedicated {@code pushBody} when set, else the inbox body. */
    public String effectivePushBody() {
        return pushBody != null && !pushBody.isBlank() ? pushBody : body;
    }
}
