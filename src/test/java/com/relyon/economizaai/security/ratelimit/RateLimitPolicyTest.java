package com.relyon.economizaai.security.ratelimit;

import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitPolicyTest {

    @Test
    void toBandwidth_buildsBucketWithExactCapacity() {
        var policy = new RateLimitPolicy("auth", 5, Duration.ofMinutes(1));
        var bucket = Bucket.builder().addLimit(policy.toBandwidth()).build();

        assertTrue(bucket.tryConsume(5), "bucket must allow exactly the configured capacity");
        assertFalse(bucket.tryConsume(1), "bucket must reject requests beyond capacity");
    }

    @Test
    void toBandwidth_refillsOverTime() {
        var policy = new RateLimitPolicy("fast", 2, Duration.ofMillis(100));
        var bucket = Bucket.builder().addLimit(policy.toBandwidth()).build();
        bucket.tryConsume(2);

        var probe = bucket.tryConsumeAndReturnRemaining(1);
        assertFalse(probe.isConsumed());
        assertTrue(probe.getNanosToWaitForRefill() > 0, "empty bucket must report a refill wait");
        assertTrue(probe.getNanosToWaitForRefill() <= Duration.ofMillis(100).toNanos(),
                "refill wait must not exceed the refill period");
    }

    @Test
    void recordExposesNameCapacityAndPeriod() {
        var policy = new RateLimitPolicy("submit", 30, Duration.ofHours(1));

        assertNotNull(policy.toBandwidth());
        assertTrue(policy.name().equals("submit") && policy.capacity() == 30
                && policy.refillPeriod().equals(Duration.ofHours(1)));
    }
}
