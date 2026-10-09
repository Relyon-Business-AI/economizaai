package com.relyon.economizaai.service.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * End-to-end round-trip against the REAL R2 bucket. Only runs when R2 creds
 * are present in the environment (local/manual), never in CI. Validates the
 * full RestClient + SigV4 path against Cloudflare, not just the signature math.
 */
@EnabledIfEnvironmentVariable(named = "R2_ACCESS_KEY_ID", matches = ".+")
class CloudflareR2IntegrationTest {

    @Test
    void storeReadDeleteRoundTrip() throws IOException {
        var storage = new CloudflareR2ProfilePictureStorage(
                RestClient.builder(),
                System.getenv("R2_ENDPOINT"),
                System.getenv("R2_BUCKET"),
                System.getenv("R2_ACCESS_KEY_ID"),
                System.getenv("R2_SECRET_ACCESS_KEY"),
                "auto");

        var data = "java-r2-roundtrip".getBytes();
        var key = storage.store(new ByteArrayInputStream(data), "image/jpeg", data.length);

        assertArrayEquals(data, storage.read(key), "read must return the stored bytes");

        storage.delete(key);
        assertNull(storage.read(key), "read after delete must be null (404)");
    }
}
