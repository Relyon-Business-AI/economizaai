package com.relyon.economizaai.security.ratelimit;

import com.relyon.economizaai.exception.AccountLockedException;
import com.relyon.economizaai.service.privacy.LogMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-ACCOUNT login throttle: locks an account for a cooldown after too many
 * failed password attempts within a window, independent of source IP. The IP
 * rate limit ({@link RateLimitFilter}) caps one IP; this closes the gap where an
 * attacker rotates IPs to keep guessing a SINGLE account's password.
 *
 * <p>Only real accounts are tracked — the guard is driven from the login path
 * exclusively when the e-mail resolves to a user, so a bot spraying random
 * addresses can never grow the map. In-memory (per instance), like the IP
 * limiter: a restart resets counters and a scaled deploy tracks per instance,
 * both acceptable for this surface. The threshold is deliberately high so a
 * legitimate user's typos never trip it.
 */
@Slf4j
@Component
public class LoginAttemptGuard {

    private final int maxFailures;
    private final Duration window;
    private final Duration lockDuration;
    private final Clock clock;
    private final Map<String, Attempts> byEmail = new ConcurrentHashMap<>();

    public LoginAttemptGuard(
            @Value("${economizaai.auth.lockout.max-failures:10}") int maxFailures,
            @Value("${economizaai.auth.lockout.window-seconds:900}") long windowSeconds,
            @Value("${economizaai.auth.lockout.duration-seconds:900}") long durationSeconds) {
        this(maxFailures, windowSeconds, durationSeconds, Clock.systemUTC());
    }

    LoginAttemptGuard(int maxFailures, long windowSeconds, long durationSeconds, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = Duration.ofSeconds(windowSeconds);
        this.lockDuration = Duration.ofSeconds(durationSeconds);
        this.clock = clock;
    }

    /** Throws {@link AccountLockedException} if the account is currently locked. */
    public void assertNotLocked(String email) {
        var attempts = byEmail.get(key(email));
        if (attempts != null && attempts.lockedUntil != null && clock.instant().isBefore(attempts.lockedUntil)) {
            throw new AccountLockedException();
        }
    }

    /** Records one failed password attempt for an existing account; locks on threshold. */
    public void recordFailure(String email) {
        var now = clock.instant();
        byEmail.compute(key(email), (ignored, existing) -> {
            var attempts = existing == null || now.isAfter(existing.windowStart.plus(window))
                    ? new Attempts(now) : existing;
            attempts.count++;
            if (attempts.count >= maxFailures) {
                attempts.lockedUntil = now.plus(lockDuration);
                log.warn("login.account_locked email={} failures={} locked_for_s={}",
                        LogMasker.email(email), attempts.count, lockDuration.toSeconds());
            }
            return attempts;
        });
    }

    /** Clears all state for an account after a successful login. */
    public void recordSuccess(String email) {
        byEmail.remove(key(email));
    }

    private static String key(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static final class Attempts {
        private final Instant windowStart;
        private int count;
        private Instant lockedUntil;

        private Attempts(Instant windowStart) {
            this.windowStart = windowStart;
        }
    }
}
