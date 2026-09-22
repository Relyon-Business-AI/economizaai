package com.relyon.economizaai.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NotificationTypeDestinationTest {

    @Test
    void mapsEachTypeToItsDestination() {
        assertEquals(NotificationDestination.DEALS, NotificationType.PROMO_PERSONAL.destination());
        assertEquals(NotificationDestination.DEALS, NotificationType.PROMO_COMMUNITY.destination());
        assertEquals(NotificationDestination.DEALS, NotificationType.CHEAPER_MARKET.destination());
        assertEquals(NotificationDestination.DEALS, NotificationType.DEALS_DIGEST.destination());
        assertEquals(NotificationDestination.DEALS, NotificationType.DIGEST.destination());
        assertEquals(NotificationDestination.REPLENISHMENT, NotificationType.STOCKOUT.destination());
        assertEquals(NotificationDestination.PRODUCT, NotificationType.PRICE_DROP.destination());
        assertEquals(NotificationDestination.BUDGET, NotificationType.BUDGET.destination());
        assertEquals(NotificationDestination.INBOX, NotificationType.SYSTEM.destination());
    }

    @Test
    void everyTypeHasADestination() {
        for (var type : NotificationType.values()) {
            assertNotNull(type.destination(), "missing destination for " + type);
        }
    }
}
