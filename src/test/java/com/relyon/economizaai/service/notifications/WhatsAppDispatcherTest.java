package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageException;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppDispatcherTest {

    private static final String OPTED_IN_NUMBER = "+5551999990000";
    private static final String SMS_NUMBER = "+5551888880000";
    private static final String PRICE_ALERT_TEMPLATE = "HXpricealert";

    @Mock private TwilioMessageClient twilioMessageClient;
    @Mock private PaidApiGuardService paidApiGuardService;

    private WhatsAppDispatcher dispatcher(String priceAlertTemplateSid) {
        return new WhatsAppDispatcher(twilioMessageClient, paidApiGuardService,
                new WhatsAppChannelPolicy(twilioMessageClient),
                new WhatsAppTemplateRegistry(priceAlertTemplateSid, "", ""));
    }

    private static User connectedAdmin() {
        return User.builder().id(UUID.randomUUID()).email("admin@economizaai.app").role(Role.ADMIN)
                .phoneNumber(SMS_NUMBER).phoneVerified(true)
                .whatsAppOptIn(true).whatsAppNumber(OPTED_IN_NUMBER).build();
    }

    private static NotificationPayload priceAlert(User user) {
        return new NotificationPayload(user, NotificationType.PRICE_DROP, "Title", "Arroz baixou\npara  R$ 19,90", Map.of());
    }

    @Test
    void channelIsWhatsApp() {
        assertEquals(NotificationChannel.WHATSAPP, dispatcher(PRICE_ALERT_TEMPLATE).channel());
    }

    @Test
    void sendsTheNoticeTemplateToTheOptedInNumberNotTheSmsNumber() {
        when(twilioMessageClient.isConfigured(true)).thenReturn(true);

        var result = dispatcher(PRICE_ALERT_TEMPLATE).dispatch(priceAlert(connectedAdmin()));

        assertTrue(result.delivered());
        assertNull(result.failureReason());
        verify(twilioMessageClient).sendWhatsAppTemplate(OPTED_IN_NUMBER, PRICE_ALERT_TEMPLATE,
                "{\"1\":\"Arroz baixou para R$ 19,90\"}");
        verify(twilioMessageClient, never()).sendWhatsApp(anyString(), anyString());
        verify(paidApiGuardService).recordSuccess(any(), eq(PaidApiService.TWILIO_MESSAGE), eq(null), eq("twilio"));
    }

    @Test
    void usesTheReadyOneLineTextWhenTheCallerProvidesOne() {
        when(twilioMessageClient.isConfigured(true)).thenReturn(true);
        var payload = new NotificationPayload(connectedAdmin(), NotificationType.PRICE_DROP, "Title", "Long\nbody",
                Map.of(WhatsAppDispatcher.EXTRA_ONE_LINE_TEXT, "R$ 120,00 em 3 compra(s) essa semana."));

        dispatcher(PRICE_ALERT_TEMPLATE).dispatch(payload);

        verify(twilioMessageClient).sendWhatsAppTemplate(OPTED_IN_NUMBER, PRICE_ALERT_TEMPLATE,
                "{\"1\":\"R$ 120,00 em 3 compra(s) essa semana.\"}");
    }

    @Test
    void blankTemplateSidSkipsTheLegWithoutSendingAnything() {
        when(twilioMessageClient.isConfigured(true)).thenReturn(true);

        var result = dispatcher("  ").dispatch(priceAlert(connectedAdmin()));

        assertFalse(result.delivered());
        assertEquals("template_not_configured", result.failureReason());
        verify(twilioMessageClient, never()).sendWhatsAppTemplate(anyString(), anyString(), anyString());
        verify(twilioMessageClient, never()).sendWhatsApp(anyString(), anyString());
        verifyNoInteractions(paidApiGuardService);
    }

    @Test
    void noticeWithoutAWhatsAppTemplateIsSkipped() {
        when(twilioMessageClient.isConfigured(true)).thenReturn(true);
        var payload = new NotificationPayload(connectedAdmin(), NotificationType.STOCKOUT, "Title", "Body", Map.of());

        var result = dispatcher(PRICE_ALERT_TEMPLATE).dispatch(payload);

        assertEquals("template_not_configured", result.failureReason());
        verify(twilioMessageClient, never()).sendWhatsAppTemplate(anyString(), anyString(), anyString());
    }

    @Test
    void nonAdminIsNeverSentAnything() {
        when(twilioMessageClient.isConfigured(true)).thenReturn(true);
        var regularUser = connectedAdmin();
        regularUser.setRole(Role.USER);

        var result = dispatcher(PRICE_ALERT_TEMPLATE).dispatch(priceAlert(regularUser));

        assertFalse(result.delivered());
        assertEquals("whatsapp_not_available", result.failureReason());
        verify(twilioMessageClient, never()).sendWhatsAppTemplate(anyString(), anyString(), anyString());
    }

    @Test
    void adminWhoDidNotOptInIsSkippedEvenWithAVerifiedSmsNumber() {
        when(twilioMessageClient.isConfigured(true)).thenReturn(true);
        var adminWithoutOptIn = connectedAdmin();
        adminWithoutOptIn.setWhatsAppOptIn(false);

        var result = dispatcher(PRICE_ALERT_TEMPLATE).dispatch(priceAlert(adminWithoutOptIn));

        assertEquals("whatsapp_not_opted_in", result.failureReason());
        verify(twilioMessageClient, never()).sendWhatsAppTemplate(anyString(), anyString(), anyString());
    }

    @Test
    void failsGracefullyWhenTwilioNotConfigured() {
        when(twilioMessageClient.isConfigured(true)).thenReturn(false);

        var result = dispatcher(PRICE_ALERT_TEMPLATE).dispatch(priceAlert(connectedAdmin()));

        assertFalse(result.delivered());
        assertEquals("twilio_not_configured", result.failureReason());
        verify(twilioMessageClient, never()).sendWhatsAppTemplate(anyString(), anyString(), anyString());
    }

    @Test
    void failsGracefullyWhenTwilioQuotaExceeded() {
        when(twilioMessageClient.isConfigured(true)).thenReturn(true);
        doThrow(new PaidApiQuotaExceededException(PaidApiService.TWILIO_MESSAGE.name()))
                .when(paidApiGuardService).assertWithinDailyCap(any(), eq(PaidApiService.TWILIO_MESSAGE));

        var result = dispatcher(PRICE_ALERT_TEMPLATE).dispatch(priceAlert(connectedAdmin()));

        assertFalse(result.delivered());
        assertEquals("twilio_quota_exceeded", result.failureReason());
        verify(twilioMessageClient, never()).sendWhatsAppTemplate(anyString(), anyString(), anyString());
    }

    @Test
    void reportsFailureWithoutThrowingWhenTwilioErrors() {
        when(twilioMessageClient.isConfigured(true)).thenReturn(true);
        doThrow(new TwilioMessageException("Twilio send failed: boom", new RuntimeException("boom")))
                .when(twilioMessageClient).sendWhatsAppTemplate(eq(OPTED_IN_NUMBER), anyString(), anyString());

        var result = dispatcher(PRICE_ALERT_TEMPLATE).dispatch(priceAlert(connectedAdmin()));

        assertFalse(result.delivered());
        assertTrue(result.failureReason().contains("Twilio send failed"));
        verify(paidApiGuardService).recordFailure(any(), eq(PaidApiService.TWILIO_MESSAGE), eq(null), eq("twilio"));
    }

    @Test
    void oneLineTextCollapsesWhitespaceAndCapsTheLength() {
        var longBody = "linha\tum\n\n" + "x".repeat(2000);
        var payload = new NotificationPayload(connectedAdmin(), NotificationType.PRICE_DROP, "Title", longBody, Map.of());

        var oneLine = WhatsAppDispatcher.oneLineText(payload);

        assertTrue(oneLine.startsWith("linha um x"));
        assertEquals(900, oneLine.length());
        assertEquals("", WhatsAppDispatcher.oneLineText(
                new NotificationPayload(connectedAdmin(), NotificationType.PRICE_DROP, "Title", null, null)));
    }
}
