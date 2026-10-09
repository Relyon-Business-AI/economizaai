package com.relyon.economizaai.service.profile;

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
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Profile-picture storage backed by Cloudflare R2 (S3-compatible). Active in
 * prod (where the service has no local disk, so deploys are zero-downtime) —
 * selected via {@code economizaai.profile-picture.storage=r2}.
 *
 * <p>We sign requests with AWS SigV4 by hand over a {@link RestClient} rather
 * than pulling the full AWS SDK: only three one-object operations are needed
 * (PUT/GET/DELETE), and a slim client keeps the heap small (the prod box was
 * OOM-sensitive). R2 uses region {@code auto} and the account-scoped endpoint
 * {@code https://<account>.r2.cloudflarestorage.com}.
 *
 * <p>The object key equals the opaque key returned to the caller (a
 * server-generated {@code <uuid>.<ext>}), so it's 1:1 with the old on-disk
 * filenames — the disk→R2 migration copies files under the same key.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "economizaai.profile-picture.storage", havingValue = "r2")
public class CloudflareR2ProfilePictureStorage implements ProfilePictureStorage {

    private static final String SERVICE = "s3";
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
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

    public CloudflareR2ProfilePictureStorage(
            RestClient.Builder builder,
            @Value("${economizaai.profile-picture.r2.endpoint}") String endpoint,
            @Value("${economizaai.profile-picture.r2.bucket}") String bucket,
            @Value("${economizaai.profile-picture.r2.access-key}") String accessKey,
            @Value("${economizaai.profile-picture.r2.secret-key}") String secretKey,
            @Value("${economizaai.profile-picture.r2.region:auto}") String region) {
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
        log.info("profile_picture.storage.r2 endpoint={} bucket={}", this.endpoint, bucket);
    }

    @Override
    public String store(InputStream bytes, String contentType, long sizeBytes) throws IOException {
        var data = bytes.readAllBytes();
        var key = UUID.randomUUID() + extensionFor(contentType);
        putObject(key, data, contentType);
        log.info("profile_picture.stored key={} size={} backend=r2", key, sizeBytes);
        return key;
    }

    /**
     * Uploads bytes under an explicit key (overwrites). Used by {@link #store}
     * (fresh UUID key) and by the one-time disk→R2 migration (existing filename).
     */
    void putObject(String key, byte[] data, String contentType) throws IOException {
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
    public void delete(String key) throws IOException {
        if (key == null) return;
        var headers = signedHeaders("DELETE", "/" + bucket + "/" + key, hex(sha256(new byte[0])), Instant.now());
        try {
            restClient.delete()
                    .uri(URI.create(objectUrl(key)))
                    .headers(outgoing -> headers.forEach(outgoing::set))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) return; // already gone — delete is best-effort
            throw new IOException("R2 delete failed: " + ex.getStatusCode(), ex);
        }
    }

    private String objectUrl(String key) {
        return endpoint + "/" + bucket + "/" + key;
    }

    /**
     * AWS SigV4 for a single S3 object request. Signs {@code host},
     * {@code x-amz-content-sha256} and {@code x-amz-date}; returns the headers
     * the caller must set ({@code Host} is added by the HTTP layer and matches
     * the signed value). Keys are server-generated UUIDs, so the canonical URI
     * needs no percent-encoding.
     */
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
        if (contentType == null) return "";
        return switch (contentType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }
}
