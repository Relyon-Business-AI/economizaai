package com.relyon.economizaai.service.monitoring;

import com.relyon.economizaai.service.ContactService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.management.MemoryUsage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HeapPressureMonitorTest {

    private static final long MAX_HEAP_BYTES = 1000;
    private static final long COOLDOWN_MINUTES = 360;

    @Mock private ContactService contactService;

    private final AtomicReference<MemoryUsage> currentHeapUsage = new AtomicReference<>();
    private final AtomicReference<Instant> currentInstant = new AtomicReference<>(Instant.parse("2026-09-30T12:00:00Z"));
    private HeapPressureMonitor monitor;

    @BeforeEach
    void setUp() {
        var mutableClock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return currentInstant.get(); }
        };
        monitor = new HeapPressureMonitor(contactService, Runnable::run, 0.90, 3, COOLDOWN_MINUTES,
                currentHeapUsage::get, mutableClock);
    }

    private void tickWithUsedHeap(long usedBytes) {
        currentHeapUsage.set(new MemoryUsage(0, usedBytes, usedBytes, MAX_HEAP_BYTES));
        monitor.checkHeap();
    }

    @Test
    void belowThresholdNeverAlerts() {
        tickWithUsedHeap(500);
        tickWithUsedHeap(800);
        tickWithUsedHeap(890);

        verify(contactService, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void alertsAfterConsecutiveTicksAboveThreshold() {
        tickWithUsedHeap(950);
        tickWithUsedHeap(950);
        verify(contactService, never()).notifyAdmin(anyString(), anyString());

        tickWithUsedHeap(950);
        verify(contactService).notifyAdmin(contains("heap"), contains("95"));
    }

    @Test
    void dipBelowThresholdResetsTheConsecutiveCount() {
        tickWithUsedHeap(950);
        tickWithUsedHeap(950);
        tickWithUsedHeap(500);
        tickWithUsedHeap(950);
        tickWithUsedHeap(950);

        verify(contactService, never()).notifyAdmin(anyString(), anyString());
    }

    @Test
    void cooldownSuppressesRepeatAlertsUntilItExpires() {
        tickWithUsedHeap(950);
        tickWithUsedHeap(950);
        tickWithUsedHeap(950);
        tickWithUsedHeap(950);
        tickWithUsedHeap(950);
        verify(contactService, times(1)).notifyAdmin(anyString(), anyString());

        currentInstant.updateAndGet(instant -> instant.plus(Duration.ofMinutes(COOLDOWN_MINUTES + 1)));
        tickWithUsedHeap(950);
        verify(contactService, times(2)).notifyAdmin(anyString(), anyString());
    }

    @Test
    void undefinedMaxHeapIsIgnored() {
        currentHeapUsage.set(new MemoryUsage(0, 500, 500, -1));
        monitor.checkHeap();

        verify(contactService, never()).notifyAdmin(anyString(), anyString());
    }
}
