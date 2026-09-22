package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A product the household has actually bought (confirmed, non-excluded
 * purchases), with the household's own most-recent purchase context.
 * {@code friendlyName} is the household's own rename of the product
 * (household_product_aliases), null when never renamed.
 */
public record HouseholdProductResponse(
        UUID productId,
        String name,
        String friendlyName,
        String brand,
        String category,
        long timesBought,
        LocalDateTime lastBoughtAt,
        BigDecimal lastUnitPrice,
        String lastMarketCnpj,
        String lastMarketName,
        String lastMarketFriendlyName
) {}
