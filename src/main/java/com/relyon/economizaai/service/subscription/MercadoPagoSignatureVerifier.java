package com.relyon.economizaai.service.subscription;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Validates Mercado Pago's {@code x-signature} header: {@code ts=<ts>,v1=<hmac>}
 * where the HMAC-SHA256 (hex, keyed by the webhook secret) covers the manifest
 * {@code id:<data.id>;request-id:<x-request-id>;ts:<ts>;} — alphanumeric ids
 * lowercased, absent parts omitted, per MP's webhook docs. Blank secret =
 * everything rejected (fail-closed).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MercadoPagoSignatureVerifier {

    private final MercadoPagoProperties properties;

    public boolean isValid(String xSignature, String xRequestId, String dataId) {
        if (!properties.isWebhookConfigured()) {
            log.warn("mercadopago.webhook.auth not_configured rejecting");
            return false;
        }
        var secret = properties.getWebhookSecret();
        if (xSignature == null || xSignature.isBlank()) {
            return false;
        }
        String ts = null, providedHmac = null;
        for (var part : xSignature.split(",")) {
            var keyValue = part.trim().split("=", 2);
            if (keyValue.length != 2) continue;
            if (keyValue[0].trim().equals("ts")) ts = keyValue[1].trim();
            if (keyValue[0].trim().equals("v1")) providedHmac = keyValue[1].trim();
        }
        if (ts == null || providedHmac == null) {
            return false;
        }
        var manifest = new StringBuilder();
        if (dataId != null && !dataId.isBlank()) {
            manifest.append("id:").append(dataId.toLowerCase(Locale.ROOT)).append(";");
        }
        if (xRequestId != null && !xRequestId.isBlank()) {
            manifest.append("request-id:").append(xRequestId).append(";");
        }
        manifest.append("ts:").append(ts).append(";");
        var expectedHmac = hmacSha256Hex(secret, manifest.toString());
        return MessageDigest.isEqual(
                expectedHmac.getBytes(StandardCharsets.UTF_8),
                providedHmac.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private String hmacSha256Hex(String secret, String payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception cryptoFailure) {
            throw new IllegalStateException("HmacSHA256 unavailable", cryptoFailure);
        }
    }
}
