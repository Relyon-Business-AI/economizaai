package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who may receive notices on WhatsApp. For now the channel exists for ADMIN accounts only
 * (the owner is testing it; it becomes a paid-tier channel later) and only while the Twilio
 * WhatsApp sender is configured. Everyone else never sees the option and is never sent
 * anything, whatever is stored.
 */
@Component
@RequiredArgsConstructor
public class WhatsAppChannelPolicy {

    private final TwilioMessageClient twilioMessageClient;

    /** The channel exists for this user (listed in the settings, may be switched on). */
    public boolean isAvailableFor(User user) {
        return user.getRole() == Role.ADMIN && twilioMessageClient.isConfigured(true);
    }

    /** The user completed the reverse opt-in: we hold the WhatsApp number they wrote to us from. */
    public boolean isConnected(User user) {
        return user.isWhatsAppOptIn() && user.getWhatsAppNumber() != null && !user.getWhatsAppNumber().isBlank();
    }

    /** Available and connected — the only state in which a WhatsApp option can be on and a leg can go out. */
    public boolean isUsableBy(User user) {
        return isAvailableFor(user) && isConnected(user);
    }
}
