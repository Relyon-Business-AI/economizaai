package com.relyon.economizaai.service.notifications.schedule;

import com.relyon.economizaai.time.BrazilClock;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lets a job that is woken every full hour decide whether the send time set in the admin is
 * now (Brasília). The time is read at each tick, so a change takes effect at the next full
 * hour, with no restart. One gate belongs to one job and lets a given hour through only once.
 */
public class HourlySendGate {

    /** Every full hour — the tick of every job with an admin-defined send time. */
    public static final String EVERY_FULL_HOUR = "0 0 * * * *";
    public static final String ZONE = "America/Sao_Paulo";

    private static final int HALF_HOUR_MINUTES = 30;

    private final AtomicReference<LocalDateTime> lastHourLetThrough = new AtomicReference<>();

    /** Every day at {@code hour}. */
    public boolean claimDaily(ZonedDateTime now, int hour) {
        var tickHour = tickHourOf(now);
        return tickHour.getHour() == hour && claim(tickHour);
    }

    /** Every day at each of {@code hours}. */
    public boolean claimDailyAtAnyOf(ZonedDateTime now, Collection<Integer> hours) {
        var tickHour = tickHourOf(now);
        return hours.contains(tickHour.getHour()) && claim(tickHour);
    }

    /** Every week on {@code weekday} at {@code hour}. */
    public boolean claimWeekly(ZonedDateTime now, DayOfWeek weekday, int hour) {
        var tickHour = tickHourOf(now);
        return tickHour.getDayOfWeek() == weekday && tickHour.getHour() == hour && claim(tickHour);
    }

    /** Every month on {@code dayOfMonth} at {@code hour}. */
    public boolean claimMonthly(ZonedDateTime now, int dayOfMonth, int hour) {
        var tickHour = tickHourOf(now);
        return tickHour.getDayOfMonth() == dayOfMonth && tickHour.getHour() == hour && claim(tickHour);
    }

    /**
     * The full hour this tick stands for, in Brasília. A tick that fires a moment early or
     * late still belongs to the hour it was scheduled for, so the nearest full hour is used.
     */
    private static LocalDateTime tickHourOf(ZonedDateTime now) {
        return now.withZoneSameInstant(BrazilClock.ZONE).toLocalDateTime()
                .plusMinutes(HALF_HOUR_MINUTES).truncatedTo(ChronoUnit.HOURS);
    }

    private boolean claim(LocalDateTime tickHour) {
        return !tickHour.equals(lastHourLetThrough.getAndSet(tickHour));
    }
}
