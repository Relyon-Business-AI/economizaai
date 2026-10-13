package com.relyon.economizaai.service.analytics.gsc;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline coverage of the service-account JWT path — the only hand-rolled crypto here.
 * Generates a throwaway RSA key, signs a JWT via the provider, and verifies the
 * signature + claims with the matching public key (no Google call involved).
 */
class GoogleServiceAccountTokenProviderTest {

    @Test
    void buildSignedJwt_parsesPkcs8KeyAndSignsVerifiableRs256() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();

        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        var properties = new GoogleSearchConsoleProperties();
        properties.setSaClientEmail("economiza-ai-app@economiza-ai-502515.iam.gserviceaccount.com");
        properties.setSaPrivateKey(pem);

        var provider = new GoogleServiceAccountTokenProvider(properties, RestClient.builder());
        var jwt = provider.buildSignedJwt();

        var parts = jwt.split("\\.");
        assertEquals(3, parts.length, "header.claims.signature");

        var signer = Signature.getInstance("SHA256withRSA");
        signer.initVerify(keyPair.getPublic());
        signer.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(signer.verify(Base64.getUrlDecoder().decode(parts[2])), "signature verifies with the public key");

        var header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        var claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(header.contains("RS256"), "RS256 alg");
        assertTrue(claims.contains("economiza-ai-app@economiza-ai-502515.iam.gserviceaccount.com"), "iss = SA email");
        assertTrue(claims.contains("https://www.googleapis.com/auth/webmasters.readonly"), "readonly scope");
        assertTrue(claims.contains("https://oauth2.googleapis.com/token"), "token endpoint aud");
    }

    @Test
    void handlesLiteralBackslashNInKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();

        // Simulate the key pasted as a single line with literal \n (as it sits in the JSON key file).
        var oneLineWithEscapes = "-----BEGIN PRIVATE KEY-----\\n"
                + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\\n-----END PRIVATE KEY-----\\n";

        var properties = new GoogleSearchConsoleProperties();
        properties.setSaClientEmail("sa@example.iam.gserviceaccount.com");
        properties.setSaPrivateKey(oneLineWithEscapes);

        var provider = new GoogleServiceAccountTokenProvider(properties, RestClient.builder());
        var jwt = provider.buildSignedJwt();

        var parts = jwt.split("\\.");
        var signer = Signature.getInstance("SHA256withRSA");
        signer.initVerify(keyPair.getPublic());
        signer.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        assertTrue(signer.verify(Base64.getUrlDecoder().decode(parts[2])), "literal-\\n key still parses + signs");
    }
}
