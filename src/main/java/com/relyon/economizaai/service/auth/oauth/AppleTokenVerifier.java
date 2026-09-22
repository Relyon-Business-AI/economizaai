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

/**
 * Verifies an Apple identity token: RS256 signature against Apple's JWKS,
 * issuer, expiry, and (when configured) audience == one of our Apple client
 * ids (the app's bundle id or Service ID). Apple only includes the user's name
 * on the first authorization, so the SocialLoginService also accepts a name
 * forwarded by the app.
 */
@Slf4j
@Service
public class AppleTokenVerifier {

    private static final String JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final String VALID_ISSUER = "https://appleid.apple.com";

    private final JwksKeySource keySource;
    private final List<String> clientIds;

    public AppleTokenVerifier(JwksKeySource keySource,
                              @Value("${economizaai.oauth.apple.client-ids:}") String clientIds) {
        this.keySource = keySource;
        this.clientIds = Arrays.stream(clientIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .toList();
    }

    public record AppleClaims(String subject, String email, boolean emailVerified, String name) {}

    public AppleClaims verify(String identityToken, String name) {
        var claims = parseAndVerifySignature(identityToken);
        validateIssuer(claims);
        validateExpiry(claims);
        validateAudience(claims);
        return new AppleClaims(claims.getSubject(), getString(claims, "email"),
                emailVerified(claims), name);
    }

    /**
     * Apple sends {@code email_verified} as a boolean or the string "true". When the
     * claim is absent we default to true — Apple verifies the addresses it issues
     * (including private relay addresses).
     */
    private static boolean emailVerified(JWTClaimsSet claims) {
        var raw = claims.getClaim("email_verified");
        if (raw == null) return true;
        if (raw instanceof Boolean bool) return bool;
        return "true".equalsIgnoreCase(raw.toString());
    }

    private JWTClaimsSet parseAndVerifySignature(String identityToken) {
        try {
            var keySet = keySource.keySet(JWKS_URL);
            var processor = new DefaultJWTProcessor<SecurityContext>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                    JWSAlgorithm.RS256, new ImmutableJWKSet<>(keySet)));
            processor.setJWTClaimsSetVerifier((claims, context) -> { });
            return processor.process(SignedJWT.parse(identityToken), null);
        } catch (Exception ex) {
            log.warn("oauth.apple.verify_failed {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            throw new InvalidOAuthTokenException();
        }
    }

    private void validateIssuer(JWTClaimsSet claims) {
        if (!VALID_ISSUER.equals(claims.getIssuer())) {
            log.warn("oauth.apple.bad_issuer iss={}", claims.getIssuer());
            throw new InvalidOAuthTokenException();
        }
    }

    private void validateExpiry(JWTClaimsSet claims) {
        var expiration = claims.getExpirationTime();
        if (expiration == null || expiration.before(new Date())) {
            log.warn("oauth.apple.expired exp={}", expiration);
            throw new InvalidOAuthTokenException();
        }
    }

    private void validateAudience(JWTClaimsSet claims) {
        if (clientIds.isEmpty()) {
            log.warn("oauth.apple.aud_check_skipped reason=no-client-ids-configured");
            return;
        }
        var audiences = claims.getAudience();
        if (audiences == null || audiences.stream().noneMatch(clientIds::contains)) {
            log.warn("oauth.apple.bad_audience aud={}", audiences);
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
}
