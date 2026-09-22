package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationDestination;
import com.relyon.economizaai.model.enums.NotificationType;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One notification as the user sees it. {@code payload} is the same JSON
 * blob that was put on the wire when the notification was generated —
 * carries extras like {@code receiptId}, {@code productId}, {@code savingsPct}
 * so the FE can deep-link from the notification card. {@code destination}
 * is derived from the type and tells the FE which screen to route to.
 */
public record NotificationResponse(
        UUID id,
        NotificationType type,
        NotificationDestination destination,
        NotificationChannel channel,
        String title,
        String body,
        String payload,
        boolean delivered,
        LocalDateTime deliveredAt,
        LocalDateTime readAt,
        LocalDateTime createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getType(),
                n.getType().destination(),
                n.getChannel(),
                n.getTitle(),
                n.getBody(),
                n.getPayload(),
                n.isDelivered(),
                n.getDeliveredAt(),
                n.getReadAt(),
                n.getCreatedAt()
        );
    }
}
