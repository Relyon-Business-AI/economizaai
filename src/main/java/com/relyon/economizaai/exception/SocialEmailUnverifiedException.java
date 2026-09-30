package com.relyon.economizaai.exception;

import com.relyon.economizaai.model.enums.AuthProvider;

/**
 * A social login matched an existing account by e-mail, but the incoming
 * provider token does not assert the e-mail as verified. Accepting it would let
 * anyone who can register that address at the provider take over the account —
 * so the login is refused until the e-mail is verified at the provider.
 */
public class SocialEmailUnverifiedException extends DomainException {

    public SocialEmailUnverifiedException(AuthProvider provider) {
        super("auth.social.email_unverified", displayName(provider));
    }

    private static String displayName(AuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> "Google";
            case APPLE -> "Apple";
            default -> provider.name();
        };
    }
}
