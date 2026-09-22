package com.relyon.economizaai.exception;

import com.relyon.economizaai.model.enums.AuthProvider;

/**
 * Someone tried an email/password login on an account that was created via a
 * social provider (Google/Apple) and therefore has no local password. Instead
 * of a generic "invalid credentials", we tell them which provider button to
 * use. The provider is exposed to the handler so the FE can highlight the
 * right button, not just render the message.
 */
public class SocialAccountLoginException extends DomainException {

    private final transient AuthProvider provider;

    public SocialAccountLoginException(AuthProvider provider) {
        super("auth.social_account", displayName(provider));
        this.provider = provider;
    }

    public AuthProvider getProvider() {
        return provider;
    }

    private static String displayName(AuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> "Google";
            case APPLE -> "Apple";
            default -> provider.name();
        };
    }
}
