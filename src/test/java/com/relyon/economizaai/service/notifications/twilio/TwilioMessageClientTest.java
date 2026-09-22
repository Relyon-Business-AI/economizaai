package com.relyon.economizaai.service.notifications.twilio;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwilioMessageClientTest {

    private static final String SID = "ACxxxxxxxx";
    private static final String AUTH = "secret-token";
    private static final String FROM_SMS = "+15551234567";
    private static final String FROM_WA = "+14155238886";

    /** Records the post() args instead of hitting the network. */
    private static class RecordingClient extends TwilioMessageClient {
        final List<String[]> calls = new ArrayList<>();
        final boolean fail;

        RecordingClient(String sid, String auth, String fromSms, String fromWa, boolean fail) {
            super(RestClient.builder(), sid, auth, fromSms, fromWa);
            this.fail = fail;
        }

        @Override
        protected void post(String from, String to, String body) {
            calls.add(new String[]{from, to, body});
            if (fail) throw new RuntimeException("boom");
        }
    }

    private RecordingClient client(boolean fail) {
        return new RecordingClient(SID, AUTH, FROM_SMS, FROM_WA, fail);
    }

    @Test
    void isConfigured_trueOnlyWhenCredentialsAndFromPresent() {
        assertTrue(client(false).isConfigured(false));
        assertTrue(client(false).isConfigured(true));
    }

    @Test
    void isConfigured_falseWhenSidMissing() {
        var noSid = new RecordingClient("", AUTH, FROM_SMS, FROM_WA, false);
        assertFalse(noSid.isConfigured(false));
        assertFalse(noSid.isConfigured(true));
    }

    @Test
    void isConfigured_falseWhenAuthTokenMissing() {
        var noAuth = new RecordingClient(SID, " ", FROM_SMS, FROM_WA, false);
        assertFalse(noAuth.isConfigured(false));
    }

    @Test
    void isConfigured_smsFalseWhenSmsFromMissing() {
        var noSmsFrom = new RecordingClient(SID, AUTH, "", FROM_WA, false);
        assertFalse(noSmsFrom.isConfigured(false));
        assertTrue(noSmsFrom.isConfigured(true));
    }

    @Test
    void isConfigured_whatsAppFalseWhenWhatsAppFromMissing() {
        var noWaFrom = new RecordingClient(SID, AUTH, FROM_SMS, "", false);
        assertTrue(noWaFrom.isConfigured(false));
        assertFalse(noWaFrom.isConfigured(true));
    }

    @Test
    void sendSms_usesPlainFromAndTo() {
        var recording = client(false);

        recording.sendSms("+5551999999999", "hello");

        assertEquals(1, recording.calls.size());
        var call = recording.calls.get(0);
        assertEquals(FROM_SMS, call[0]);
        assertEquals("+5551999999999", call[1]);
        assertEquals("hello", call[2]);
    }

    @Test
    void sendWhatsApp_prefixesBothFromAndTo() {
        var recording = client(false);

        recording.sendWhatsApp("+5551999999999", "oi");

        var call = recording.calls.get(0);
        assertEquals("whatsapp:" + FROM_WA, call[0]);
        assertEquals("whatsapp:+5551999999999", call[1]);
        assertEquals("oi", call[2]);
    }

    @Test
    void sendSms_wrapsHttpErrorInTwilioMessageException() {
        var failing = client(true);

        var ex = assertThrows(TwilioMessageException.class,
                () -> failing.sendSms("+5551999999999", "hello"));
        assertTrue(ex.getMessage().contains("Twilio send failed"));
    }

    @Test
    void sendWhatsApp_wrapsHttpErrorInTwilioMessageException() {
        var failing = client(true);

        assertThrows(TwilioMessageException.class,
                () -> failing.sendWhatsApp("+5551999999999", "oi"));
    }
}
