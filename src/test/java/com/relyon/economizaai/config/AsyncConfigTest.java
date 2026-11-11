package com.relyon.economizaai.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AsyncConfigTest {

    @Test
    void boundedExecutor_rejectsOnceThreadsAndQueueAreFull() throws InterruptedException {
        var executor = AsyncConfig.boundedExecutor("test-lane-", 1, 1);
        var release = new CountDownLatch(1);
        var running = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                running.countDown();
                awaitQuietly(release);
            });
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> awaitQuietly(release)); // fills the single queue slot

            assertThrows(TaskRejectedException.class, () -> executor.execute(() -> { }));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void boundedExecutor_carriesTheSubmittersMdcToTheWorker() throws InterruptedException {
        var executor = AsyncConfig.boundedExecutor("test-lane-", 1, 1);
        var seenOnWorker = new AtomicReference<String>();
        var done = new CountDownLatch(1);
        MDC.put(MdcContextFilter.RECEIPT_ID, "abc12345");
        try {
            executor.execute(() -> {
                seenOnWorker.set(MDC.get(MdcContextFilter.RECEIPT_ID));
                done.countDown();
            });
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(seenOnWorker.get()).isEqualTo("abc12345");
        } finally {
            MDC.remove(MdcContextFilter.RECEIPT_ID);
            executor.shutdown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
