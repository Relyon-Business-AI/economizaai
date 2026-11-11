package com.relyon.economizaai.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncConfigTest {

    @Test
    void receiptIngestExecutor_shutdownWaitsForAnInFlightIngest() throws InterruptedException {
        var executor = (ThreadPoolTaskExecutor) new AsyncConfig().receiptIngestExecutor(30);
        var ingestStarted = new CountDownLatch(1);
        var ingestFinished = new AtomicBoolean(false);
        executor.execute(() -> {
            ingestStarted.countDown();
            try {
                Thread.sleep(400);
                ingestFinished.set(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(ingestStarted.await(5, TimeUnit.SECONDS));

        executor.destroy(); // what the context does on shutdown

        assertTrue(ingestFinished.get(), "shutdown must wait for the running ingest instead of interrupting it");
    }

    @Test
    void receiptIngestExecutor_shutdownAlsoDrainsQueuedIngests() {
        var executor = (ThreadPoolTaskExecutor) new AsyncConfig().receiptIngestExecutor(30);
        var completed = new CountDownLatch(12); // more tasks than the 8 threads → some wait in the queue
        for (var task = 0; task < 12; task++) {
            executor.execute(() -> {
                try {
                    Thread.sleep(150);
                    completed.countDown();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        executor.destroy();

        assertTrue(completed.getCount() == 0, "queued ingests must run before the pool terminates");
    }
}
