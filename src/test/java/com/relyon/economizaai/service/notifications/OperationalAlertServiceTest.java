package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.service.ContactService;
import com.relyon.economizaai.service.notifications.twilio.TwilioMessageClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationalAlertServiceTest {

    private final ContactService contactService = mock(ContactService.class);
    private final TwilioMessageClient twilioClient = mock(TwilioMessageClient.class);

    private OperationalAlertService service(String phones, String templateSid, boolean enabled) {
        return new OperationalAlertService(contactService, twilioClient, phones, templateSid, enabled);
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
}
