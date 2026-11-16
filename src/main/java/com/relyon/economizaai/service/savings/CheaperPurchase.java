package com.relyon.economizaai.service.savings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.NotificationEvent;
import com.relyon.economizaai.model.enums.AppSavingSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.UUID;

/**
 * A purchase attributed to the app, read from a {@code CONVERTED} telemetry event: the amount
 * saved comes from the event's own column, the unit prices and the discount from its metadata.
 * Prices and percent are null when an old event carries no readable metadata; an event written
 * before sources were recorded came from a surfaced deal.
 */
record CheaperPurchase(UUID eventId, UUID productId, String marketCnpj, AppSavingSource source, BigDecimal saved,
                       BigDecimal paidPrice, BigDecimal previousPrice, BigDecimal percent,
                       OffsetDateTime recordedAt) {

    /** Biggest saving first; on a tie, the biggest percentage; then the most recent. */
    static final Comparator<CheaperPurchase> BIGGEST_SAVING_FIRST = Comparator
            .comparing(CheaperPurchase::saved).reversed()
            .thenComparing(CheaperPurchase::percent, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(CheaperPurchase::recordedAt, Comparator.reverseOrder());

    private static final ObjectMapper METADATA_READER = new ObjectMapper();
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    static CheaperPurchase of(NotificationEvent convertedEvent) {
        var metadata = readMetadata(convertedEvent.getMetadata());
        var discountFraction = decimal(metadata, "discountFraction");
        return new CheaperPurchase(
                convertedEvent.getId(),
                convertedEvent.getProductId(),
                convertedEvent.getMarketCnpj(),
                sourceOf(metadata),
                convertedEvent.getSavingsAmount().setScale(2, RoundingMode.HALF_UP),
                money(decimal(metadata, "paidUnitPrice")),
                money(decimal(metadata, "previousLastPaid")),
                discountFraction == null ? null : discountFraction.multiply(ONE_HUNDRED).setScale(1, RoundingMode.HALF_UP),
                convertedEvent.getOccurredAt());
    }

    private static JsonNode readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return null;
        try {
            return METADATA_READER.readTree(metadataJson);
        } catch (Exception unreadableMetadata) {
            return null;
        }
    }

    private static AppSavingSource sourceOf(JsonNode metadata) {
        if (metadata == null || !metadata.hasNonNull("source")) return AppSavingSource.DEALS;
        try {
            return AppSavingSource.valueOf(metadata.get("source").asText());
        } catch (IllegalArgumentException unknownSource) {
            return AppSavingSource.DEALS;
        }
    }

    private static BigDecimal decimal(JsonNode metadata, String field) {
        if (metadata == null || !metadata.hasNonNull(field) || !metadata.get(field).isNumber()) return null;
        return metadata.get(field).decimalValue();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
