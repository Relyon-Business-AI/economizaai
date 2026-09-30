package com.relyon.economizaai.time;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Single resolution point for the product's wall-clock timezone. The app serves
 * Brazil, so every user-facing day/month window (monthly caps, dashboards,
 * lookbacks, leaderboards) must follow Brasília time — never the host's default
 * zone (the servers run UTC, which flips "today" at 21:00 Brasília). If per-user
 * timezones ever become a requirement, this is the one place to change.
 */
public final class BrazilClock {

    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private BrazilClock() {
    }

    /** Today's calendar date in Brasília. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** Current Brasília wall-clock date-time. */
    public static LocalDateTime nowDateTime() {
        return LocalDateTime.now(ZONE);
    }

    /** Current calendar month in Brasília. */
    public static YearMonth currentYearMonth() {
        return YearMonth.now(ZONE);
    }

    /** Current instant carrying the Brasília offset. */
    public static OffsetDateTime nowOffset() {
        return OffsetDateTime.now(ZONE);
    }

    /** Current zoned date-time in Brasília. */
    public static ZonedDateTime now() {
        return ZonedDateTime.now(ZONE);
    }
}
