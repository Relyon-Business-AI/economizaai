package com.relyon.economizaai.model.enums;

/**
 * Where the FE should navigate when the user taps a notification card. Derived
 * from the {@link NotificationType} at response time (no persisted column) — the
 * {@code extras} payload already carries the concrete id (productId / marketCnpj)
 * the destination screen needs.
 */
public enum NotificationDestination {
    /** Deals / offers screen. */
    DEALS,
    /** Replenishment / stock screen. */
    REPLENISHMENT,
    /** A specific product's detail screen. */
    PRODUCT,
    /** Budget screen. */
    BUDGET,
    /** A specific receipt's screen (e.g. a recovered contingency note). */
    RECEIPT,
    /** Generic inbox — transactional/system notifications and the fallback. */
    INBOX
}
