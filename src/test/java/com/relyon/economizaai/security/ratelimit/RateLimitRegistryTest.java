package com.relyon.economizaai.security.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitRegistryTest {

    private RateLimitRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RateLimitRegistry();
    }

    @Test
    void samePolicyAndKey_returnsSameBucket() {
        var policy = new RateLimitPolicy("auth", 5, Duration.ofMinutes(1));

        var firstLookup = registry.bucketFor(policy, "ip:1.2.3.4");
        var secondLookup = registry.bucketFor(policy, "ip:1.2.3.4");

        assertSame(firstLookup, secondLookup);
    }

    @Test
    void differentKeys_getSeparateBuckets() {
        var policy = new RateLimitPolicy("auth", 5, Duration.ofMinutes(1));

        var bucketA = registry.bucketFor(policy, "ip:1.2.3.4");
        var bucketB = registry.bucketFor(policy, "ip:5.6.7.8");

        assertNotSame(bucketA, bucketB);
    }

    @Test
    void differentPolicies_withSameKey_getSeparateBuckets() {
        var authPolicy = new RateLimitPolicy("auth", 5, Duration.ofMinutes(1));
        var submitPolicy = new RateLimitPolicy("submit", 30, Duration.ofHours(1));

        var authBucket = registry.bucketFor(authPolicy, "ip:1.2.3.4");
        var submitBucket = registry.bucketFor(submitPolicy, "ip:1.2.3.4");

        assertNotSame(authBucket, submitBucket);
    }

    @Test
    void newBucket_carriesPolicyCapacity() {
        var policy = new RateLimitPolicy("auth", 3, Duration.ofMinutes(1));
        var bucket = registry.bucketFor(policy, "ip:1.2.3.4");

        assertTrue(bucket.tryConsume(3), "fresh bucket must hold full capacity");
        assertFalse(bucket.tryConsume(1), "capacity+1 must be rejected");
    }

    @Test
    void exhaustedBucket_doesNotAffectOtherKeys() {
        var policy = new RateLimitPolicy("auth", 2, Duration.ofMinutes(1));
        var exhausted = registry.bucketFor(policy, "ip:1.2.3.4");
        exhausted.tryConsume(2);

        var other = registry.bucketFor(policy, "ip:9.9.9.9");
        assertTrue(other.tryConsume(1));
    }
}
