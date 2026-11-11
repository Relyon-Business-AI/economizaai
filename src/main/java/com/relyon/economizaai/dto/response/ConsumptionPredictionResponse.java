package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.ProductCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Per-product purchase prediction for the household.
 *
 * @param daysUntilNextPurchase negative if estimated next purchase is already past
 * @param confidence            coarse signal so the FE can down-weight low-sample estimates
 * @param status                {@code RAN_OUT} (overdue), {@code RUNNING_LOW} (within threshold),
 *                              or {@code OK} (informational)
 * @param typeLabel             product's generic type ("biscoito", "salame") — drives the FE icon
 * @param categorySlug          taxonomy leaf slug, a second hint for the FE icon (nullable)
 */
public record ConsumptionPredictionResponse(
        UUID productId,
        String productName,
        String friendlyDescription,
        ProductCategory category,
        LocalDate lastPurchaseDate,
        LocalDate predictedNextPurchaseDate,
        long daysUntilNextPurchase,
        BigDecimal averageIntervalDays,
        BigDecimal averageQuantityPerPurchase,
        int sampleSize,
        Confidence confidence,
        Status status,
        String typeLabel,
        String categorySlug
) {
    public enum Confidence { LOW, MEDIUM, HIGH }
    public enum Status { OK, RUNNING_LOW, RAN_OUT }

    /** Pre-icon shape, kept for callers that don't resolve the product type. */
    public ConsumptionPredictionResponse(UUID productId, String productName, String friendlyDescription,
                                         ProductCategory category, LocalDate lastPurchaseDate,
                                         LocalDate predictedNextPurchaseDate, long daysUntilNextPurchase,
                                         BigDecimal averageIntervalDays, BigDecimal averageQuantityPerPurchase,
                                         int sampleSize, Confidence confidence, Status status) {
        this(productId, productName, friendlyDescription, category, lastPurchaseDate, predictedNextPurchaseDate,
                daysUntilNextPurchase, averageIntervalDays, averageQuantityPerPurchase, sampleSize, confidence,
                status, null, null);
    }
}
