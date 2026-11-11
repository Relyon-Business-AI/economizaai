package com.relyon.economizaai.service.photoattempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stores photo-extraction-attempt images in Cloudflare R2 (same bucket/creds as
 * profile pics), under {@code photo-attempts/<receiptId>/<index>.<ext>} plus a
 * small {@code manifest.json} so retrieval needs no S3 listing. SigV4 signed by
 * hand over {@link RestClient} — same slim approach as the profile-picture store
 * (no AWS SDK, keeps the heap small). Active only where R2 is configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "economizaai.photo-retention.storage", havingValue = "r2")
public class CloudflareR2PhotoAttemptStorage implements PhotoAttemptStorage {

    private static final String SERVICE = "s3";
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String KEY_PREFIX = "photo-attempts/";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;

    private final RestClient restClient;
    private final String endpoint;
    private final String host;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String region;

    public CloudflareR2PhotoAttemptStorage(
            RestClient.Builder builder,
            @Value("${economizaai.photo-retention.r2.endpoint}") String endpoint,
            @Value("${economizaai.photo-retention.r2.bucket}") String bucket,
            @Value("${economizaai.photo-retention.r2.access-key}") String accessKey,
            @Value("${economizaai.photo-retention.r2.secret-key}") String secretKey,
            @Value("${economizaai.photo-retention.r2.region:auto}") String region) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        this.restClient = builder.requestFactory(requestFactory).build();
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.host = URI.create(this.endpoint).getHost();
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.region = region;
        log.info("photo_attempt.storage.r2 endpoint={} bucket={}", this.endpoint, bucket);
    }

    @Override
    public List<StoredPhoto> store(String receiptId, List<byte[]> photos, List<String> mediaTypes) throws IOException {
        var stored = new ArrayList<StoredPhoto>();
        for (var index = 0; index < photos.size(); index++) {
            var mediaType = index < mediaTypes.size() ? mediaTypes.get(index) : "image/jpeg";
            var key = KEY_PREFIX + receiptId + "/" + index + extensionFor(mediaType);
            putObject(key, photos.get(index), mediaType);
            stored.add(new StoredPhoto(key, index, mediaType));
        }
        putObject(manifestKey(receiptId), MAPPER.writeValueAsBytes(Map.of("photos", stored)), "application/json");
        log.info("photo_attempt.stored receipt={} photos={}", receiptId, stored.size());
        return stored;
    }

    @Override
    public List<StoredPhoto> list(String receiptId) throws IOException {
        var manifest = read(manifestKey(receiptId));
        if (manifest == null) return List.of();
        var node = MAPPER.readTree(manifest).path("photos");
        var photos = new ArrayList<StoredPhoto>();
        for (var entry : node) {
            photos.add(new StoredPhoto(entry.path("key").asText(), entry.path("index").asInt(),
                    entry.path("mediaType").asText("image/jpeg")));
        }
        return photos;
    }

    @Override
    public byte[] read(String key) throws IOException {
        var headers = signedHeaders("GET", "/" + bucket + "/" + key, hex(sha256(new byte[0])), Instant.now());
        try {
            return restClient.get()
                    .uri(URI.create(objectUrl(key)))
                    .headers(outgoing -> headers.forEach(outgoing::set))
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) return null;
            throw new IOException("R2 get failed: " + ex.getStatusCode(), ex);
        }
    }

    @Override
    public void delete(String receiptId) throws IOException {
        var photos = list(receiptId);
        for (var photo : photos) {
            deleteObject(photo.key());
        }
        deleteObject(manifestKey(receiptId));
        log.info("photo_attempt.deleted receipt={} photos={}", receiptId, photos.size());
    }

    private void deleteObject(String key) throws IOException {
        var headers = signedHeaders("DELETE", "/" + bucket + "/" + key, hex(sha256(new byte[0])), Instant.now());
        try {
            restClient.delete()
                    .uri(URI.create(objectUrl(key)))
                    .headers(outgoing -> headers.forEach(outgoing::set))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) return; // already gone
            throw new IOException("R2 delete failed: " + ex.getStatusCode(), ex);
        }
    }

    private void putObject(String key, byte[] data, String contentType) throws IOException {
        var headers = signedHeaders("PUT", "/" + bucket + "/" + key, hex(sha256(data)), Instant.now());
        try {
            restClient.put()
                    .uri(URI.create(objectUrl(key)))
                    .headers(outgoing -> headers.forEach(outgoing::set))
                    .header("Content-Type", contentType == null ? "application/octet-stream" : contentType)
                    .body(data)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw new IOException("R2 put failed: " + ex.getStatusCode() + " " + ex.getResponseBodyAsString(), ex);
        }
    }

    private static String manifestKey(String receiptId) {
        return KEY_PREFIX + receiptId + "/manifest.json";
    }

    private String objectUrl(String key) {
        return endpoint + "/" + bucket + "/" + key;
    }

    Map<String, String> signedHeaders(String method, String canonicalUri, String payloadHash, Instant now) {
        var amzDate = AMZ_DATE.format(now);
        var dateStamp = DATESTAMP.format(now);
        var signedHeaderNames = "host;x-amz-content-sha256;x-amz-date";
        var canonicalHeaders = "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        var canonicalRequest = method + "\n" + canonicalUri + "\n\n"
                + canonicalHeaders + "\n" + signedHeaderNames + "\n" + payloadHash;
        var scope = dateStamp + "/" + region + "/" + SERVICE + "/aws4_request";
        var stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        var signature = hex(hmac(signingKey(dateStamp), stringToSign));
        var authorization = ALGORITHM + " Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaderNames + ", Signature=" + signature;
        var headers = new LinkedHashMap<String, String>();
        headers.put("x-amz-date", amzDate);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("Authorization", authorization);
        return headers;
    }

    private byte[] signingKey(String dateStamp) {
        var kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        var kRegion = hmac(kDate, region);
        var kService = hmac(kRegion, SERVICE);
        return hmac(kService, "aws4_request");
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 failed", ex);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 failed", ex);
        }
    }

    private static String hex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (var singleByte : bytes) {
            sb.append(Character.forDigit((singleByte >> 4) & 0xF, 16));
            sb.append(Character.forDigit(singleByte & 0xF, 16));
        }
        return sb.toString();
    }

    private static String extensionFor(String contentType) {
        if (contentType == null) return ".jpg";
        return switch (contentType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/heic" -> ".heic";
            default -> ".jpg";
        };
    }
}
