package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.exception.PaidApiQuotaExceededException;
import com.relyon.economizaai.model.User;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.model.enums.PaidApiService;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageException;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmsDispatcherTest {

    @Mock private TwilioMessageClient twilioMessageClient;
    @Mock private PaidApiGuardService paidApiGuardService;

    @InjectMocks private SmsDispatcher smsDispatcher;

    private NotificationPayload payload(boolean verified, String phone) {
        var user = User.builder()
                .id(UUID.randomUUID())
                .email("maria@example.com")
                .phoneNumber(phone)
                .phoneVerified(verified)
                .build();
        return new NotificationPayload(user, NotificationType.PROMO_PERSONAL, "Title", "Body text", Map.of());
    }

    @Test
    void channelIsSms() {
        assertEquals(NotificationChannel.SMS, smsDispatcher.channel());
    }

    @Test
    void dispatchSendsAndReportsSuccessWhenConfiguredAndVerified() {
        when(twilioMessageClient.isConfigured(false)).thenReturn(true);

        var result = smsDispatcher.dispatch(payload(true, "+5551999999999"));

        assertTrue(result.delivered());
        assertNull(result.failureReason());
        verify(twilioMessageClient).sendSms("+5551999999999", "Body text");
        verify(paidApiGuardService).recordSuccess(any(), eq(PaidApiService.TWILIO_MESSAGE), eq(null), eq("twilio"));
    }

    @Test
    void dispatchFailsGracefullyWhenTwilioQuotaExceeded() {
        when(twilioMessageClient.isConfigured(false)).thenReturn(true);
        doThrow(new PaidApiQuotaExceededException(PaidApiService.TWILIO_MESSAGE.name()))
                .when(paidApiGuardService).assertWithinDailyCap(any(), eq(PaidApiService.TWILIO_MESSAGE));

        var result = smsDispatcher.dispatch(payload(true, "+5551999999999"));

        assertFalse(result.delivered());
        assertEquals("twilio_quota_exceeded", result.failureReason());
        verify(twilioMessageClient, never()).sendSms(anyString(), anyString());
    }

    @Test
    void dispatchFailsGracefullyWhenTwilioNotConfigured() {
        when(twilioMessageClient.isConfigured(false)).thenReturn(false);

        var result = smsDispatcher.dispatch(payload(true, "+5551999999999"));

        assertFalse(result.delivered());
        assertEquals("twilio_not_configured", result.failureReason());
        verify(twilioMessageClient, never()).sendSms(anyString(), anyString());
    }

    @Test
    void dispatchFailsGracefullyWhenPhoneNotVerified() {
        when(twilioMessageClient.isConfigured(false)).thenReturn(true);

        var result = smsDispatcher.dispatch(payload(false, "+5551999999999"));

        assertFalse(result.delivered());
        assertEquals("phone_not_verified", result.failureReason());
        verify(twilioMessageClient, never()).sendSms(anyString(), anyString());
    }

    @Test
    void dispatchFailsGracefullyWhenPhoneMissing() {
        when(twilioMessageClient.isConfigured(false)).thenReturn(true);

        var result = smsDispatcher.dispatch(payload(true, null));

        assertFalse(result.delivered());
        assertEquals("phone_not_verified", result.failureReason());
    }

    @Test
    void dispatchReportsFailureWithoutThrowingWhenTwilioErrors() {
        when(twilioMessageClient.isConfigured(false)).thenReturn(true);
        doThrow(new TwilioMessageException("Twilio send failed: boom", new RuntimeException("boom")))
                .when(twilioMessageClient).sendSms(eq("+5551999999999"), anyString());

        var result = smsDispatcher.dispatch(payload(true, "+5551999999999"));

        assertFalse(result.delivered());
        assertTrue(result.failureReason().contains("Twilio send failed"));
    }
}
