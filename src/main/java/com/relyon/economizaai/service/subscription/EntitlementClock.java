package com.relyon.economizaai.service.subscription;

import com.relyon.economizaai.time.BrazilClock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Date arithmetic for Premium entitlements. Period ends are stored as host wall-clock
 * {@link LocalDateTime} (the convention of every timestamp in this codebase), but the product
 * thinks in Brasília calendar days — "Premium until 31/12" means the end of that day in Brasília.
 * Every conversion between the two lives here.
 */
public final class EntitlementClock {

    private static final LocalTime LAST_MINUTE_OF_DAY = LocalTime.of(23, 59);

    private EntitlementClock() {
    }

    /** A {@code [startsAt, endsAt)} window in host wall-clock time. */
    public record Window(LocalDateTime startsAt, LocalDateTime endsAt) {}

    /** Host wall-clock instant of the LAST second of the given Brasília day. */
    public static LocalDateTime endOfBrazilDay(LocalDate brazilDay) {
        return toHost(brazilDay.atTime(23, 59, 59).atZone(BrazilClock.ZONE));
    }

    /** The Brasília calendar day a stored host wall-clock instant falls on. */
    public static LocalDate brazilDayOf(LocalDateTime hostDateTime) {
        return toBrazil(hostDateTime).toLocalDate();
    }

    /**
     * One calendar month of Premium queued after the user's current entitlement. It starts when
     * that entitlement ends (or now, when the user has none left), and an entitlement that runs to
     * the end of a Brasília day hands over at the next midnight — so a month earned during a promo
     * that ends 31/12 runs 01/01 → 01/02, and a second one stacks 01/02 → 01/03.
     */
    public static Window monthAfter(LocalDateTime now, LocalDateTime currentEntitlementEnd) {
        var handover = currentEntitlementEnd == null || currentEntitlementEnd.isBefore(now)
                ? toBrazil(now)
                : startOfNextDayWhenDayIsOver(toBrazil(currentEntitlementEnd));
        return new Window(toHost(handover), toHost(handover.plusMonths(1)));
    }

    private static ZonedDateTime startOfNextDayWhenDayIsOver(ZonedDateTime brazilDateTime) {
        if (brazilDateTime.toLocalTime().isBefore(LAST_MINUTE_OF_DAY)) {
            return brazilDateTime;
        }
        return brazilDateTime.toLocalDate().plusDays(1).atStartOfDay(BrazilClock.ZONE);
    }

    private static ZonedDateTime toBrazil(LocalDateTime hostDateTime) {
        return hostDateTime.atZone(ZoneId.systemDefault()).withZoneSameInstant(BrazilClock.ZONE);
    }

    private static LocalDateTime toHost(ZonedDateTime brazilDateTime) {
        return brazilDateTime.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }
}
