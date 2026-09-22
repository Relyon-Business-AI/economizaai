package com.relyon.economizaai.service.auth.oauth;

import com.nimbusds.jose.jwk.JWKSet;

/**
 * Thin seam over the network fetch of a provider's JWKS (JSON Web Key Set).
 * Extracting it lets the token verifiers be unit-tested by feeding an in-test
 * key set, so tests never hit the network.
 */
public interface JwksKeySource {

    /** Returns the current key set for the given JWKS endpoint, refreshing as needed. */
    JWKSet keySet(String jwksUrl);
}
