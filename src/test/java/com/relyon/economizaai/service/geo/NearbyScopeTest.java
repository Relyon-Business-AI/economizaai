package com.relyon.economizaai.service.geo;

import com.relyon.economizaai.service.geo.NearbyScope.Candidate;
import com.relyon.economizaai.service.geo.ViewerLocationService.Source;
import com.relyon.economizaai.service.geo.ViewerLocationService.ViewerLocation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NearbyScopeTest {

    private static final ViewerLocation LOCATED = new ViewerLocation(
            new BigDecimal("-30.0331"), new BigDecimal("-51.2300"), Source.RECEIPTS_MEDIAN, "RS");
    private static final ViewerLocation UNLOCATED_RS = new ViewerLocation(null, null, Source.NONE, "RS");
    private static final ViewerLocation UNKNOWN = new ViewerLocation(null, null, Source.NONE, null);

    private static final Candidate NEAR = new Candidate(false, 3.0, "RS");
    private static final Candidate FAR_SAME_STATE = new Candidate(false, 400.0, "RS");
    private static final Candidate FAR_OTHER_STATE = new Candidate(false, 2500.0, "PE");
    private static final Candidate UNGEOCODED = new Candidate(false, null, "RS");
    private static final Candidate WATCHED_FAR = new Candidate(true, 2500.0, "PE");

    private static final Function<Candidate, Candidate> IDENTITY = candidate -> candidate;

    @Test
    void located_keepsWatchedAndWithinRadiusOnly_noStateWidening() {
        var scope = new NearbyScope(LOCATED, Set.of(), 10);

        var kept = scope.narrow(List.of(NEAR, FAR_SAME_STATE, FAR_OTHER_STATE, UNGEOCODED, WATCHED_FAR), IDENTITY);

        assertEquals(List.of(NEAR, WATCHED_FAR), kept);
        assertTrue(scope.narrow(List.of(FAR_SAME_STATE), IDENTITY).isEmpty(), "nothing nearby is an honest answer");
    }

    @Test
    void unlocated_prefersWatched_thenState_thenNational() {
        var scope = new NearbyScope(UNLOCATED_RS, Set.of("x"), 10);
        assertEquals(List.of(WATCHED_FAR), scope.narrow(List.of(FAR_SAME_STATE, WATCHED_FAR), IDENTITY));
        assertEquals(List.of(FAR_SAME_STATE), scope.narrow(List.of(FAR_SAME_STATE, FAR_OTHER_STATE), IDENTITY));

        var nationalScope = new NearbyScope(UNKNOWN, Set.of(), 10);
        assertEquals(List.of(FAR_SAME_STATE, FAR_OTHER_STATE),
                nationalScope.narrow(List.of(FAR_SAME_STATE, FAR_OTHER_STATE), IDENTITY));
    }

    @Test
    void admits_singleEventRules() {
        var located = new NearbyScope(LOCATED, Set.of(), 10);
        assertTrue(located.admits(NEAR));
        assertTrue(located.admits(WATCHED_FAR));
        assertFalse(located.admits(FAR_SAME_STATE));
        assertFalse(located.admits(UNGEOCODED));

        var unlocated = new NearbyScope(UNLOCATED_RS, Set.of(), 10);
        assertTrue(unlocated.admits(FAR_SAME_STATE));
        assertFalse(unlocated.admits(FAR_OTHER_STATE));

        assertTrue(new NearbyScope(UNKNOWN, Set.of(), 10).admits(FAR_OTHER_STATE));
    }

    @Test
    void distanceAndTier() {
        var located = new NearbyScope(LOCATED, Set.of("12345678000199"), 10);
        assertTrue(located.distanceKmTo(new BigDecimal("-23.5505"), new BigDecimal("-46.6333")) > 800);
        assertNull(located.distanceKmTo(null, null));
        assertTrue(located.isWatched("12345678000199"));
        assertFalse(located.isWatched(null));
        assertEquals(NearbyScope.Tier.NEARBY, located.tier());
        assertEquals(NearbyScope.Tier.WATCHED, new NearbyScope(UNLOCATED_RS, Set.of("x"), 10).tier());
        assertEquals(NearbyScope.Tier.STATE, new NearbyScope(UNLOCATED_RS, Set.of(), 10).tier());
        assertEquals(NearbyScope.Tier.NATIONAL, new NearbyScope(UNKNOWN, Set.of(), 10).tier());
    }
}
