package com.relyon.economizaai.exception;

/**
 * Thrown when an account is temporarily locked after too many failed password
 * attempts (see {@code LoginAttemptGuard}). Distinct from
 * {@link InvalidCredentialsException} so the client can show a "try again later"
 * message instead of "wrong password". Maps to 429.
 */
public class AccountLockedException extends DomainException {

    public AccountLockedException() {
        super("auth.account_locked");
    }
}
