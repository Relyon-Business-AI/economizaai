package com.relyon.economizaai.service.notifications.twilio;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Validates Twilio's {@code X-Twilio-Signature}: HMAC-SHA1 (keyed by the Twilio
 * auth token) over the full request URL followed by every POST param appended as
 * {@code key + value}, params sorted by key; Base64-encoded, compared constant-time.
 * Blank auth token = everything rejected (fail-closed).
 * See https://www.twilio.com/docs/usage/security#validating-requests
 */
@Slf4j
@Service
public class TwilioSignatureVerifier {

    private final String authToken;

    public TwilioSignatureVerifier(
            @Value("${economizaai.notifications.twilio.auth-token:}") String authToken) {
        this.authToken = authToken;
    }

    public boolean isValid(String url, Map<String, String> params, String signatureHeader) {
        if (authToken == null || authToken.isBlank()) {
            log.warn("twilio.webhook.auth not_configured rejecting");
            return false;
        }
        if (url == null || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        var payload = new StringBuilder(url);
        var sorted = new TreeMap<>(params == null ? Map.<String, String>of() : params);
        for (var entry : sorted.entrySet()) {
            payload.append(entry.getKey()).append(entry.getValue() == null ? "" : entry.getValue());
        }
        var expected = hmacSha1Base64(payload.toString());
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacSha1Base64(String payload) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception cryptoFailure) {
            throw new IllegalStateException("HmacSHA1 unavailable", cryptoFailure);
        }
    }
}
