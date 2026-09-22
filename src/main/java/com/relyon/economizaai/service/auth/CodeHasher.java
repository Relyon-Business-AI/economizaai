package com.relyon.economizaai.service.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashing + comparison for short verification codes (password reset, email
 * verification). Codes are stored as SHA-256 hex so a DB leak never exposes a
 * live code; comparison is constant-time so response timing leaks nothing.
 */
final class CodeHasher {

    private CodeHasher() {}

    static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    /** Constant-time: does the typed code match the stored hash? */
    static boolean matches(String typedCode, String storedHash) {
        var typedHash = typedCode == null ? "" : sha256(typedCode);
        return MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.UTF_8),
                typedHash.getBytes(StandardCharsets.UTF_8));
    }
}
