package com.relyon.economizaai.model.enums;

public enum NotificationChannel {
    PUSH,      // mobile push (Expo -> FCM/APNs via PushDispatcher)
    EMAIL,     // SMTP via EmailDispatcher (active once SMTP creds are wired)
    ALEXA,     // Amazon Alexa proactive events (structure only — not yet functional)
    SMS,       // SMS via a future Twilio/Zenvia integration (structure only — not yet functional)
    WHATSAPP,  // WhatsApp Cloud API (structure only — not yet functional)
    NONE       // user opted out of this notification type
}
