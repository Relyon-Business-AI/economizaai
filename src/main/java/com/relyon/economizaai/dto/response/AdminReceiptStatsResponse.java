package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;

/**
 * Aggregate for the admin Notas list under the current filters: how many notes
 * match and the sum of their values — shown as a header total on the screen.
 */
public record AdminReceiptStatsResponse(long count, BigDecimal totalAmount) {}
