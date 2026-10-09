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
 */
public record NotificationPayload(
        User user,
        NotificationType type,
        String title,
        String body,
        Map<String, Object> extras,
        UUID campaignId
) {
    public NotificationPayload(User user, NotificationType type, String title, String body,
                               Map<String, Object> extras) {
        this(user, type, title, body, extras, null);
    }
}
