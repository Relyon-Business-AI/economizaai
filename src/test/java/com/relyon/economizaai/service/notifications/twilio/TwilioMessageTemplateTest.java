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
    void sendWhatsAppWithMedia_prefixesAndPassesBodyAndMediaUrl() {
        var captured = new String[4];
        var client = new TwilioMessageClient(RestClient.builder(), "AC123", "token", "", "+14063448189") {
            @Override
            protected void postMedia(String from, String to, String body, String mediaUrl) {
                captured[0] = from;
                captured[1] = to;
                captured[2] = body;
                captured[3] = mediaUrl;
            }
        };

        client.sendWhatsApp("+5551999999999", "legenda", "https://api.economizaai.app/x.png");

        assertEquals("whatsapp:+14063448189", captured[0]);
        assertEquals("whatsapp:+5551999999999", captured[1]);
        assertEquals("legenda", captured[2]);
        assertEquals("https://api.economizaai.app/x.png", captured[3]);
    }

    @Test
    void sendWhatsAppWithBlankMedia_fallsBackToPlainTextSend() {
        var mediaCalled = new boolean[1];
        var textBody = new String[1];
        var client = new TwilioMessageClient(RestClient.builder(), "AC123", "token", "", "+14063448189") {
            @Override
            protected void postMedia(String from, String to, String body, String mediaUrl) {
                mediaCalled[0] = true;
            }

            @Override
            protected void post(String from, String to, String body) {
                textBody[0] = body;
            }
        };

        client.sendWhatsApp("+5551999999999", "só texto", null);

        assertEquals(false, mediaCalled[0], "blank media must not hit the media path");
        assertEquals("só texto", textBody[0]);
    }

    @Test
    void fetchWhatsAppTemplateStatus_unknownWhenUnconfiguredOrBlankSid() {
        var unconfigured = new TwilioMessageClient(RestClient.builder(), "", "", "", "");
        assertEquals("unknown", unconfigured.fetchWhatsAppTemplateStatus("HX123"));

        var configured = new TwilioMessageClient(RestClient.builder(), "AC123", "token", "", "+14063448189");
        assertEquals("unknown", configured.fetchWhatsAppTemplateStatus(""));
    }

    @Test
    void isConfigured_trueForWhatsAppWhenCredsAndFromSet() {
        var client = new TwilioMessageClient(RestClient.builder(), "AC123", "token", "", "+14063448189");
        assertEquals(true, client.isConfigured(true));
        assertEquals(false, client.isConfigured(false), "no SMS From → SMS not configured");
    }
}
