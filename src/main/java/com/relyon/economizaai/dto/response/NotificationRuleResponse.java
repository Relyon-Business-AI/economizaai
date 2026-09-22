package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.NotificationRule;
import com.relyon.economizaai.model.enums.NotificationChannel;
import com.relyon.economizaai.model.enums.NotificationType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationRuleResponse(
        UUID id,
        NotificationType type,
        UUID productId,
        String productName,
        String friendlyDescription,
        BigDecimal thresholdPrice,
        Double radiusKm,
        Integer leadTimeDays,
        NotificationChannel channel,
        boolean active,
        boolean isDefault,
        LocalDateTime lastFiredAt,
        LocalDateTime createdAt
) {
    /** @param friendlyDescription the household's own rename of the rule's product, null when never renamed */
    public static NotificationRuleResponse from(NotificationRule rule, String friendlyDescription) {
        return new NotificationRuleResponse(
                rule.getId(),
                rule.getType(),
                rule.getProduct() != null ? rule.getProduct().getId() : null,
                rule.getProduct() != null ? rule.getProduct().getNormalizedName() : null,
                friendlyDescription,
                rule.getThresholdPrice(),
                rule.getRadiusKm(),
                rule.getLeadTimeDays(),
                rule.getChannel(),
                rule.isActive(),
                rule.isDefault(),
                rule.getLastFiredAt(),
                rule.getCreatedAt());
    }
}
