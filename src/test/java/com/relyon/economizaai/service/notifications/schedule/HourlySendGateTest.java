package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HourlySendGateTest {

    private final HourlySendGate gate = new HourlySendGate();

    /** 2026-11-16 is a Monday. */
    private static ZonedDateTime brasilia(int dayOfNovember, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, dayOfNovember, hour, minute, 0, 0, BrazilClock.ZONE);
    }

    @Test
    void daily_letsOnlyTheConfiguredHourThrough() {
        assertFalse(gate.claimDaily(brasilia(16, 9, 0), 10));
        assertTrue(gate.claimDaily(brasilia(16, 10, 0), 10));
        assertFalse(gate.claimDaily(brasilia(16, 11, 0), 10));
    }

    @Test
    void theSameHourIsNeverLetThroughTwice_butTheNextDayIs() {
        assertTrue(gate.claimDaily(brasilia(16, 10, 0), 10));
        assertFalse(gate.claimDaily(brasilia(16, 10, 0), 10));
        assertFalse(gate.claimDaily(brasilia(16, 10, 3), 10));

        assertTrue(gate.claimDaily(brasilia(17, 10, 0), 10));
    }

    @Test
    void aTickThatFiresAMomentEarlyOrLateStillBelongsToItsHour() {
        var momentEarly = brasilia(16, 9, 59).plusSeconds(59);
        assertTrue(gate.claimDaily(momentEarly, 10));

        var late = brasilia(17, 10, 7);
        assertTrue(gate.claimDaily(late, 10));
    }

    @Test
    void theHourIsReadInBrasiliaWhateverTheZoneOfTheClock() {
        var tenInBrasiliaSeenFromUtc = ZonedDateTime.of(2026, 11, 16, 13, 0, 0, 0, ZoneOffset.UTC);

        assertTrue(gate.claimDaily(tenInBrasiliaSeenFromUtc, 10));
    }

    @Test
    void dailyAtSeveralHours_letsEachOfThemThroughOnce() {
        var hours = List.of(9, 18);

        assertTrue(gate.claimDailyAtAnyOf(brasilia(16, 9, 0), hours));
        assertFalse(gate.claimDailyAtAnyOf(brasilia(16, 9, 0), hours));
        assertFalse(gate.claimDailyAtAnyOf(brasilia(16, 12, 0), hours));
        assertTrue(gate.claimDailyAtAnyOf(brasilia(16, 18, 0), hours));
    }

    @Test
    void weekly_needsBothTheWeekdayAndTheHour() {
        assertFalse(gate.claimWeekly(brasilia(16, 9, 0), DayOfWeek.MONDAY, 10));
        assertFalse(gate.claimWeekly(brasilia(17, 10, 0), DayOfWeek.MONDAY, 10));
        assertTrue(gate.claimWeekly(brasilia(16, 10, 0), DayOfWeek.MONDAY, 10));
        assertTrue(gate.claimWeekly(brasilia(23, 10, 0), DayOfWeek.MONDAY, 10));
    }

    @Test
    void monthly_needsBothTheDayOfTheMonthAndTheHour() {
        assertFalse(gate.claimMonthly(brasilia(2, 10, 0), 1, 10));
        assertFalse(gate.claimMonthly(brasilia(1, 11, 0), 1, 10));
        assertTrue(gate.claimMonthly(brasilia(1, 10, 0), 1, 10));
        assertFalse(gate.claimMonthly(brasilia(1, 10, 0), 1, 10));
    }

    @Test
    void aNewTimeSetInTheAdminIsFollowedAtTheNextTick() {
        assertTrue(gate.claimDaily(brasilia(16, 10, 0), 10));

        assertTrue(gate.claimDaily(brasilia(16, 15, 0), 15));
        assertFalse(gate.claimDaily(brasilia(17, 10, 0), 15));
    }
}
