package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.Notification;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The inbox timestamps are stored as UTC wall-clock but DISPLAYED by the FE —
 * the mapper must hand them over in Brasília, or a fresh notification renders
 * as "3h atrás".
 */
class NotificationResponseTest {

    private Notification notification() {
        var notification = Notification.builder()
                .type(NotificationType.SYSTEM)
                .channel(NotificationChannel.PUSH)
                .title("Importação concluída")
                .body("Sua importação terminou")
                .delivered(true)
                .build();
        notification.setCreatedAt(LocalDateTime.of(2026, 9, 30, 12, 0, 0)); // UTC storage basis
        return notification;
    }

    @Test
    void from_convertsUtcStoredTimestampsToBrasilia() {
        var stored = notification();
        stored.setDeliveredAt(LocalDateTime.of(2026, 9, 30, 12, 1, 0));
        stored.setReadAt(LocalDateTime.of(2026, 9, 30, 12, 5, 0));

        var response = NotificationResponse.from(stored);

        assertEquals(LocalDateTime.of(2026, 9, 30, 9, 0, 0), response.createdAt());
        assertEquals(LocalDateTime.of(2026, 9, 30, 9, 1, 0), response.deliveredAt());
        assertEquals(LocalDateTime.of(2026, 9, 30, 9, 5, 0), response.readAt());
    }

    @Test
    void from_keepsUnreadTimestampsNull() {
        var response = NotificationResponse.from(notification());

        assertEquals(LocalDateTime.of(2026, 9, 30, 9, 0, 0), response.createdAt());
        assertNull(response.deliveredAt());
        assertNull(response.readAt());
    }
}
