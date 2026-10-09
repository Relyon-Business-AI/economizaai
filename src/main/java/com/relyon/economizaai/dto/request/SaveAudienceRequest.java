package com.relyon.economizaai.dto.request;

import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Create/update payload for a notification audience. Null filter = "don't filter on this". */
public record SaveAudienceRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description,
        Role role,
        SubscriptionTier subscriptionTier,
        @Pattern(regexp = "pt|en") String locale,
        Boolean hasPushToken,
        @Min(1) @Max(3650) Integer registeredWithinDays,
        @Min(1) @Max(3650) Integer activeWithinDays
) {}
