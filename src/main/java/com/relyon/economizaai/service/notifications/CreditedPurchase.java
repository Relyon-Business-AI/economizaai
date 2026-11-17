package com.relyon.economizaai.service.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relyon.economizaai.model.NotificationEvent;
import com.relyon.economizaai.model.enums.AppSavingSource;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * A purchase the attribution already credited to the app, read back from its {@code CONVERTED}
 * event. The first credited purchase of a product at a market fixes the reference (what the
 * household paid before the app showed the price); the purchases after it are follow-ups
 * measured against that same reference.
 *
 * <p>{@code shownUnitPrice} is null when the credit came from a surfaced deal, which has no cap.
 * {@code ownReference} is false when the household had never bought the product before, so the
 * reference was not a price it paid and later purchases are not measured against it.
 * Events written before these fields existed read as a first purchase made when the event was
 * recorded.
 */
record CreditedPurchase(String receiptItemId, boolean followUp, boolean ownReference, BigDecimal referenceUnitPrice,
                        BigDecimal shownUnitPrice, AppSavingSource source, OffsetDateTime purchasedAt) {

    static final String RECEIPT_ITEM_ID = "receiptItemId";
    static final String FOLLOW_UP = "followUp";
    static final String OWN_REFERENCE = "ownReference";
    static final String REFERENCE_UNIT_PRICE = "previousLastPaid";
    static final String SHOWN_UNIT_PRICE = "shownUnitPrice";
    static final String SOURCE = "source";
    static final String PURCHASED_AT = "purchasedAt";

    private static final ObjectMapper METADATA_READER = new ObjectMapper();

    static CreditedPurchase of(NotificationEvent convertedEvent) {
        var metadata = readMetadata(convertedEvent.getMetadata());
        return new CreditedPurchase(
                text(metadata, RECEIPT_ITEM_ID),
                metadata != null && metadata.path(FOLLOW_UP).asBoolean(false),
                metadata == null || metadata.path(OWN_REFERENCE).asBoolean(true),
                decimal(metadata, REFERENCE_UNIT_PRICE),
                decimal(metadata, SHOWN_UNIT_PRICE),
                sourceOf(metadata),
                purchasedAt(metadata, convertedEvent.getOccurredAt()));
    }

    boolean isOf(UUID receiptItemId) {
        return receiptItemId != null && receiptItemId.toString().equals(this.receiptItemId);
    }

    /** A first purchase with a usable reference, made inside the follow-up window of {@code purchaseInstant}. */
    boolean setsTheReferenceFor(OffsetDateTime purchaseInstant, int followUpDays) {
        return !followUp
                && ownReference
                && referenceUnitPrice != null
                && purchasedAt != null
                && !purchasedAt.isAfter(purchaseInstant)
                && !purchasedAt.isBefore(purchaseInstant.minusDays(followUpDays));
    }

    private static JsonNode readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return null;
        try {
            return METADATA_READER.readTree(metadataJson);
        } catch (Exception unreadableMetadata) {
            return null;
        }
    }

    private static String text(JsonNode metadata, String field) {
        return metadata == null || !metadata.hasNonNull(field) ? null : metadata.get(field).asText();
    }

    private static BigDecimal decimal(JsonNode metadata, String field) {
        if (metadata == null || !metadata.hasNonNull(field) || !metadata.get(field).isNumber()) return null;
        return metadata.get(field).decimalValue();
    }

    private static AppSavingSource sourceOf(JsonNode metadata) {
        try {
            var source = text(metadata, SOURCE);
            return source == null ? AppSavingSource.DEALS : AppSavingSource.valueOf(source);
        } catch (IllegalArgumentException unknownSource) {
            return AppSavingSource.DEALS;
        }
    }

    private static OffsetDateTime purchasedAt(JsonNode metadata, OffsetDateTime recordedAt) {
        var purchasedAt = text(metadata, PURCHASED_AT);
        if (purchasedAt == null) return recordedAt;
        try {
            return OffsetDateTime.parse(purchasedAt);
        } catch (DateTimeParseException unreadableInstant) {
            return recordedAt;
        }
    }
}
