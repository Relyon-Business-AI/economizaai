package com.relyon.economizaai.service.auth.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.relyon.economizaai.exception.InvalidOAuthTokenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Verifies a Google ID token: RS256 signature against Google's JWKS, issuer,
 * expiry, and (when configured) audience == one of our Google client ids.
 * The mobile app obtains the ID token natively; we only verify it here.
 */
@Slf4j
@Service
public class GoogleTokenVerifier {

    private static final String JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> VALID_ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private final JwksKeySource keySource;
    private final List<String> clientIds;

    public GoogleTokenVerifier(JwksKeySource keySource,
                               @Value("${economizaai.oauth.google.client-ids:}") String clientIds) {
        this.keySource = keySource;
        this.clientIds = Arrays.stream(clientIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .toList();
    }

    public record GoogleClaims(String subject, String email, boolean emailVerified, String name) {}

    public GoogleClaims verify(String idToken) {
        var claims = parseAndVerifySignature(idToken);
        validateIssuer(claims);
        validateExpiry(claims);
        validateAudience(claims);
        var emailVerified = getBoolean(claims, "email_verified");
        return new GoogleClaims(claims.getSubject(), getString(claims, "email"), emailVerified, getString(claims, "name"));
    }

    private JWTClaimsSet parseAndVerifySignature(String idToken) {
        try {
            var keySet = keySource.keySet(JWKS_URL);
            var processor = new DefaultJWTProcessor<SecurityContext>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                    JWSAlgorithm.RS256, new ImmutableJWKSet<>(keySet)));
            // We run our own issuer/exp/aud checks below, so no extra claims verifier here.
            processor.setJWTClaimsSetVerifier((claims, context) -> { });
            return processor.process(SignedJWT.parse(idToken), null);
        } catch (Exception ex) {
            log.warn("oauth.google.verify_failed {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new InvalidOAuthTokenException();
        }
    }

    private void validateIssuer(JWTClaimsSet claims) {
        if (!VALID_ISSUERS.contains(claims.getIssuer())) {
            log.warn("oauth.google.bad_issuer iss={}", claims.getIssuer());
            throw new InvalidOAuthTokenException();
        }
    }

    private void validateExpiry(JWTClaimsSet claims) {
        var expiration = claims.getExpirationTime();
        if (expiration == null || expiration.before(new Date())) {
            log.warn("oauth.google.expired exp={}", expiration);
            throw new InvalidOAuthTokenException();
        }
    }

    private void validateAudience(JWTClaimsSet claims) {
        if (clientIds.isEmpty()) {
            log.warn("oauth.google.aud_check_skipped reason=no-client-ids-configured");
            return;
        }
        var audiences = claims.getAudience();
        if (audiences == null || audiences.stream().noneMatch(clientIds::contains)) {
            log.warn("oauth.google.bad_audience aud={}", audiences);
            throw new InvalidOAuthTokenException();
        }
    }

    private static String getString(JWTClaimsSet claims, String name) {
        try {
            return claims.getStringClaim(name);
        } catch (Exception ex) {
            return null;
        }
    }

    // Returns the primitive boolean (default false) rather than a nullable Boolean:
    // a missing/unparseable claim means "not asserted", and false is the safe
    // default for a security claim like email_verified. Avoids handing callers a
    // null Boolean that would NPE on unboxing (Sonar S2447).
    private static boolean getBoolean(JWTClaimsSet claims, String name) {
        try {
            return Boolean.TRUE.equals(claims.getBooleanClaim(name));
        } catch (Exception ex) {
            return false;
        }
    }
}
