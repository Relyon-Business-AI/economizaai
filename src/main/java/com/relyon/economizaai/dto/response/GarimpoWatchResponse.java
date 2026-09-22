package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.GarimpoWatch;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record GarimpoWatchResponse(
        UUID id,
        String searchTerm,
        String marketplace,
        BigDecimal targetPrice,
        Integer minDiscountPercent,
        boolean active,
        LocalDateTime lastRunAt,
        String createdBy,
        LocalDateTime createdAt) {

    public static GarimpoWatchResponse from(GarimpoWatch watch) {
        return new GarimpoWatchResponse(
                watch.getId(),
                watch.getSearchTerm(),
                watch.getProvider(),
                watch.getTargetPrice(),
                watch.getMinDiscountPercent(),
                watch.isActive(),
                watch.getLastRunAt(),
                watch.getCreatedBy(),
                watch.getCreatedAt());
    }
}
