package com.relyon.economizaai.service.notifications.twilio;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the WhatsApp template send path: the {@code whatsapp:} prefixing of From/To and
 * the ContentSid/ContentVariables pass-through. The HTTP exchange is stubbed via the
 * {@code postTemplate} seam, so no network call happens.
 */
class TwilioMessageTemplateTest {

    @Test
    void sendWhatsAppTemplate_prefixesWhatsAppAndForwardsContent() {
        var captured = new String[4];
        var client = new TwilioMessageClient(RestClient.builder(), "AC123", "token", "", "+14063448189") {
            @Override
            protected void postTemplate(String from, String to, String contentSid, String contentVariablesJson) {
                captured[0] = from;
                captured[1] = to;
                captured[2] = contentSid;
                captured[3] = contentVariablesJson;
            }
        };

        client.sendWhatsAppTemplate("+5551999999999", "HX123", "{\"1\":\"saldo baixo\"}");

        assertEquals("whatsapp:+14063448189", captured[0], "From gets the whatsapp: prefix");
        assertEquals("whatsapp:+5551999999999", captured[1], "To gets the whatsapp: prefix");
        assertEquals("HX123", captured[2]);
        assertEquals("{\"1\":\"saldo baixo\"}", captured[3]);
    }

    @Test
    void isConfigured_trueForWhatsAppWhenCredsAndFromSet() {
        var client = new TwilioMessageClient(RestClient.builder(), "AC123", "token", "", "+14063448189");
        assertEquals(true, client.isConfigured(true));
        assertEquals(false, client.isConfigured(false), "no SMS From → SMS not configured");
    }
}
