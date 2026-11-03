package com.relyon.economizaai.dto.response;

/**
 * Whether the app may show the native store-rating dialog right now. {@code reason} is a
 * machine key for telemetry/debugging ({@code ok}, {@code not_enough_receipts},
 * {@code recent_failure}, {@code max_prompts_reached}, {@code cooldown}) — never rendered.
 */
public record StoreReviewPromptResponse(boolean eligible, String reason) {

    public static StoreReviewPromptResponse allowed() {
        return new StoreReviewPromptResponse(true, "ok");
    }

    public static StoreReviewPromptResponse blocked(String reason) {
        return new StoreReviewPromptResponse(false, reason);
    }
}
