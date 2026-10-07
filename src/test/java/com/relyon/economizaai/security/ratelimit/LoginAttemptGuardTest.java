package com.relyon.economizaai.security.ratelimit;

import com.relyon.economizaai.exception.AccountLockedException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginAttemptGuardTest {

    private static final String EMAIL = "user@economizaai.app";

    private final Instant[] now = {Instant.parse("2026-10-07T12:00:00Z")};
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now[0]; }
    };

    private LoginAttemptGuard guard(int maxFailures, long windowSeconds, long durationSeconds) {
        return new LoginAttemptGuard(maxFailures, windowSeconds, durationSeconds, clock);
    }

    @Test
    void locksAfterReachingMaxFailures() {
        var guard = guard(3, 900, 900);
        for (var attempt = 0; attempt < 3; attempt++) guard.recordFailure(EMAIL);
        assertThrows(AccountLockedException.class, () -> guard.assertNotLocked(EMAIL));
    }

    @Test
    void belowThresholdStaysUnlocked() {
        var guard = guard(3, 900, 900);
        guard.recordFailure(EMAIL);
        guard.recordFailure(EMAIL);
        assertDoesNotThrow(() -> guard.assertNotLocked(EMAIL));
    }

    @Test
    void successResetsTheCounter() {
        var guard = guard(3, 900, 900);
        guard.recordFailure(EMAIL);
        guard.recordFailure(EMAIL);
        guard.recordSuccess(EMAIL);
        guard.recordFailure(EMAIL); // back to a single failure
        assertDoesNotThrow(() -> guard.assertNotLocked(EMAIL));
    }

    @Test
    void unlocksAfterCooldownExpires() {
        var guard = guard(3, 900, 900);
        for (var attempt = 0; attempt < 3; attempt++) guard.recordFailure(EMAIL);
        assertThrows(AccountLockedException.class, () -> guard.assertNotLocked(EMAIL));
        now[0] = now[0].plusSeconds(901);
        assertDoesNotThrow(() -> guard.assertNotLocked(EMAIL));
    }

    @Test
    void failuresOutsideWindowDoNotAccumulate() {
        var guard = guard(3, 900, 900);
        guard.recordFailure(EMAIL);
        guard.recordFailure(EMAIL);
        now[0] = now[0].plusSeconds(901); // window elapsed → counter resets
        guard.recordFailure(EMAIL);
        assertDoesNotThrow(() -> guard.assertNotLocked(EMAIL));
    }

    @Test
    void emailMatchingIsCaseInsensitive() {
        var guard = guard(3, 900, 900);
        for (var attempt = 0; attempt < 3; attempt++) guard.recordFailure("User@Economizaai.APP");
        assertThrows(AccountLockedException.class, () -> guard.assertNotLocked(EMAIL));
    }

    @Test
    void differentAccountsHaveIndependentCounters() {
        var guard = guard(3, 900, 900);
        for (var attempt = 0; attempt < 3; attempt++) guard.recordFailure("a@economizaai.app");
        assertDoesNotThrow(() -> guard.assertNotLocked("b@economizaai.app"));
    }
}
