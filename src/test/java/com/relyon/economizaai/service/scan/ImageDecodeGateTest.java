package com.relyon.economizaai.service.scan;

import com.relyon.economizaai.exception.ImageDecodeBusyException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageDecodeGateTest {

    @Test
    void runsTheWorkAndReturnsItsResult() {
        var gate = new ImageDecodeGate(1, 50);

        assertThat(gate.withPermit(() -> "decoded")).isEqualTo("decoded");
    }

    @Test
    void refusesWhenEveryPermitIsBusyBeyondTheWait() throws Exception {
        var gate = new ImageDecodeGate(1, 50);
        var permitHeld = new CountDownLatch(1);
        var releasePermit = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var holder = executor.submit(() -> gate.withPermit(() -> {
                permitHeld.countDown();
                awaitQuietly(releasePermit);
                return "first";
            }));
            assertThat(permitHeld.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> gate.withPermit(() -> "second"))
                    .isInstanceOf(ImageDecodeBusyException.class)
                    .extracting("messageKey").isEqualTo("image.decode.busy");

            releasePermit.countDown();
            assertThat(holder.get(2, TimeUnit.SECONDS)).isEqualTo("first");
            // The permit is back: the next upload goes through.
            assertThat(gate.withPermit(() -> "third")).isEqualTo("third");
        } finally {
            releasePermit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void releasesThePermitWhenTheWorkFails() {
        var gate = new ImageDecodeGate(1, 50);

        assertThatThrownBy(() -> gate.withPermit(() -> {
            throw new IllegalStateException("decode failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(gate.withPermit(() -> "next")).isEqualTo("next");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
