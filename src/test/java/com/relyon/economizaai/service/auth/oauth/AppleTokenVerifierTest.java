package com.relyon.economizaai.service.auth.oauth;

import com.nimbusds.jwt.JWTClaimsSet;
import com.relyon.economizaai.exception.InvalidOAuthTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppleTokenVerifierTest {

    private static final String JWKS_URL = "https://appleid.apple.com/auth/keys";
    private static final String CLIENT_ID = "com.relyon.economizaai";

    @Mock
    private JwksKeySource keySource;

    private OAuthTestTokens tokens;

    @BeforeEach
    void setUp() {
        tokens = OAuthTestTokens.generate();
        lenient().when(keySource.keySet(JWKS_URL)).thenReturn(tokens.publicJwks());
    }

    private AppleTokenVerifier verifier(String clientIds) {
        return new AppleTokenVerifier(keySource, clientIds);
    }

    private JWTClaimsSet.Builder baseClaims() {
        return new JWTClaimsSet.Builder()
                .subject("apple-sub-1")
                .issuer("https://appleid.apple.com")
                .audience(CLIENT_ID)
                .claim("email", "joao@example.com")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000));
    }

    @Test
    void verify_validToken_returnsClaims_withForwardedName() {
        var token = tokens.sign(baseClaims().build());

        var claims = verifier(CLIENT_ID).verify(token, "Joao");

        assertEquals("apple-sub-1", claims.subject());
        assertEquals("joao@example.com", claims.email());
        assertEquals("Joao", claims.name());
    }

    @Test
    void verify_emailVerifiedBooleanClaim_isThreaded() {
        var token = tokens.sign(baseClaims().claim("email_verified", true).build());

        assertTrue(verifier(CLIENT_ID).verify(token, "Joao").emailVerified());
    }

    @Test
    void verify_emailVerifiedStringTrueClaim_isThreaded() {
        var token = tokens.sign(baseClaims().claim("email_verified", "true").build());

        assertTrue(verifier(CLIENT_ID).verify(token, "Joao").emailVerified());
    }

    @Test
    void verify_emailVerifiedFalseClaim_isThreaded() {
        var token = tokens.sign(baseClaims().claim("email_verified", false).build());

        assertFalse(verifier(CLIENT_ID).verify(token, "Joao").emailVerified());
    }

    @Test
    void verify_emailVerifiedAbsent_defaultsToTrue() {
        var token = tokens.sign(baseClaims().build());

        assertTrue(verifier(CLIENT_ID).verify(token, "Joao").emailVerified());
    }

    @Test
    void verify_nullName_tolerated() {
        var token = tokens.sign(baseClaims().build());

        var claims = verifier(CLIENT_ID).verify(token, null);

        assertNull(claims.name());
    }

    @Test
    void verify_failsClosed_whenNoClientIdsConfigured() {
        // No expected audience → every social login is rejected; skipping the
        // check would let a token minted for any other app authenticate.
        var token = tokens.sign(baseClaims().audience("some-other-app").build());

        assertThrows(InvalidOAuthTokenException.class, () -> verifier("").verify(token, "Joao"));
    }

    @Test
    void verify_expiredToken_rejected() {
        var token = tokens.sign(baseClaims()
                .expirationTime(new Date(System.currentTimeMillis() - 60_000))
                .build());
        var verifier = verifier(CLIENT_ID);

        assertThrows(InvalidOAuthTokenException.class, () -> verifier.verify(token, "Joao"));
    }

    @Test
    void verify_wrongIssuer_rejected() {
        var token = tokens.sign(baseClaims().issuer("https://evil.example.com").build());
        var verifier = verifier(CLIENT_ID);

        assertThrows(InvalidOAuthTokenException.class, () -> verifier.verify(token, "Joao"));
    }

    @Test
    void verify_wrongAudience_rejected_whenConfigured() {
        var token = tokens.sign(baseClaims().audience("some-other-app").build());
        var verifier = verifier(CLIENT_ID);

        assertThrows(InvalidOAuthTokenException.class, () -> verifier.verify(token, "Joao"));
    }

    @Test
    void verify_badSignature_rejected() {
        var token = tokens.sign(baseClaims().build());
        when(keySource.keySet(JWKS_URL)).thenReturn(OAuthTestTokens.unrelatedJwks());
        var verifier = verifier(CLIENT_ID);

        assertThrows(InvalidOAuthTokenException.class, () -> verifier.verify(token, "Joao"));
    }
}
