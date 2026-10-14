package com.relyon.economizaai.service.notifications;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Mints and verifies short-lived, HMAC-signed tokens that authorize fetching ONE user's
 * report chart over an unauthenticated public URL (Twilio fetches a {@code MediaUrl} with
 * no credentials). The token carries {@code userId:expiryEpochSecond}, signed with the
 * app secret so it can't be forged, and expires fast ({@link #TTL_SECONDS}) so a leaked
 * link stops working. No DB state — the chart is regenerated on fetch.
 *
 * <p>Format: {@code base64url(payload) + "~" + base64url(hmacSha256(payload))}. The
 * {@code ~} separator stays out of the base64url alphabet and is URL/path-safe, so the
 * token drops cleanly into a {@code /{token}.png} path.
 */
@Slf4j
@Service
public class ReportChartTokenService {

    static final long TTL_SECONDS = 900; // 15 min — well beyond Twilio's fetch latency
    private static final String SEPARATOR = "~";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;

    public ReportChartTokenService(@Value("${jwt.secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String mint(UUID userId) {
        var payload = userId + ":" + (Instant.now().getEpochSecond() + TTL_SECONDS);
        var payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(payloadBytes) + SEPARATOR + ENCODER.encodeToString(sign(payloadBytes));
    }

    /** Returns the userId when the token is well-formed, signature-valid and unexpired; else empty. */
    public Optional<UUID> verify(String token) {
        try {
            var parts = token.split(SEPARATOR);
            if (parts.length != 2) return Optional.empty();
            var payloadBytes = DECODER.decode(parts[0]);
            var providedSig = DECODER.decode(parts[1]);
            if (!MessageDigest.isEqual(sign(payloadBytes), providedSig)) return Optional.empty();

            var payload = new String(payloadBytes, StandardCharsets.UTF_8);
            var sep = payload.lastIndexOf(':');
            if (sep < 0) return Optional.empty();
            var expiry = Long.parseLong(payload.substring(sep + 1));
            if (Instant.now().getEpochSecond() > expiry) return Optional.empty();
            return Optional.of(UUID.fromString(payload.substring(0, sep)));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private byte[] sign(byte[] data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC signing failed", ex);
        }
    }
}
