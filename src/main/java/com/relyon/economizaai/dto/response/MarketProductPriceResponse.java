package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.ProductCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One product's latest community price at a store (market screen). Deliberately carries nothing
 * that could tie the price back to a purchase: no household/user/receipt, no quantity, no sample
 * or household counts, and the observation moment only as a DAY.
 */
public record MarketProductPriceResponse(
        UUID productId,
        String productName,
        ProductCategory category,
        BigDecimal latestPrice,
        LocalDate observedOn
) {
}
