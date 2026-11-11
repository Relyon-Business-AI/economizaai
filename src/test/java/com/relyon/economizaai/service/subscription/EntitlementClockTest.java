package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.time.BrazilClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntitlementClockTest {

    /** Host wall-clock (how timestamps are stored) of a Brasília wall-clock moment. */
    private static LocalDateTime brazil(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute)
                .atZone(BrazilClock.ZONE).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static final LocalDateTime PROMO_END = EntitlementClock.endOfBrazilDay(LocalDate.of(2026, 12, 31));

    @Test
    void endOfBrazilDay_isTheLastSecondOfThatDayInBrasilia() {
        assertEquals(brazil(2026, 12, 31, 23, 59).plusSeconds(59), PROMO_END);
        assertEquals(LocalDate.of(2026, 12, 31), EntitlementClock.brazilDayOf(PROMO_END));
        assertEquals(LocalDate.of(2027, 1, 1), EntitlementClock.brazilDayOf(PROMO_END.plusSeconds(1)));
    }

    @Test
    void monthAfter_promoHolder_startsCountingOnJanuaryFirst() {
        var earnedInNovember = brazil(2026, 11, 11, 14, 30);

        var reward = EntitlementClock.monthAfter(earnedInNovember, PROMO_END);

        assertEquals(brazil(2027, 1, 1, 0, 0), reward.startsAt());
        assertEquals(brazil(2027, 2, 1, 0, 0), reward.endsAt());
    }

    @Test
    void monthAfter_stackingTwoRewards_runsBackToBackOnCalendarMonths() {
        var now = brazil(2026, 11, 11, 14, 30);

        var first = EntitlementClock.monthAfter(now, PROMO_END);
        var second = EntitlementClock.monthAfter(now, first.endsAt());
        var third = EntitlementClock.monthAfter(now, second.endsAt());

        assertEquals(first.endsAt(), second.startsAt());
        assertEquals(brazil(2027, 3, 1, 0, 0), second.endsAt(), "February is a whole calendar month");
        assertEquals(brazil(2027, 4, 1, 0, 0), third.endsAt());
    }

    @Test
    void monthAfter_freeUserAfterThePromo_startsNow() {
        var now = brazil(2027, 3, 10, 9, 15);

        var withNoEntitlement = EntitlementClock.monthAfter(now, null);
        var withLapsedEntitlement = EntitlementClock.monthAfter(now, PROMO_END);

        assertEquals(now, withNoEntitlement.startsAt());
        assertEquals(brazil(2027, 4, 10, 9, 15), withNoEntitlement.endsAt());
        assertEquals(withNoEntitlement, withLapsedEntitlement);
    }

    @Test
    void monthAfter_paidSubscriber_queuesAfterThePaidPeriod() {
        var now = brazil(2027, 2, 20, 18, 0);
        var paidUntil = brazil(2027, 3, 10, 15, 42);

        var reward = EntitlementClock.monthAfter(now, paidUntil);

        assertEquals(paidUntil, reward.startsAt(), "never shortens or overlaps the paid period");
        assertEquals(brazil(2027, 4, 10, 15, 42), reward.endsAt());
    }

    @Test
    void monthAfter_clampsToTheLastDayOfAShorterMonth() {
        var now = brazil(2027, 1, 31, 10, 0);

        var reward = EntitlementClock.monthAfter(now, null);

        assertEquals(brazil(2027, 2, 28, 10, 0), reward.endsAt());
    }
}
