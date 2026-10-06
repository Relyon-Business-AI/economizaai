package com.relyon.economizaai.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One sponsored (merchant-announced) promo in the consumer feed. ALWAYS
 * rendered with the "Patrocinado" badge — and never mixed into the organic,
 * data-driven promo/cheapest rankings (MONETIZATION.md §4 constraint).
 * {@code verified} = real scanned receipts at the chain confirm the price.
 */
public record SponsoredPromoResponse(
        UUID promoId,
        String cnpjRoot,
        String chainName,
        String ean,
        UUID productId,
        String productName,
        String description,
        BigDecimal promoPrice,
        BigDecimal regularPrice,
        LocalDate startsAt,
        LocalDate endsAt,
        boolean verified) {}
