package com.relyon.economizaai.service.notifications.twilio;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Twilio signature check: an independent HMAC-SHA1 over (url + sorted key+value)
 * reproduces what {@link TwilioSignatureVerifier} accepts; a tampered signature or a blank
 * (fail-closed) auth token are both rejected.
 */
class TwilioSignatureVerifierTest {

    private static final String TOKEN = "my-auth-token-12345";
    private static final String URL = "https://api.economizaai.app/api/v1/webhooks/twilio/whatsapp-inbound";

    private Map<String, String> inboundParams() {
        var params = new LinkedHashMap<String, String>();
        params.put("From", "whatsapp:+5551997513229");
        params.put("Body", "CONFIRMAR-abc123");
        params.put("MessageSid", "SM0123456789");
        return params;
    }

    /** Independent reference impl of Twilio's algorithm (url + params sorted by key, each key+value). */
    private static String reference(String token, String url, Map<String, String> params) throws Exception {
        var payload = new StringBuilder(url);
        new TreeMap<>(params).forEach((key, value) -> payload.append(key).append(value));
        var mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsSignatureMatchingTheTwilioAlgorithm() throws Exception {
        var verifier = new TwilioSignatureVerifier(TOKEN);
        var signature = reference(TOKEN, URL, inboundParams());
        assertTrue(verifier.isValid(URL, inboundParams(), signature));
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        var verifier = new TwilioSignatureVerifier(TOKEN);
        var signature = reference(TOKEN, URL, inboundParams());
        var tampered = inboundParams();
        tampered.put("Body", "CONFIRMAR-DIFFERENT"); // body changed → signature no longer matches
        assertFalse(verifier.isValid(URL, tampered, signature));
    }

    @Test
    void blankAuthTokenFailsClosed() throws Exception {
        var signature = reference(TOKEN, URL, inboundParams());
        assertFalse(new TwilioSignatureVerifier("").isValid(URL, inboundParams(), signature));
    }
}
