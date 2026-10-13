package com.relyon.economizaai.service.analytics.gsc;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints Google OAuth2 access tokens from a service-account key (JWT bearer grant),
 * so Search Console calls never run on a stale hand-pasted token. The signed JWT
 * asserts the SA identity + webmasters.readonly scope; Google exchanges it for a
 * ~1h access token, which we cache and re-mint shortly before expiry.
 *
 * <p>Pure JDK crypto (RS256 over the PKCS#8 private key) — no Google client lib.
 */
@Slf4j
@Component
public class GoogleServiceAccountTokenProvider {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/webmasters.readonly";
    private static final long ASSERTION_TTL_SECONDS = 3600;

    private final GoogleSearchConsoleProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile String cachedToken;
    private volatile Instant cachedExpiry = Instant.EPOCH;

    public GoogleServiceAccountTokenProvider(GoogleSearchConsoleProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.restClient = builder.build();
    }

    /** A valid access token for the configured service account, re-minted when the
     *  cached one is within 60s of expiry. Throws if minting fails. */
    public synchronized String accessToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedExpiry.minusSeconds(60))) {
            return cachedToken;
        }
        var assertion = buildSignedJwt();
        var form = "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=" + assertion;
        var response = restClient.post()
                .uri(URI.create(TOKEN_URI))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        try {
            var json = objectMapper.readTree(response);
            var token = json.path("access_token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("no access_token (error=" + json.path("error").asText("unknown")
                        + ": " + json.path("error_description").asText("") + ")");
            }
            var expiresIn = json.path("expires_in").asLong(ASSERTION_TTL_SECONDS);
            cachedToken = token;
            cachedExpiry = Instant.now().plusSeconds(expiresIn);
            log.info("gsc.sa_token.minted expiresInS={}", expiresIn);
            return token;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("GSC SA token parse failed: " + ex.getMessage(), ex);
        }
    }

    // Package-private for unit testing of the RS256 signing path (no network).
    String buildSignedJwt() {
        var now = Instant.now();
        var header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        var claims = String.format(
                "{\"iss\":\"%s\",\"scope\":\"%s\",\"aud\":\"%s\",\"iat\":%d,\"exp\":%d}",
                properties.getSaClientEmail(), SCOPE, TOKEN_URI,
                now.getEpochSecond(), now.plusSeconds(ASSERTION_TTL_SECONDS).getEpochSecond());
        var signingInput = header + "." + base64Url(claims.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + base64Url(sign(signingInput.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] sign(byte[] data) {
        try {
            var signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey());
            signer.update(data);
            return signer.sign();
        } catch (Exception ex) {
            throw new IllegalStateException("GSC SA JWT signing failed: " + ex.getMessage(), ex);
        }
    }

    private PrivateKey privateKey() {
        try {
            // Accept the PEM exactly as it sits in the JSON key (literal \n) or with real newlines.
            var pem = properties.getSaPrivateKey()
                    .replace("\\n", "\n")
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            var der = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException("GSC SA private key parse failed: " + ex.getMessage(), ex);
        }
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
