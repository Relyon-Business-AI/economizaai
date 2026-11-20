package com.relyon.economizaai.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;

import java.time.Duration;

/**
 * Holds one {@link Bucket} per (policy, key) pair. In-memory only — fine
 * for single-instance deploys (Render free tier). Move to Redis-backed
 * Bucket4j when we go multi-instance; the {@link RateLimitFilter} contract
 * doesn't change.
 *
 * <p>Backed by Caffeine with expire-after-access so the map can't grow
 * unbounded (every distinct IP creates an entry). Eviction is lossless:
 * the longest refill window is 1 hour, so a bucket idle that long is fully
 * refilled anyway — recreating it fresh grants the same budget.
 *
 * <p>Single responsibility: bucket lifecycle. The filter decides which
 * policy + key applies; this class just stores buckets.
 *
 * <p>Wired as an explicit @Bean in {@link RateLimitConfig} (not @Component)
 * so {@code @WebMvcTest} slices don't pull it in transitively.
 */
public class RateLimitRegistry {

    /** A bucket and the policy it was built for. */
    private record PolicyBucket(RateLimitPolicy policy, Bucket bucket) {}

    private final Cache<String, PolicyBucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(100_000)
            .build();

    /**
     * The bucket of this policy for this key. A policy whose limit changed since the bucket was
     * built (limits edited in the admin) gets a fresh bucket, so the new limit applies at once.
     */
    public Bucket bucketFor(RateLimitPolicy policy, String key) {
        var compositeKey = policy.name() + ":" + key;
        return buckets.asMap()
                .compute(compositeKey, (unusedKey, existing) ->
                        existing != null && existing.policy().equals(policy) ? existing : newBucket(policy))
                .bucket();
    }

    private static PolicyBucket newBucket(RateLimitPolicy policy) {
        return new PolicyBucket(policy, Bucket.builder().addLimit(policy.toBandwidth()).build());
    }
}
