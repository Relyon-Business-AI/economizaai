package com.relyon.economizaai.model.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class NotificationTypeDestinationTest {

    @Test
    void mapsEachTypeToItsDestination() {
        // Promo de um produto específico abre o PRODUTO (melhores mercados).
        assertEquals(NotificationDestination.PRODUCT, NotificationType.PROMO_PERSONAL.destination());
        assertEquals(NotificationDestination.PRODUCT, NotificationType.PROMO_COMMUNITY.destination());
        assertEquals(NotificationDestination.PRODUCT, NotificationType.CHEAPER_MARKET.destination());
        // Só o resumo diário (rollup) abre a lista de ofertas.
        assertEquals(NotificationDestination.DEALS, NotificationType.DEALS_DIGEST.destination());
        // Resumo (semanal/mensal) deep-linka pra gastos por categoria — nunca pra
        // ofertas (poderia estar vazia em volume baixo).
        assertEquals(NotificationDestination.SPENDING, NotificationType.DIGEST.destination());
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
