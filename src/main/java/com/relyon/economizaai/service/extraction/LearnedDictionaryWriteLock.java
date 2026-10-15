package com.relyon.economizaai.service.extraction;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes read-modify-write upserts into {@code learned_dictionary} across the two
 * promotion services ({@link AutoPromotionService}, {@link ConsensusPromotionService}).
 * The scheduler is single-threaded so the nightly jobs never overlap each other, but an
 * admin can trigger a promotion endpoint on an HTTP thread while a scheduled run is in
 * flight — two concurrent upserts of the same token would hit the {@code normalized_token}
 * UNIQUE. A single process-wide lock (prod runs one instance) keeps them ordered.
 */
@Component
public class LearnedDictionaryWriteLock {

    private final ReentrantLock lock = new ReentrantLock();

    public void runExclusively(Runnable write) {
        lock.lock();
        try {
            write.run();
        } finally {
            lock.unlock();
        }
    }
}
