package com.relyon.economizaai.service.photoattempt;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives the storage against a local in-process HTTP server standing in for the bucket. */
class CloudflareR2PhotoAttemptStorageTest {

    private static final String RECEIPT_ID = "5f0c1a9e-0000-0000-0000-000000000001";
    private static final String MANIFEST = """
            {"photos":[{"key":"photo-attempts/%1$s/0.jpg","index":0,"mediaType":"image/jpeg"},
                       {"key":"photo-attempts/%1$s/1.png","index":1,"mediaType":"image/png"}]}
            """.formatted(RECEIPT_ID);

    private HttpServer bucketServer;
    private final List<String> deletedPaths = new CopyOnWriteArrayList<>();
    private boolean manifestExists = true;

    @BeforeEach
    void startBucketServer() throws IOException {
        bucketServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bucketServer.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deletedPaths.add(path);
                exchange.sendResponseHeaders(204, -1);
            } else if (path.endsWith("/manifest.json") && manifestExists) {
                var body = MANIFEST.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        bucketServer.start();
    }

    @AfterEach
    void stopBucketServer() {
        bucketServer.stop(0);
    }

    private CloudflareR2PhotoAttemptStorage storage() {
        return new CloudflareR2PhotoAttemptStorage(RestClient.builder(),
                "http://127.0.0.1:" + bucketServer.getAddress().getPort(),
                "test-bucket", "AKIATEST", "SECRETTEST", "auto");
    }

    @Test
    void deleteRemovesEveryPhotoOfTheReceiptAndItsManifest() throws IOException {
        storage().delete(RECEIPT_ID);

        assertThat(deletedPaths).containsExactly(
                "/test-bucket/photo-attempts/" + RECEIPT_ID + "/0.jpg",
                "/test-bucket/photo-attempts/" + RECEIPT_ID + "/1.png",
                "/test-bucket/photo-attempts/" + RECEIPT_ID + "/manifest.json");
    }

    @Test
    void deleteIsSilentWhenNothingWasStoredForTheReceipt() throws IOException {
        manifestExists = false;

        storage().delete(RECEIPT_ID);

        assertThat(deletedPaths).containsExactly("/test-bucket/photo-attempts/" + RECEIPT_ID + "/manifest.json");
    }
}
