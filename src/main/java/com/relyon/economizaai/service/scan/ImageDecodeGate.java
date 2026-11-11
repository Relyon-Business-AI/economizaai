package com.relyon.economizaai.service.scan;

import com.relyon.economizaai.exception.ImageDecodeBusyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * One global bound on how many uploaded images are decoded/processed at the same time.
 * A decoded photo is a full raster in the heap (tens of MB for a phone picture), and the
 * decode + OCR passes are CPU-heavy, so every full-image decode in the app — receipt
 * photos, QR photos, chave OCR, profile pictures — runs under a permit from here.
 *
 * <p>When no permit frees up within a short wait the caller gets
 * {@link ImageDecodeBusyException} (localized 503, "try again in a moment") instead of
 * piling more rasters onto the heap. NOT reentrant: take one permit per upload and do
 * all the work for that upload inside it.
 */
@Slf4j
@Component
public class ImageDecodeGate {

    private final Semaphore permits;
    private final long maxWaitMs;

    public ImageDecodeGate(@Value("${economizaai.image-decode.max-concurrent:3}") int maxConcurrent,
                           @Value("${economizaai.image-decode.max-wait-ms:5000}") long maxWaitMs) {
        this.permits = new Semaphore(Math.max(1, maxConcurrent), true);
        this.maxWaitMs = maxWaitMs;
    }

    public <T> T withPermit(Supplier<T> imageWork) {
        acquirePermit();
        try {
            return imageWork.get();
        } finally {
            permits.release();
        }
    }

    private void acquirePermit() {
        try {
            if (!permits.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS)) {
                log.warn("image_decode.busy waited_ms={} queued={}", maxWaitMs, permits.getQueueLength());
                throw new ImageDecodeBusyException();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ImageDecodeBusyException();
        }
    }
}
