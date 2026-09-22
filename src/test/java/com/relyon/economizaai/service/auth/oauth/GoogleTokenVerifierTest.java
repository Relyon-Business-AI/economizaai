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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoogleTokenVerifierTest {

    private static final String JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final String CLIENT_ID = "client-123.apps.googleusercontent.com";

    @Mock
    private JwksKeySource keySource;

    private OAuthTestTokens tokens;

    @BeforeEach
    void setUp() {
        tokens = OAuthTestTokens.generate();
        lenient().when(keySource.keySet(JWKS_URL)).thenReturn(tokens.publicJwks());
    }

    private GoogleTokenVerifier verifier(String clientIds) {
        return new GoogleTokenVerifier(keySource, clientIds);
    }

    private JWTClaimsSet.Builder baseClaims() {
        return new JWTClaimsSet.Builder()
                .subject("google-sub-1")
                .issuer("https://accounts.google.com")
                .audience(CLIENT_ID)
                .claim("email", "maria@example.com")
                .claim("email_verified", true)
                .claim("name", "Maria")
                .expirationTime(new Date(System.currentTimeMillis() + 60_000));
    }

    @Test
    void verify_validToken_returnsClaims() {
        var token = tokens.sign(baseClaims().build());

        var claims = verifier(CLIENT_ID).verify(token);

        assertEquals("google-sub-1", claims.subject());
        assertEquals("maria@example.com", claims.email());
        assertTrue(claims.emailVerified());
        assertEquals("Maria", claims.name());
    }

    @Test
    void verify_skipsAudienceCheck_whenNoClientIdsConfigured() {
        var token = tokens.sign(baseClaims().audience("some-other-app").build());

        var claims = verifier("").verify(token);

        assertEquals("google-sub-1", claims.subject());
    }

    @Test
    void verify_expiredToken_rejected() {
        var token = tokens.sign(baseClaims()
                .expirationTime(new Date(System.currentTimeMillis() - 60_000))
                .build());
        var verifier = verifier(CLIENT_ID);

        assertThrows(InvalidOAuthTokenException.class, () -> verifier.verify(token));
    }

    @Test
    void verify_wrongIssuer_rejected() {
        var token = tokens.sign(baseClaims().issuer("https://evil.example.com").build());
        var verifier = verifier(CLIENT_ID);

        assertThrows(InvalidOAuthTokenException.class, () -> verifier.verify(token));
    }

    @Test
    void verify_wrongAudience_rejected_whenConfigured() {
        var token = tokens.sign(baseClaims().audience("some-other-app").build());
        var verifier = verifier(CLIENT_ID);

        assertThrows(InvalidOAuthTokenException.class, () -> verifier.verify(token));
    }

    @Test
    void verify_badSignature_rejected() {
        var token = tokens.sign(baseClaims().build());
        when(keySource.keySet(JWKS_URL)).thenReturn(OAuthTestTokens.unrelatedJwks());
        var verifier = verifier(CLIENT_ID);

        assertThrows(InvalidOAuthTokenException.class, () -> verifier.verify(token));
    }
}
