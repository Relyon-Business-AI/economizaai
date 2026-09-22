package com.relyon.economizaai.service.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * Per-household cache generation counter. Folded into the cache keys of the
 * household-scoped read caches (dashboard, insightsSpend) so that bumping a
 * household's generation atomically orphans all of its cached entries —
 * including period-keyed ones we can't address individually — without evicting
 * other households. Orphaned entries age out by TTL.
 *
 * <p>In-memory only: resets to 0 on restart (at worst one recompute) and is
 * per-instance (single-instance deployments only — see DEV_NOTES).
 */
@Component
public class HouseholdCacheGen {

    private final ConcurrentHashMap<UUID, Long> generations = new ConcurrentHashMap<>();

    /** Current generation for a household (0 if never mutated since startup). */
    public long get(UUID householdId) {
        return generations.getOrDefault(householdId, 0L);
    }

    /** Invalidate a household's cached reads by advancing its generation. */
    public void bump(UUID householdId) {
        generations.merge(householdId, 1L, Long::sum);
    }
}
