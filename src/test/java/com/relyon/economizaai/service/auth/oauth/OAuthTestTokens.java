package com.relyon.economizaai.service.auth.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * In-test RSA keypair + token-signing helpers. Lets the verifier tests feed a
 * locally-signed token plus the matching public JWKS through a mocked
 * {@link JwksKeySource}, so no network call is made.
 */
final class OAuthTestTokens {

    private final RSAKey rsaKey;

    private OAuthTestTokens(RSAKey rsaKey) {
        this.rsaKey = rsaKey;
    }

    static OAuthTestTokens generate() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            var key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
            return new OAuthTestTokens(key);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Public-only key set — what a real JWKS endpoint serves. */
    JWKSet publicJwks() {
        return new JWKSet(rsaKey.toPublicJWK());
    }

    /** A second, unrelated key set so signatures don't verify (bad-signature case). */
    static JWKSet unrelatedJwks() {
        return generate().publicJwks();
    }

    String sign(JWTClaimsSet claims) {
        try {
            var header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build();
            var jwt = new SignedJWT(header, claims);
            jwt.sign(new RSASSASigner(rsaKey.toRSAPrivateKey()));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
