package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.NotificationRule;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Backward-compatible view of a PRICE_DROP {@link NotificationRule} for the
 * legacy {@code /api/v1/alerts} ("avise-me quando") endpoints.
 */
public record PriceAlertResponse(
        UUID id,
        UUID productId,
        String productName,
        String friendlyDescription,
        BigDecimal thresholdPrice,
        Double radiusKm,
        boolean active,
        LocalDateTime lastFiredAt,
        LocalDateTime createdAt
) {
    /** @param friendlyDescription the household's own rename of the alert's product, null when never renamed */
    public static PriceAlertResponse from(NotificationRule rule, String friendlyDescription) {
        return new PriceAlertResponse(
                rule.getId(),
                rule.getProduct() != null ? rule.getProduct().getId() : null,
                rule.getProduct() != null ? rule.getProduct().getNormalizedName() : null,
                friendlyDescription,
                rule.getThresholdPrice(),
                rule.getRadiusKm(),
                rule.isActive(),
                rule.getLastFiredAt(),
                rule.getCreatedAt());
    }
}
