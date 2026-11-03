package com.relyon.economizaai.service.analytics.asc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

/**
 * Mints App Store Connect API JWT tokens (ES256, P-256 / secp256r1).
 *
 * <p>Apple limits JWT lifetime to 20 minutes (1200s). We generate a fresh token for
 * each sync run — the sync takes seconds — so caching is unnecessary. The JWT is
 * used directly as a Bearer token; no token-exchange step is needed (unlike Google SA).
 *
 * <p>JWT header: {@code {"alg":"ES256","kid":"<keyId>","typ":"JWT"}}
 * JWT claims: {@code {"iss":"<issuerId>","exp":<now+1199>,"aud":"appstoreconnect-v1"}}
 *
 * <p>JDK's {@code SHA256withECDSA} produces a DER-encoded signature, but JWT ES256
 * requires raw {@code r || s} (each 32 bytes, zero-padded). We convert with
 * {@link #derToRaw(byte[])}.
 */
@Slf4j
@Component
public class AppStoreConnectJwtProvider {

    private static final int TOKEN_TTL_SECONDS = 1199; // Apple max is 1200
    private static final String AUDIENCE = "appstoreconnect-v1";

    private final AppStoreConnectProperties properties;

    public AppStoreConnectJwtProvider(AppStoreConnectProperties properties) {
        this.properties = properties;
    }

    /** A signed JWT Bearer token valid for ~20 minutes. */
    public String bearerToken() {
        var header = base64Url(("{\"alg\":\"ES256\",\"kid\":\"" + properties.getKeyId() + "\",\"typ\":\"JWT\"}").getBytes());
        var now = Instant.now().getEpochSecond();
        var claims = base64Url(("{\"iss\":\"" + properties.getIssuerId() + "\",\"exp\":" + (now + TOKEN_TTL_SECONDS)
                + ",\"aud\":\"" + AUDIENCE + "\"}").getBytes());
        var signingInput = header + "." + claims;
        var signature = base64Url(derToRaw(signEs256(signingInput.getBytes())));
        return signingInput + "." + signature;
    }

    private byte[] signEs256(byte[] data) {
        try {
            var signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(loadPrivateKey());
            signer.update(data);
            return signer.sign();
        } catch (Exception ex) {
            throw new IllegalStateException("ASC JWT signing failed: " + ex.getMessage(), ex);
        }
    }

    private PrivateKey loadPrivateKey() {
        try {
            var pem = properties.getPrivateKey()
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            var der = Base64.getDecoder().decode(pem);
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException("ASC private key parse failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Converts a DER-encoded ECDSA signature to the raw {@code r || s} format
     * required by JWT ES256. For P-256 each integer is exactly 32 bytes.
     *
     * <p>DER layout: {@code 30 <totLen> 02 <rLen> <r> 02 <sLen> <s>}
     * r and s may have a leading 0x00 byte (sign extension) when the high bit is set.
     */
    static byte[] derToRaw(byte[] der) {
        int rLen = der[3] & 0xFF;
        int rOff = 4;
        int sLenOff = rOff + rLen + 1;
        int sLen = der[sLenOff] & 0xFF;
        int sOff = sLenOff + 1;

        var raw = new byte[64];
        copyUnsignedInt(der, rOff, rLen, raw, 0);
        copyUnsignedInt(der, sOff, sLen, raw, 32);
        return raw;
    }

    /** Copies a big-endian DER integer (possibly with a leading 0x00) into a fixed 32-byte slot. */
    private static void copyUnsignedInt(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff) {
        // Strip leading 0x00 sign byte added by DER when the high bit is set.
        int start = (srcLen > 32 && src[srcOff] == 0) ? srcOff + 1 : srcOff;
        int len = srcLen > 32 ? 32 : srcLen;
        System.arraycopy(src, start, dst, dstOff + (32 - len), len);
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
