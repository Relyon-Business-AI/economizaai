package com.relyon.economizaai.security.ratelimit;

import io.github.bucket4j.Bucket;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Holds one {@link Bucket} per (policy, key) pair. In-memory only — fine
 * for single-instance deploys (Render free tier). Move to Redis-backed
 * Bucket4j when we go multi-instance; the {@link RateLimitFilter} contract
 * doesn't change.
 *
 * <p>Single responsibility: bucket lifecycle. The filter decides which
 * policy + key applies; this class just stores buckets.
 *
 * <p>Wired as an explicit @Bean in {@link RateLimitConfig} (not @Component)
 * so {@code @WebMvcTest} slices don't pull it in transitively.
 */
public class RateLimitRegistry {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public Bucket bucketFor(RateLimitPolicy policy, String key) {
        var compositeKey = policy.name() + ":" + key;
        return buckets.computeIfAbsent(compositeKey, k -> Bucket.builder().addLimit(policy.toBandwidth()).build());
    }
}
