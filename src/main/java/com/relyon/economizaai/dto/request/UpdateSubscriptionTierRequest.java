package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.SubscriptionTier;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Admin set of a user's subscription tier (testing / promos / ops). Setting PRO
 * activates a manual subscription; setting FREE cancels it.
 */
public record UpdateSubscriptionTierRequest(
        @Schema(description = "Target tier.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull SubscriptionTier tier
) {}
