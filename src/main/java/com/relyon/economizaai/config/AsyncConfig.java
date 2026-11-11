package com.relyon.economizaai.config;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables {@code @Async} and provides the executor for receipt ingestion.
 *
 * <p>Receipt submit returns immediately with status PROCESSING; the slow part
 * (SEFAZ fetch + captcha solve + parse — up to a couple of minutes) runs on
 * this pool so the HTTP request never blocks the FE. The pool is small and
 * bounded: ingestion is I/O-bound on SEFAZ/CapSolver, not CPU, and we'd rather
 * queue a burst than spawn unbounded threads.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String RECEIPT_INGEST_EXECUTOR = "receiptIngestExecutor";

    @Bean(RECEIPT_INGEST_EXECUTOR)
    @Profile("!test")
    public Executor receiptIngestExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        // core == max: a ThreadPoolExecutor only grows past core once the queue is FULL,
        // so core 4 / max 8 ran 4 threads until 50 scans were already waiting.
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("receipt-ingest-");
        // Propagate the request's MDC (req=, user=) onto the ingest thread so
        // async ingestion log lines stay traceable per user — otherwise only the
        // rcpt= tag (set inside ingest) survives.
        executor.setTaskDecorator(runnable -> {
            var contextMap = MDC.getCopyOfContextMap();
            return () -> {
                if (contextMap != null) MDC.setContextMap(contextMap);
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }

    /**
     * Under the {@code test} profile run ingestion inline on the caller's thread
     * (and transaction), so {@code @SpringBootTest} flows are deterministic and
     * see the persisted result without cross-thread/commit timing.
     */
    @Bean(RECEIPT_INGEST_EXECUTOR)
    @Profile("test")
    public Executor receiptIngestExecutorSync() {
        return new SyncTaskExecutor();
    }

    public static final String AI_SWEEP_EXECUTOR = "aiSweepExecutor";

    /**
     * Single-threaded lane for the admin AI sweep — one sweep at a time by
     * design (it's an occasional, human-triggered batch), isolated from the
     * ingestion pool so a long sweep never delays receipt processing.
     */
    @Bean(AI_SWEEP_EXECUTOR)
    @Profile("!test")
    public Executor aiSweepExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("ai-sweep-");
        executor.initialize();
        return executor;
    }

    @Bean(AI_SWEEP_EXECUTOR)
    @Profile("test")
    public Executor aiSweepExecutorSync() {
        return new SyncTaskExecutor();
    }

    public static final String PHOTO_EXTRACT_EXECUTOR = "photoExtractExecutor";

    /**
     * Photo-receipt extraction (one vision call per photo, user waiting on the result).
     * Own lane so a burst of uploads queues here instead of being rejected behind an
     * admin sweep; a full queue still rejects, and the dispatch site fails the receipt.
     */
    @Bean(PHOTO_EXTRACT_EXECUTOR)
    @Profile("!test")
    public Executor photoExtractExecutor(
            @Value("${economizaai.async.photo-extract.threads:3}") int threads,
            @Value("${economizaai.async.photo-extract.queue-capacity:30}") int queueCapacity) {
        return boundedExecutor("photo-extract-", threads, queueCapacity);
    }

    @Bean(PHOTO_EXTRACT_EXECUTOR)
    @Profile("test")
    public Executor photoExtractExecutorSync() {
        return new SyncTaskExecutor();
    }

    public static final String AI_CATEGORIZATION_EXECUTOR = "aiCategorizationExecutor";

    /**
     * User-triggered categorization (per-ingest category suggestion + per-confirm item
     * fallback). Best-effort work: when the queue is full the dispatch site skips it and
     * the daily job covers the rest.
     */
    @Bean(AI_CATEGORIZATION_EXECUTOR)
    @Profile("!test")
    public Executor aiCategorizationExecutor(
            @Value("${economizaai.async.ai-categorization.threads:2}") int threads,
            @Value("${economizaai.async.ai-categorization.queue-capacity:50}") int queueCapacity) {
        return boundedExecutor("ai-categorize-", threads, queueCapacity);
    }

    @Bean(AI_CATEGORIZATION_EXECUTOR)
    @Profile("test")
    public Executor aiCategorizationExecutorSync() {
        return new SyncTaskExecutor();
    }

    /** Fixed-size pool (core == max, so the queue is only used once every thread is busy) that rejects when full. */
    static ThreadPoolTaskExecutor boundedExecutor(String threadNamePrefix, int threads, int queueCapacity) {
        var executor = new ThreadPoolTaskExecutor();
        var poolSize = Math.max(1, threads);
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setTaskDecorator(mdcPropagatingDecorator());
        executor.initialize();
        return executor;
    }

    /** Carries the submitting thread's MDC (req=, user=, rcpt=) onto the worker thread. */
    static TaskDecorator mdcPropagatingDecorator() {
        return runnable -> {
            var contextMap = MDC.getCopyOfContextMap();
            return () -> {
                if (contextMap != null) MDC.setContextMap(contextMap);
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }
}
