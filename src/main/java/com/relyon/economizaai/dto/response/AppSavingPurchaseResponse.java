package com.relyon.economizaai.dto.response;

import com.relyon.economizaai.model.enums.AppSavingSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One purchase that came out cheaper after the app showed its price. {@code paidPrice} and
 * {@code previousPrice} are unit prices (this purchase and the household's previous one);
 * {@code saved} is the difference times the quantity bought; {@code percent} is how much
 * cheaper the unit price was. {@code source} is the machine key of where the price was seen
 * and {@code sourceLabel} its localized text.
 */
public record AppSavingPurchaseResponse(
        UUID id,
        UUID productId,
        String productName,
        String marketCnpj,
        String marketName,
        String marketFriendlyName,
        AppSavingSource source,
        String sourceLabel,
        BigDecimal paidPrice,
        BigDecimal previousPrice,
        BigDecimal saved,
        BigDecimal percent,
        LocalDateTime recordedAt
) {}
