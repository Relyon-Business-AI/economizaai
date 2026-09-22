package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.enums.NotificationChannel;
import org.springframework.stereotype.Component;

/**
 * Amazon Alexa channel — structure only. A real implementation would push an
 * Alexa Proactive Event (AMAZON.MessageAlert / custom schema) via the Alexa
 * Skill Messaging API using a stored skill grant per user. Not yet functional.
 */
@Component
public class AlexaDispatcher extends StubChannelDispatcher {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.ALEXA;
    }
}
