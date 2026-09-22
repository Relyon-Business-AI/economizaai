package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class StubChannelDispatcherTest {

    private NotificationPayload payload() {
        var user = User.builder().id(UUID.randomUUID()).email("user@example.com").build();
        return new NotificationPayload(user, NotificationType.PROMO_COMMUNITY, "Title", "Body", Map.of("k", "v"));
    }

    @Test
    void alexaDispatcher_reportsChannelAndUndeliveredStub() {
        var dispatcher = new AlexaDispatcher();

        assertEquals(NotificationChannel.ALEXA, dispatcher.channel());
        var result = dispatcher.dispatch(payload());
        assertFalse(result.delivered());
        assertNotNull(result.failureReason());
    }
}
