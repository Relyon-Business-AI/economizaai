package com.relyon.economizaai.service.profile;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Validates the hand-rolled AWS SigV4 signing against a fixed vector whose
 * expected Authorization was produced by an independent reference
 * implementation and verified end-to-end against the real R2 bucket
 * (PUT/GET/DELETE round-trip). Guards against a regression in the canonical
 * request / signing-key chain, which a mismatched signature would turn into
 * silent 403s from R2.
 */
class CloudflareR2ProfilePictureStorageTest {

    private static final String PAYLOAD_HASH_HELLO =
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @Test
    void signsRequestMatchingKnownSigV4Vector() {
        var storage = new CloudflareR2ProfilePictureStorage(
                RestClient.builder(),
                "https://testacct.r2.cloudflarestorage.com",
                "test-bucket", "AKIATEST", "SECRETTEST", "auto");

        Map<String, String> headers = storage.signedHeaders(
                "PUT", "/test-bucket/abc.jpg", PAYLOAD_HASH_HELLO,
                Instant.parse("2026-01-02T03:04:05Z"));

        assertEquals("20260102T030405Z", headers.get("x-amz-date"));
        assertEquals(PAYLOAD_HASH_HELLO, headers.get("x-amz-content-sha256"));
        assertEquals(
                "AWS4-HMAC-SHA256 Credential=AKIATEST/20260102/auto/s3/aws4_request, "
                        + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=a7cad49c0bee68b5c3d066caf6f976027b5137ee74032582ca88c4ba63e4a35f",
                headers.get("Authorization"));
    }

    @Test
    void trailingSlashInEndpointIsNormalized() {
        var storage = new CloudflareR2ProfilePictureStorage(
                RestClient.builder(),
                "https://testacct.r2.cloudflarestorage.com/",
                "test-bucket", "AKIATEST", "SECRETTEST", "auto");
        // host is still parsed correctly, so the signature matches the no-slash case
        Map<String, String> headers = storage.signedHeaders(
                "PUT", "/test-bucket/abc.jpg", PAYLOAD_HASH_HELLO,
                Instant.parse("2026-01-02T03:04:05Z"));
        assertEquals(
                "AWS4-HMAC-SHA256 Credential=AKIATEST/20260102/auto/s3/aws4_request, "
                        + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                        + "Signature=a7cad49c0bee68b5c3d066caf6f976027b5137ee74032582ca88c4ba63e4a35f",
                headers.get("Authorization"));
    }
}
