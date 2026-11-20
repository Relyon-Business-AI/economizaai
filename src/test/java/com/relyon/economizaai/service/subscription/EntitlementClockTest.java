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
    void monthsAfter_promoHolder_startsCountingOnJanuaryFirst() {
        var earnedInNovember = brazil(2026, 11, 11, 14, 30);

        var reward = EntitlementClock.monthsAfter(earnedInNovember, PROMO_END, 1);

        assertEquals(brazil(2027, 1, 1, 0, 0), reward.startsAt());
        assertEquals(brazil(2027, 2, 1, 0, 0), reward.endsAt());
    }

    @Test
    void monthsAfter_stackingTwoRewards_runsBackToBackOnCalendarMonths() {
        var now = brazil(2026, 11, 11, 14, 30);

        var first = EntitlementClock.monthsAfter(now, PROMO_END, 1);
        var second = EntitlementClock.monthsAfter(now, first.endsAt(), 1);
        var third = EntitlementClock.monthsAfter(now, second.endsAt(), 1);

        assertEquals(first.endsAt(), second.startsAt());
        assertEquals(brazil(2027, 3, 1, 0, 0), second.endsAt(), "February is a whole calendar month");
        assertEquals(brazil(2027, 4, 1, 0, 0), third.endsAt());
    }

    @Test
    void monthsAfter_spansAsManyCalendarMonthsAsTheRewardIsWorth() {
        var reward = EntitlementClock.monthsAfter(brazil(2026, 11, 11, 14, 30), PROMO_END, 3);

        assertEquals(brazil(2027, 1, 1, 0, 0), reward.startsAt());
        assertEquals(brazil(2027, 4, 1, 0, 0), reward.endsAt());
    }

    @Test
    void monthsOf_tellsHowManyMonthsAWindowWasGranted_evenWhenTheMonthIsShorter() {
        var endOfJanuary = brazil(2027, 1, 31, 10, 0);

        for (var months = 1; months <= 12; months++) {
            var window = EntitlementClock.monthsAfter(endOfJanuary, null, months);
            assertEquals(months, EntitlementClock.monthsOf(window.startsAt(), window.endsAt()));
        }
    }

    @Test
    void monthsAfter_freeUserAfterThePromo_startsNow() {
        var now = brazil(2027, 3, 10, 9, 15);

        var withNoEntitlement = EntitlementClock.monthsAfter(now, null, 1);
        var withLapsedEntitlement = EntitlementClock.monthsAfter(now, PROMO_END, 1);

        assertEquals(now, withNoEntitlement.startsAt());
        assertEquals(brazil(2027, 4, 10, 9, 15), withNoEntitlement.endsAt());
        assertEquals(withNoEntitlement, withLapsedEntitlement);
    }

    @Test
    void monthsAfter_paidSubscriber_queuesAfterThePaidPeriod() {
        var now = brazil(2027, 2, 20, 18, 0);
        var paidUntil = brazil(2027, 3, 10, 15, 42);

        var reward = EntitlementClock.monthsAfter(now, paidUntil, 1);

        assertEquals(paidUntil, reward.startsAt(), "never shortens or overlaps the paid period");
        assertEquals(brazil(2027, 4, 10, 15, 42), reward.endsAt());
    }

    @Test
    void monthsAfter_clampsToTheLastDayOfAShorterMonth() {
        var now = brazil(2027, 1, 31, 10, 0);

        var reward = EntitlementClock.monthsAfter(now, null, 1);

        assertEquals(brazil(2027, 2, 28, 10, 0), reward.endsAt());
    }
}
