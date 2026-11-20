package com.relyon.economizaai.service.notifications;

import org.springframework.beans.factory.annotation.Value;
import lombok.Setter;
import lombok.Getter;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who may receive notices on WhatsApp. The channel exists for ADMIN accounts only until the
 * audience setting opens it to everyone, and only while the Twilio WhatsApp sender is configured. Everyone else never sees the option and is never sent
 * anything, whatever is stored.
 */
@Component
@RequiredArgsConstructor
public class WhatsAppChannelPolicy {

    /** Who the channel exists for. */
    public enum Audience { ADMINS, EVERYONE }

    private final TwilioMessageClient twilioMessageClient;

    @Getter @Setter
    @Value("${economizaai.notifications.whatsapp.audience:ADMINS}")
    private volatile Audience audience = Audience.ADMINS;

    /** The channel exists for this user (listed in the settings, may be switched on). */
    public boolean isAvailableFor(User user) {
        return isInAudience(user) && twilioMessageClient.isConfigured(true);
    }

    private boolean isInAudience(User user) {
        return audience == Audience.EVERYONE || user.getRole() == Role.ADMIN;
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
