package com.relyon.economizai.dto.response;

import com.relyon.economizai.model.Notification;
import com.relyon.economizai.model.enums.NotificationChannel;
import com.relyon.economizai.model.enums.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Admin-facing snapshot of a SENT notification — powers the "notificações
 * enviadas" list. Shows who got what, when, whether it was delivered and read.
 */
public record AdminNotificationSummaryResponse(
        UUID id,
        String userEmail,
        NotificationType type,
        NotificationChannel channel,
        String title,
        String body,
        boolean delivered,
        LocalDateTime deliveredAt,
        String failureReason,
        boolean read,
        LocalDateTime createdAt) {

    public static AdminNotificationSummaryResponse from(Notification notification) {
        return new AdminNotificationSummaryResponse(
                notification.getId(),
                notification.getUser() == null ? null : notification.getUser().getEmail(),
                notification.getType(),
                notification.getChannel(),
                notification.getTitle(),
                notification.getBody(),
                notification.isDelivered(),
                notification.getDeliveredAt(),
                notification.getFailureReason(),
                notification.getReadAt() != null,
                notification.getCreatedAt());
    }
}
