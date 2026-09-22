package com.relyon.economizaai.service.extraction.ml;

/**
 * Result of an ML inference. Confidence is in [0, 1] — the share of
 * total probability mass the winning label captured.
 */
public record MlPrediction<T>(T label, double confidence) {

    public static <T> MlPrediction<T> empty() {
        return new MlPrediction<>(null, 0.0);
    }

    public boolean isConfident(double threshold) {
        return label != null && confidence >= threshold;
    }
}
