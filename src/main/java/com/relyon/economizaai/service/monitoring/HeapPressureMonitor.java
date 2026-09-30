package com.relyon.economizaai.service.monitoring;

import com.relyon.economizaai.config.AsyncConfig;
import com.relyon.economizaai.service.ContactService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Heap watchdog: warns the admin BEFORE the JVM hits OutOfMemoryError (which
 * {@code -XX:+ExitOnOutOfMemoryError} in the Dockerfile turns into a restart).
 * Sustained usage above the threshold for N consecutive ticks logs
 * {@code heap.pressure} at ERROR and dispatches ONE admin alert e-mail on the
 * async pool (never blocks the scheduler thread on SMTP), rate-limited by a
 * cooldown so a long incident can't flood the inbox.
 */
@Slf4j
@Service
public class HeapPressureMonitor {

    private static final long BYTES_PER_MB = 1024 * 1024;

    private final ContactService contactService;
    private final Executor executor;
    private final Supplier<MemoryUsage> heapUsageSupplier;
    private final Clock clock;
    private final double alertThreshold;
    private final int requiredConsecutiveTicks;
    private final Duration alertCooldown;

    private int consecutiveTicksAboveThreshold;
    private Instant lastAlertAt;

    @Autowired
    public HeapPressureMonitor(ContactService contactService,
                               @Qualifier(AsyncConfig.RECEIPT_INGEST_EXECUTOR) Executor executor,
                               @Value("${economizaai.monitoring.heap-alert-threshold:0.90}") double alertThreshold,
                               @Value("${economizaai.monitoring.heap-alert-consecutive-ticks:3}") int requiredConsecutiveTicks,
                               @Value("${economizaai.monitoring.heap-alert-cooldown-minutes:360}") long alertCooldownMinutes) {
        this(contactService, executor, alertThreshold, requiredConsecutiveTicks, alertCooldownMinutes,
                ManagementFactory.getMemoryMXBean()::getHeapMemoryUsage, Clock.systemUTC());
    }

    HeapPressureMonitor(ContactService contactService, Executor executor, double alertThreshold,
                        int requiredConsecutiveTicks, long alertCooldownMinutes,
                        Supplier<MemoryUsage> heapUsageSupplier, Clock clock) {
        this.contactService = contactService;
        this.executor = executor;
        this.alertThreshold = alertThreshold;
        this.requiredConsecutiveTicks = requiredConsecutiveTicks;
        this.alertCooldown = Duration.ofMinutes(alertCooldownMinutes);
        this.heapUsageSupplier = heapUsageSupplier;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${economizaai.monitoring.heap-check-delay-ms:60000}")
    public void checkHeap() {
        var heapUsage = heapUsageSupplier.get();
        if (heapUsage.getMax() <= 0) return;

        var usedRatio = (double) heapUsage.getUsed() / heapUsage.getMax();
        if (usedRatio < alertThreshold) {
            consecutiveTicksAboveThreshold = 0;
            return;
        }

        consecutiveTicksAboveThreshold++;
        var usedMb = heapUsage.getUsed() / BYTES_PER_MB;
        var maxMb = heapUsage.getMax() / BYTES_PER_MB;
        var usedPercent = Math.round(usedRatio * 100);
        if (consecutiveTicksAboveThreshold < requiredConsecutiveTicks) {
            log.warn("heap.pressure_building used_mb={} max_mb={} pct={} tick={}/{}",
                    usedMb, maxMb, usedPercent, consecutiveTicksAboveThreshold, requiredConsecutiveTicks);
            return;
        }

        log.error("heap.pressure used_mb={} max_mb={} pct={}", usedMb, maxMb, usedPercent);
        dispatchAlertIfOutsideCooldown(usedMb, maxMb, usedPercent);
    }

    private void dispatchAlertIfOutsideCooldown(long usedMb, long maxMb, long usedPercent) {
        var now = clock.instant();
        if (lastAlertAt != null && now.isBefore(lastAlertAt.plus(alertCooldown))) return;
        lastAlertAt = now;

        var subject = "Pressão de heap na API (" + usedPercent + "%)";
        var body = """
                O heap da JVM está acima do limite de alerta há %d verificações consecutivas.

                - Heap usado: %d MB
                - Heap máximo: %d MB
                - Uso: %d%%
                - Quando: %s

                Se o consumo não cair, a JVM vai estourar OutOfMemoryError e reiniciar
                (-XX:+ExitOnOutOfMemoryError). Vale investigar o que está segurando memória.
                """.formatted(consecutiveTicksAboveThreshold, usedMb, maxMb, usedPercent, now);
        try {
            executor.execute(() -> contactService.notifyAdmin(subject, body));
            log.info("heap.alert_dispatched pct={}", usedPercent);
        } catch (RejectedExecutionException ex) {
            log.warn("heap.alert_dispatch_rejected pct={}", usedPercent);
        }
    }
}
