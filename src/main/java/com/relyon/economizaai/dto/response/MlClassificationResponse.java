package com.relyon.economizaai.dto.response;

/**
 * The ML model's raw prediction for a description — the model ALONE, ignoring
 * the dictionary and regardless of whether ML is currently applied in the live
 * cascade. Developer tool for inspecting/improving the model.
 */
public record MlClassificationResponse(
        String input,
        Guess category,
        Guess genericName,
        boolean ready,
        double confidenceThreshold
) {
    /** {@code meetsThreshold} = the model is confident enough that this would be applied if ML were on. */
    public record Guess(String label, Double confidence, boolean meetsThreshold) {}
}
