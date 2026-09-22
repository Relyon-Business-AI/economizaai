package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationType;

import java.util.Map;

/**
 * What a service layer hands to NotificationService when something
 * worth telling the user about happens. Channel-agnostic — the
 * service decides how (email, push) based on user preference.
 */
public record NotificationPayload(
        User user,
        NotificationType type,
        String title,
        String body,
        Map<String, Object> extras
) {}
