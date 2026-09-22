package com.relyon.economizaai.security.ratelimit;

import io.github.bucket4j.Bandwidth;

import java.time.Duration;

/**
 * One throttle policy: how many tokens fit in the bucket and how fast they
 * refill. Bucket4j's {@link Bandwidth} carries both. Bound to a route
 * group + key strategy by {@link RateLimitFilter}.
 */
public record RateLimitPolicy(String name, int capacity, Duration refillPeriod) {

    public Bandwidth toBandwidth() {
        return Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, refillPeriod)
                .build();
    }
}
