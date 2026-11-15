package com.relyon.economizaai.model.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationNoticeTest {

    @Test
    void noticesAreListedInTheScreenOrder() {
        assertEquals(List.of(
                        NotificationNotice.RECEIPT_READY, NotificationNotice.PERSONAL_OFFERS,
                        NotificationNotice.STOCKOUT, NotificationNotice.PRICE_ALERTS,
                        NotificationNotice.DEALS_DIGEST, NotificationNotice.WEEKLY_REPORT,
                        NotificationNotice.ACCOUNT),
                Arrays.asList(NotificationNotice.values()));
    }

    @Test
    void eachNoticeOffersItsFixedChannelsInOrder() {
        var pushOnly = List.of(NotificationChannel.PUSH);
        var everyChannel = List.of(NotificationChannel.PUSH, NotificationChannel.EMAIL, NotificationChannel.WHATSAPP);

        assertEquals(pushOnly, NotificationNotice.RECEIPT_READY.channels());
        assertEquals(pushOnly, NotificationNotice.PERSONAL_OFFERS.channels());
        assertEquals(pushOnly, NotificationNotice.STOCKOUT.channels());
        assertEquals(everyChannel, NotificationNotice.PRICE_ALERTS.channels());
        assertEquals(everyChannel, NotificationNotice.DEALS_DIGEST.channels());
        assertEquals(List.of(NotificationChannel.EMAIL, NotificationChannel.WHATSAPP),
                NotificationNotice.WEEKLY_REPORT.channels());
        assertEquals(List.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                NotificationNotice.ACCOUNT.channels());
    }

    @Test
    void defaultsArePushOnEmailAndWhatsAppOff() {
        for (var notice : NotificationNotice.values()) {
            for (var channel : NotificationChannel.values()) {
                assertEquals(channel == NotificationChannel.PUSH, notice.isOnByDefault(channel), notice + "/" + channel);
            }
        }
    }

    @Test
    void everyTypeMapsToAtMostOneNotice() {
        assertEquals(Optional.of(NotificationNotice.RECEIPT_READY), NotificationNotice.of(NotificationType.RECEIPT_READY));
        assertEquals(Optional.of(NotificationNotice.RECEIPT_READY), NotificationNotice.of(NotificationType.RECEIPT_FAILED));
        assertEquals(Optional.of(NotificationNotice.RECEIPT_READY), NotificationNotice.of(NotificationType.RECEIPT_RECOVERED));
        assertEquals(Optional.of(NotificationNotice.PERSONAL_OFFERS), NotificationNotice.of(NotificationType.PROMO_PERSONAL));
        assertEquals(Optional.of(NotificationNotice.STOCKOUT), NotificationNotice.of(NotificationType.STOCKOUT));
        assertEquals(Optional.of(NotificationNotice.PRICE_ALERTS), NotificationNotice.of(NotificationType.PRICE_DROP));
        assertEquals(Optional.of(NotificationNotice.PRICE_ALERTS), NotificationNotice.of(NotificationType.BUDGET));
        assertEquals(Optional.of(NotificationNotice.DEALS_DIGEST), NotificationNotice.of(NotificationType.DEALS_DIGEST));
        assertEquals(Optional.of(NotificationNotice.WEEKLY_REPORT), NotificationNotice.of(NotificationType.WEEKLY_REPORT));
        assertEquals(Optional.of(NotificationNotice.ACCOUNT), NotificationNotice.of(NotificationType.SYSTEM));
    }

    @Test
    void typesOutsideTheNoticeModelHaveNoNotice() {
        var outside = List.of(NotificationType.PROMO_COMMUNITY, NotificationType.CHEAPER_MARKET,
                NotificationType.DIGEST, NotificationType.PUSH_OPTIN,
                NotificationType.HOUSEHOLD_MEMBER_JOINED, NotificationType.HOUSEHOLD_MEMBER_LEFT);

        outside.forEach(type -> assertTrue(NotificationNotice.of(type).isEmpty(), type.name()));
    }

    @Test
    void typesOfANoticeAreTheOnesMappedToIt() {
        assertEquals(List.of(NotificationType.PRICE_DROP, NotificationType.BUDGET),
                NotificationNotice.PRICE_ALERTS.types());
        assertEquals(List.of(NotificationType.SYSTEM), NotificationNotice.ACCOUNT.types());
    }

    @Test
    void fromKeyResolvesOnlyExactKeys() {
        assertEquals(Optional.of(NotificationNotice.STOCKOUT), NotificationNotice.fromKey("STOCKOUT"));
        assertTrue(NotificationNotice.fromKey("stockout").isEmpty());
        assertTrue(NotificationNotice.fromKey("PROMO_PERSONAL").isEmpty());
        assertTrue(NotificationNotice.fromKey(null).isEmpty());
    }
}
