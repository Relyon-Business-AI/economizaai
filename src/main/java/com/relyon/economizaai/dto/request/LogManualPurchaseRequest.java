package com.relyon.economizaai.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record LogManualPurchaseRequest(
        @NotNull
        UUID productId,
        @NotNull
        @DecimalMin(value = "0.001", message = "quantity must be > 0")
        BigDecimal quantity,
        LocalDateTime purchasedAt
) {}
