package com.relyon.economizaai.service.cache;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HouseholdCacheGenTest {

    @Test
    void unknownHousehold_isGenerationZero() {
        var gen = new HouseholdCacheGen();
        assertEquals(0L, gen.get(UUID.randomUUID()));
    }

    @Test
    void bump_advancesOnlyThatHousehold() {
        var gen = new HouseholdCacheGen();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();

        gen.bump(a);
        gen.bump(a);

        assertEquals(2L, gen.get(a));
        assertEquals(0L, gen.get(b), "other households are untouched");
    }
}
