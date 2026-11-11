package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.enums.NotificationType;
import com.relyon.economizaai.time.BrazilClock;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * The nightly window (Brasília time) in which nothing is pushed or e-mailed to a
 * user. A notification raised inside it still lands in the inbox right away; its
 * outbound dispatch waits for the end of the window.
 *
 * <p>Exempt: the receipt-lifecycle pushes — they answer something the user just
 * did and is waiting on.
 */
@Component
public class QuietHoursPolicy {

    private static final Set<NotificationType> ALWAYS_IMMEDIATE = EnumSet.of(
            NotificationType.RECEIPT_READY, NotificationType.RECEIPT_FAILED, NotificationType.RECEIPT_RECOVERED);

    private final boolean enabled;
    private final int startHour;
    private final int endHour;
    /** How many held-back notifications one user gets pushed when the window ends; the rest stay inbox-only. */
    @Getter
    private final int maxDeferredPerUser;

    public QuietHoursPolicy(
            @Value("${economizaai.notifications.quiet-hours.enabled:true}") boolean enabled,
            @Value("${economizaai.notifications.quiet-hours.start-hour:21}") int startHour,
            @Value("${economizaai.notifications.quiet-hours.end-hour:8}") int endHour,
            @Value("${economizaai.notifications.quiet-hours.max-deferred-per-user:3}") int maxDeferredPerUser) {
        this.enabled = enabled;
        this.startHour = startHour;
        this.endHour = endHour;
        this.maxDeferredPerUser = maxDeferredPerUser;
    }

    public boolean isQuietNow() {
        return isQuietAt(BrazilClock.nowDateTime().toLocalTime());
    }

    /** True from {@code startHour}:00 (inclusive) to {@code endHour}:00 (exclusive); the window may cross midnight. */
    public boolean isQuietAt(LocalTime brasiliaTime) {
        if (!enabled || startHour == endHour) return false;
        var hour = brasiliaTime.getHour();
        return startHour < endHour
                ? hour >= startHour && hour < endHour
                : hour >= startHour || hour < endHour;
    }

    /** Whether a notification of this type raised right now must wait for the end of the window. */
    public boolean holdsBackNow(NotificationType type) {
        return holdsBackAt(type, BrazilClock.nowDateTime().toLocalTime());
    }

    public boolean holdsBackAt(NotificationType type, LocalTime brasiliaTime) {
        return !ALWAYS_IMMEDIATE.contains(type) && isQuietAt(brasiliaTime);
    }
}
