package com.relyon.economizaai.service.notifications;

import com.relyon.economizaai.model.NotificationEvent;
import com.relyon.economizaai.model.enums.AppSavingSource;
import com.relyon.economizaai.model.enums.NotificationEventType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditedPurchaseTest {

    private static final OffsetDateTime RECORDED_AT = OffsetDateTime.parse("2026-11-10T12:00:00-03:00");

    @Test
    void readsEveryFieldTheAttributionWrites() {
        var receiptItemId = UUID.randomUUID();
        var exposureId = UUID.randomUUID();
        var credited = CreditedPurchase.of(convertedEvent("{\"savingsAmount\":4.00,\"paidUnitPrice\":8.00,"
                + "\"previousLastPaid\":10.00,\"discountFraction\":0.2000,\"source\":\"BEST_MARKETS\","
                + "\"followUp\":false,\"ownReference\":true,\"purchasedAt\":\"2026-11-09T18:30-03:00\","
                + "\"shownUnitPrice\":8.50,\"receiptItemId\":\"" + receiptItemId + "\","
                + "\"exposureId\":\"" + exposureId + "\"}"));

        assertTrue(credited.isOf(receiptItemId));
        assertFalse(credited.followUp());
        assertTrue(credited.ownReference());
        assertEquals(0, new BigDecimal("10.00").compareTo(credited.referenceUnitPrice()));
        assertEquals(0, new BigDecimal("8.50").compareTo(credited.shownUnitPrice()));
        assertEquals(AppSavingSource.BEST_MARKETS, credited.source());
        assertEquals(OffsetDateTime.parse("2026-11-09T18:30-03:00"), credited.purchasedAt());
        assertEquals(exposureId, credited.exposureId());
        assertNull(credited.dealSurfaceId());
    }

    @Test
    void eventWithoutMetadata_isAFirstPurchaseWithoutReference_soItNeverSetsOne() {
        var credited = CreditedPurchase.of(convertedEvent(null));

        assertFalse(credited.followUp());
        assertNull(credited.referenceUnitPrice());
        assertEquals(RECORDED_AT, credited.purchasedAt());
        assertFalse(credited.setsTheReferenceFor(RECORDED_AT.plusDays(1), 60));
        assertFalse(credited.isOf(UUID.randomUUID()));
    }

    @Test
    void unreadableMetadata_isTreatedAsNoMetadata() {
        var credited = CreditedPurchase.of(convertedEvent("{not json"));

        assertNull(credited.referenceUnitPrice());
        assertEquals(AppSavingSource.DEALS, credited.source());
        assertEquals(RECORDED_AT, credited.purchasedAt());
    }

    @Test
    void unknownSourceUnreadableDateAndIdentifier_fallBackInsteadOfFailing() {
        var credited = CreditedPurchase.of(convertedEvent("{\"previousLastPaid\":10.00,\"source\":\"SOMETHING_NEW\","
                + "\"purchasedAt\":\"yesterday\",\"exposureId\":\"not-an-id\"}"));

        assertEquals(AppSavingSource.DEALS, credited.source());
        assertEquals(RECORDED_AT, credited.purchasedAt());
        assertNull(credited.exposureId());
    }

    @Test
    void referenceWrittenAsText_isNotTrusted() {
        var credited = CreditedPurchase.of(convertedEvent("{\"previousLastPaid\":\"10.00\"}"));

        assertNull(credited.referenceUnitPrice());
        assertFalse(credited.setsTheReferenceFor(RECORDED_AT.plusDays(1), 60));
    }

    @Test
    void setsTheReference_onlyInsideTheWindowAndNeverFromTheFuture() {
        var credited = CreditedPurchase.of(convertedEvent(
                "{\"previousLastPaid\":10.00,\"purchasedAt\":\"2026-11-10T12:00-03:00\"}"));
        var purchasedAt = OffsetDateTime.parse("2026-11-10T12:00-03:00");

        assertTrue(credited.setsTheReferenceFor(purchasedAt, 60));
        assertTrue(credited.setsTheReferenceFor(purchasedAt.plusDays(60), 60));
        assertFalse(credited.setsTheReferenceFor(purchasedAt.plusDays(60).plusSeconds(1), 60));
        assertFalse(credited.setsTheReferenceFor(purchasedAt.minusSeconds(1), 60));
    }

    @Test
    void followUpOrBorrowedReference_neverSetsTheReference() {
        var followUp = CreditedPurchase.of(convertedEvent("{\"followUp\":true,\"previousLastPaid\":10.00}"));
        var borrowedReference = CreditedPurchase.of(convertedEvent("{\"ownReference\":false,\"previousLastPaid\":12.00}"));

        assertFalse(followUp.setsTheReferenceFor(RECORDED_AT.plusDays(1), 60));
        assertFalse(borrowedReference.setsTheReferenceFor(RECORDED_AT.plusDays(1), 60));
    }

    private static NotificationEvent convertedEvent(String metadataJson) {
        return NotificationEvent.builder()
                .id(UUID.randomUUID()).eventType(NotificationEventType.CONVERTED)
                .metadata(metadataJson).occurredAt(RECORDED_AT)
                .build();
    }
}
