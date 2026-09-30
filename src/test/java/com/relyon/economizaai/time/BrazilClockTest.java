package com.relyon.economizaai.time;

import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrazilClockTest {

    @Test
    void zone_isAmericaSaoPaulo() {
        assertEquals(ZoneId.of("America/Sao_Paulo"), BrazilClock.ZONE);
    }

    @Test
    void helpers_allResolveInTheBrasiliaZone() {
        // Compare against an explicit Brasília read taken around the calls; the
        // values must agree on the calendar day/month regardless of the host zone.
        var before = ZonedDateTime.now(BrazilClock.ZONE);
        var today = BrazilClock.today();
        var nowDateTime = BrazilClock.nowDateTime();
        var currentMonth = BrazilClock.currentYearMonth();
        var nowOffset = BrazilClock.nowOffset();
        var now = BrazilClock.now();
        var after = ZonedDateTime.now(BrazilClock.ZONE);

        // A midnight/month flip between `before` and `after` is astronomically
        // unlikely, but guard the assertion anyway: only assert when the window
        // stayed within one day.
        if (before.toLocalDate().equals(after.toLocalDate())) {
            assertEquals(before.toLocalDate(), today);
            assertEquals(before.toLocalDate(), nowDateTime.toLocalDate());
            assertEquals(YearMonth.from(before), currentMonth);
            assertEquals(before.toLocalDate(), nowOffset.toLocalDate());
            assertEquals(before.toLocalDate(), now.toLocalDate());
            assertEquals(BrazilClock.ZONE, now.getZone());
        }
    }
}
