package com.relyon.economizaai.service.auth;

import java.util.Locale;

/**
 * Canonical form of an account e-mail: trimmed and lower-cased. Every entry point
 * that stores or looks up a user by e-mail goes through here, so "Maria@x.com" and
 * "maria@x.com" are always the same account (the database also enforces it with a
 * unique index on {@code lower(email)}).
 */
public final class EmailNormalizer {

    private EmailNormalizer() {}

    /** Null passes through, so optional e-mails (social providers, webhooks) stay optional. */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
