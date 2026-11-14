package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationNotice;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class WhatsAppChannelPolicyTest {

    @Mock private TwilioMessageClient twilioMessageClient;

    @InjectMocks private WhatsAppChannelPolicy policy;

    private static User user(Role role, boolean optedIn, String whatsAppNumber) {
        return User.builder().id(UUID.randomUUID()).email("u@e").role(role)
                .whatsAppOptIn(optedIn).whatsAppNumber(whatsAppNumber).build();
    }

    private void twilioWhatsAppSender(boolean configured) {
        lenient().when(twilioMessageClient.isConfigured(true)).thenReturn(configured);
    }

    @Test
    void availableOnlyForAdminsWhileTheSenderIsConfigured() {
        twilioWhatsAppSender(true);

        assertTrue(policy.isAvailableFor(user(Role.ADMIN, false, null)));
        assertFalse(policy.isAvailableFor(user(Role.USER, true, "+5551999990000")));
        assertFalse(policy.isAvailableFor(user(Role.MERCHANT, true, "+5551999990000")));
    }

    @Test
    void notAvailableForAnAdminWithoutTheSender() {
        twilioWhatsAppSender(false);

        assertFalse(policy.isAvailableFor(user(Role.ADMIN, true, "+5551999990000")));
    }

    @Test
    void connectedNeedsTheOptInAndTheNumber() {
        assertTrue(policy.isConnected(user(Role.USER, true, "+5551999990000")));
        assertFalse(policy.isConnected(user(Role.USER, false, "+5551999990000")));
        assertFalse(policy.isConnected(user(Role.USER, true, null)));
        assertFalse(policy.isConnected(user(Role.USER, true, " ")));
    }

    @Test
    void usableNeedsBothAvailableAndConnected() {
        twilioWhatsAppSender(true);

        assertTrue(policy.isUsableBy(user(Role.ADMIN, true, "+5551999990000")));
        assertFalse(policy.isUsableBy(user(Role.ADMIN, false, null)));
        assertFalse(policy.isUsableBy(user(Role.USER, true, "+5551999990000")));
    }

    @Test
    void templateRegistryKnowsOnlyTheConfiguredNotices() {
        var registry = new WhatsAppTemplateRegistry(" HXprice ", "", "HXreport");

        assertEquals(Optional.of("HXprice"), registry.templateSidFor(NotificationNotice.PRICE_ALERTS));
        assertEquals(Optional.empty(), registry.templateSidFor(NotificationNotice.DEALS_DIGEST));
        assertEquals(Optional.of("HXreport"), registry.templateSidFor(NotificationNotice.WEEKLY_REPORT));
        assertEquals(Optional.empty(), registry.templateSidFor(NotificationNotice.STOCKOUT));
        assertEquals(Optional.empty(),
                new WhatsAppTemplateRegistry(null, null, null).templateSidFor(NotificationNotice.PRICE_ALERTS));
    }
}
