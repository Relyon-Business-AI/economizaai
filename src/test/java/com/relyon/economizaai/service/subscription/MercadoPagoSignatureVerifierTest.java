package com.relyon.economizaai.service.subscription;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MercadoPagoSignatureVerifierTest {

    private static final String SECRET = "test-webhook-secret";

    private MercadoPagoSignatureVerifier verifierWithSecret(String secret) {
        var properties = new MercadoPagoProperties();
        properties.setWebhookSecret(secret);
        return new MercadoPagoSignatureVerifier(properties);
    }

    private String sign(String manifest) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsAValidSignature() throws Exception {
        var hmac = sign("id:pre_1;request-id:req-9;ts:1700000000;");

        assertTrue(verifierWithSecret(SECRET)
                .isValid("ts=1700000000,v1=" + hmac, "req-9", "pre_1"));
    }

    @Test
    void lowercasesTheDataIdInTheManifest() throws Exception {
        var hmac = sign("id:pre_abc;request-id:req-9;ts:1700000000;");

        assertTrue(verifierWithSecret(SECRET)
                .isValid("ts=1700000000,v1=" + hmac, "req-9", "PRE_ABC"));
    }

    @Test
    void rejectsAWrongHmac() {
        assertFalse(verifierWithSecret(SECRET)
                .isValid("ts=1700000000,v1=deadbeef", "req-9", "pre_1"));
    }

    @Test
    void rejectsWhenSecretIsBlankEvenWithAHeader() {
        assertFalse(verifierWithSecret("")
                .isValid("ts=1700000000,v1=deadbeef", "req-9", "pre_1"));
    }

    @Test
    void rejectsAMissingOrMalformedHeader() {
        var verifier = verifierWithSecret(SECRET);

        assertFalse(verifier.isValid(null, "req-9", "pre_1"));
        assertFalse(verifier.isValid("garbage", "req-9", "pre_1"));
        assertFalse(verifier.isValid("ts=1700000000", "req-9", "pre_1"));
    }
}
