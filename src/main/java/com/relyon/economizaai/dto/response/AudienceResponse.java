package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.NotificationAudience;
import com.relyon.economizaai.model.enums.Role;
import com.relyon.economizaai.model.enums.SubscriptionTier;

import java.time.LocalDateTime;
import java.util.UUID;

/** Admin view of an audience: its filters plus the LIVE count of matching users. */
public record AudienceResponse(
        UUID id,
        String name,
        String description,
        boolean builtIn,
        Role role,
        SubscriptionTier subscriptionTier,
        String locale,
        Boolean hasPushToken,
        Integer registeredWithinDays,
        Integer activeWithinDays,
        long matchCount,
        LocalDateTime createdAt
) {
    public static AudienceResponse from(NotificationAudience audience, long matchCount) {
        return new AudienceResponse(
                audience.getId(),
                audience.getName(),
                audience.getDescription(),
                audience.isBuiltIn(),
                audience.getRole(),
                audience.getSubscriptionTier(),
                audience.getLocale(),
                audience.getHasPushToken(),
                audience.getRegisteredWithinDays(),
                audience.getActiveWithinDays(),
                matchCount,
                audience.getCreatedAt());
    }
}
