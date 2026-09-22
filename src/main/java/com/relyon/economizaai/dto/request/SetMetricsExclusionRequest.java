package com.relyon.economizaai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Toggle whether a user is excluded from ALL admin metrics (funnel, channels,
 * subscriptions, revenue). Used to hide store-review / robo test accounts without
 * deleting them.
 */
public record SetMetricsExclusionRequest(
        @Schema(description = "true = hide this account from every metric; false = count it again.", example = "true")
        @NotNull Boolean excluded) {
}
