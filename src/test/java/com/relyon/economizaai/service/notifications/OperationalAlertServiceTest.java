package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.service.ContactService;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageException;
import com.relyon.economizaai.service.paidapi.PaidApiGuardService;
import com.relyon.economizaai.model.enums.PaidApiService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationalAlertServiceTest {

    private final ContactService contactService = mock(ContactService.class);
    private final TwilioMessageClient twilioClient = mock(TwilioMessageClient.class);
    private final PaidApiGuardService paidApiGuardService = mock(PaidApiGuardService.class);

    private OperationalAlertService service(String phones, String templateSid, boolean enabled) {
        return new OperationalAlertService(contactService, twilioClient, paidApiGuardService, phones, templateSid, enabled);
    }

    @Test
    void alertAdmin_sendsEmailAlwaysAndWhatsAppToEachAdminWhenConfigured() {
        when(twilioClient.isConfigured(true)).thenReturn(true);
        var alerts = service("+5551999999999,+5551888888888", "HX123", true);

        alerts.alertAdmin("Infosimples: saldo baixo", "detalhe longo no e-mail");

        verify(contactService).notifyAdmin("Infosimples: saldo baixo", "detalhe longo no e-mail");
        // headline goes in the {{1}} slot as JSON, one template send per admin number
        verify(twilioClient).sendWhatsAppTemplate("+5551999999999", "HX123", "{\"1\":\"Infosimples: saldo baixo\"}");
        verify(twilioClient).sendWhatsAppTemplate("+5551888888888", "HX123", "{\"1\":\"Infosimples: saldo baixo\"}");
    }

    @Test
    void alertAdmin_emailOnlyWhenTemplateUnset() {
        var alerts = service("+5551999999999", "", true);

        alerts.alertAdmin("algo", "corpo");

        verify(contactService).notifyAdmin("algo", "corpo");
        verify(twilioClient, never()).sendWhatsAppTemplate(anyString(), anyString(), anyString());
    }

    @Test
    void alertAdmin_emailOnlyWhenWhatsAppDisabled() {
        var alerts = service("+5551999999999", "HX123", false);

        alerts.alertAdmin("algo", "corpo");

        verify(contactService).notifyAdmin("algo", "corpo");
        verify(twilioClient, never()).sendWhatsAppTemplate(anyString(), anyString(), anyString());
    }

    @Test
    void alertAdmin_emailOnlyWhenNoAdminNumbers() {
        when(twilioClient.isConfigured(true)).thenReturn(true);
        var alerts = service("", "HX123", true);

        alerts.alertAdmin("algo", "corpo");

        verify(contactService).notifyAdmin("algo", "corpo");
        verify(twilioClient, never()).sendWhatsAppTemplate(anyString(), anyString(), anyString());
    }

    @Test
    void config_reportsChannelsAndLiveTemplateStatus() {
        when(twilioClient.isConfigured(true)).thenReturn(true);
        when(twilioClient.fetchWhatsAppTemplateStatus("HX123")).thenReturn("approved");
        var alerts = service("+5551999999999,+5551888888888", "HX123", true);

        var config = alerts.config();

        assertEquals(true, config.emailActive());
        assertEquals(true, config.whatsAppEnabled());
        assertEquals(2, config.adminPhoneCount());
        assertEquals(true, config.adminTemplateConfigured());
        assertEquals(true, config.twilioWhatsAppConfigured());
        assertEquals("approved", config.templateStatus());
    }

    @Test
    void config_templateUnsetSkipsTwilioLookupAndReportsUnset() {
        var alerts = service("+5551999999999", "", true);

        var config = alerts.config();

        assertEquals(false, config.adminTemplateConfigured());
        assertEquals("unset", config.templateStatus());
        verify(twilioClient, never()).fetchWhatsAppTemplateStatus(anyString());
    }

    @Test
    void alertAdmin_swallowsEmailAndWhatsAppFailures() {
        when(twilioClient.isConfigured(true)).thenReturn(true);
        doThrow(new RuntimeException("smtp down")).when(contactService).notifyAdmin(anyString(), anyString());
        doThrow(new RuntimeException("twilio 500")).when(twilioClient)
                .sendWhatsAppTemplate(anyString(), anyString(), anyString());
        var alerts = service("+5551999999999", "HX123", true);

        // an alert must never break the caller (ingestion/scheduler), even if both channels fail
        assertDoesNotThrow(() -> alerts.alertAdmin("algo", "corpo"));
        verify(contactService).notifyAdmin(anyString(), anyString());
        verify(twilioClient).sendWhatsAppTemplate(eq("+5551999999999"), eq("HX123"), any());
    }

    @Test
    void sendWhatsAppConfirmed_trueOnlyWhenTheProviderAcceptedIt_andIsLedgered() {
        when(twilioClient.isConfigured(true)).thenReturn(true);
        var alerts = service("+5551999999999", "HX123", true);

        assertTrue(alerts.sendWhatsAppConfirmed("teto atingido"));

        verify(twilioClient).sendWhatsAppTemplate(eq("+5551999999999"), eq("HX123"), anyString());
        verify(paidApiGuardService).recordSuccess(null, PaidApiService.TWILIO_MESSAGE, null, "twilio");
    }

    @Test
    void sendWhatsAppConfirmed_providerRejects_isFalse_andLedgeredAsFailure() {
        when(twilioClient.isConfigured(true)).thenReturn(true);
        doThrow(new TwilioMessageException("twilio 500", new RuntimeException()))
                .when(twilioClient).sendWhatsAppTemplate(anyString(), anyString(), anyString());
        var alerts = service("+5551999999999", "HX123", true);

        assertFalse(alerts.sendWhatsAppConfirmed("teto atingido"));

        verify(paidApiGuardService).recordFailure(null, PaidApiService.TWILIO_MESSAGE, null, "twilio");
    }

    @Test
    void sendWhatsAppConfirmed_oneOfTwoAdminsAccepted_countsAsDelivered() {
        when(twilioClient.isConfigured(true)).thenReturn(true);
        doThrow(new TwilioMessageException("invalid number", new RuntimeException()))
                .when(twilioClient).sendWhatsAppTemplate(eq("+5551888888888"), anyString(), anyString());
        var alerts = service("+5551999999999,+5551888888888", "HX123", true);

        assertTrue(alerts.sendWhatsAppConfirmed("teto atingido"));
    }

    @Test
    void sendWhatsAppConfirmed_channelNotConfigured_isFalseWithoutCallingTwilio() {
        when(twilioClient.isConfigured(true)).thenReturn(true);
        var alerts = service("+5551999999999", "", true);

        assertFalse(alerts.isWhatsAppReady());
        assertFalse(alerts.sendWhatsAppConfirmed("teto atingido"));
        verify(twilioClient, never()).sendWhatsAppTemplate(anyString(), anyString(), anyString());
    }

    @Test
    void adminAlerts_areNeverBlockedByTheTwilioBudgetOrPerUserCap() {
        when(twilioClient.isConfigured(true)).thenReturn(true);
        var alerts = service("+5551999999999", "HX123", true);

        alerts.sendWhatsAppConfirmed("teto do Twilio atingido");
        alerts.alertAdmin("outro alerta", "detalhe");

        verify(paidApiGuardService, never()).assertUnderDailyBudget(any());
        verify(paidApiGuardService, never()).assertWithinDailyCap(any(), any());
        verify(twilioClient, times(2)).sendWhatsAppTemplate(anyString(), anyString(), anyString());
    }

    @Test
    void sendEmailConfirmed_reflectsWhetherTheMailServerAcceptedIt() {
        var alerts = service("", "", true);
        when(contactService.notifyAdminConfirmed("assunto", "corpo")).thenReturn(true, false);

        assertTrue(alerts.sendEmailConfirmed("assunto", "corpo"));
        assertFalse(alerts.sendEmailConfirmed("assunto", "corpo"));
    }
}
