package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.enums.NotificationType;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuietHoursPolicyTest {

    private final QuietHoursPolicy nightly = new QuietHoursPolicy(true, 21, 8, 3);

    @Test
    void windowCrossesMidnight_startInclusiveEndExclusive() {
        assertFalse(nightly.isQuietAt(LocalTime.of(20, 59)));
        assertTrue(nightly.isQuietAt(LocalTime.of(21, 0)));
        assertTrue(nightly.isQuietAt(LocalTime.of(23, 30)));
        assertTrue(nightly.isQuietAt(LocalTime.of(0, 0)));
        assertTrue(nightly.isQuietAt(LocalTime.of(7, 59)));
        assertFalse(nightly.isQuietAt(LocalTime.of(8, 0)));
        assertFalse(nightly.isQuietAt(LocalTime.of(14, 0)));
    }

    @Test
    void windowWithinOneDay() {
        var siesta = new QuietHoursPolicy(true, 13, 15, 3);

        assertFalse(siesta.isQuietAt(LocalTime.of(12, 59)));
        assertTrue(siesta.isQuietAt(LocalTime.of(13, 0)));
        assertTrue(siesta.isQuietAt(LocalTime.of(14, 59)));
        assertFalse(siesta.isQuietAt(LocalTime.of(15, 0)));
    }

    @Test
    void disabledOrEmptyWindowIsNeverQuiet() {
        assertFalse(new QuietHoursPolicy(false, 21, 8, 3).isQuietAt(LocalTime.of(23, 0)));
        assertFalse(new QuietHoursPolicy(true, 8, 8, 3).isQuietAt(LocalTime.of(8, 0)));
    }

    @Test
    void receiptLifecycleTypesAreNeverHeldBack() {
        var night = LocalTime.of(23, 0);

        assertFalse(nightly.holdsBackAt(NotificationType.RECEIPT_READY, night));
        assertFalse(nightly.holdsBackAt(NotificationType.RECEIPT_FAILED, night));
        assertFalse(nightly.holdsBackAt(NotificationType.RECEIPT_RECOVERED, night));
    }

    @Test
    void everyOtherTypeIsHeldBackAtNightAndFreeDuringTheDay() {
        var night = LocalTime.of(23, 0);
        var day = LocalTime.of(10, 0);

        for (var type : NotificationType.values()) {
            if (type == NotificationType.RECEIPT_READY || type == NotificationType.RECEIPT_FAILED
                    || type == NotificationType.RECEIPT_RECOVERED) {
                continue;
            }
            assertTrue(nightly.holdsBackAt(type, night), type.name());
            assertFalse(nightly.holdsBackAt(type, day), type.name());
        }
    }

    @Test
    void exposesTheConfiguredCap() {
        assertEquals(3, nightly.getMaxDeferredPerUser());
    }
}
