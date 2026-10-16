package com.relyon.economizaai.model.enums;

import java.util.Arrays;
import java.util.List;

/**
 * The kind of a notification — doubles as the discriminator for a
 * {@link com.relyon.economizaai.model.NotificationRule}. Each value carries
 * metadata that drives rule validation (does it need a product? a threshold?)
 * and default seeding (is it a system default the user merely toggles, or a
 * rule the user creates with parameters?).
 */
public enum NotificationType {

    // --- System defaults: auto-seeded per user, enabled by default, user can toggle ---
    /** You paid below your own historical median on a confirmed receipt. */
    PROMO_PERSONAL(Scope.DEFAULT, false, false),
    /** A product your household buys went on sale somewhere in the network. */
    PROMO_COMMUNITY(Scope.DEFAULT, false, false),
    /** The same product you buy is cheaper at a market near you. */
    CHEAPER_MARKET(Scope.DEFAULT, false, false),
    /** Periodic savings + watchlist digest. */
    DIGEST(Scope.DEFAULT, false, false),

    // --- User-created rules: the user supplies the parameters ---
    /** Notify when {@code product} is observed at or below {@code thresholdPrice}. */
    PRICE_DROP(Scope.USER, true, true),
    /** Replenishment: notify before a regularly-bought {@code product} is predicted to run out. */
    STOCKOUT(Scope.USER, true, false),
    /** Notify when household spend in the current month reaches {@code thresholdPrice} (R$). */
    BUDGET(Scope.USER, false, true),

    // --- Server-emitted, no user rule (governed by their own preference) ---
    /**
     * Daily rollup pointing to the deals screen (Phase C). Server-emitted by the
     * digest scheduler; governed by {@code User.digestFrequency}, NOT a rule row —
     * so it's a SYSTEM-scope type (no auto-seeded toggle).
     */
    DEALS_DIGEST(Scope.SYSTEM, false, false),

    // --- Transactional / one-off ---
    SYSTEM(Scope.SYSTEM, false, false),
    /** A note stuck in contingency ("aguardando SEFAZ") was auto-recovered and now has items. */
    RECEIPT_RECOVERED(Scope.SYSTEM, false, false),
    /** A scanned note that took a while (portal retries / fallback / sweeper) finished and is ready to review. */
    RECEIPT_READY(Scope.SYSTEM, false, false),
    /** A scanned note that took a while could not be processed and ended as FAILED_PARSE. */
    RECEIPT_FAILED(Scope.SYSTEM, false, false);

    public enum Scope { DEFAULT, USER, SYSTEM }

    private final Scope scope;
    private final boolean requiresProduct;
    private final boolean requiresThreshold;

    NotificationType(Scope scope, boolean requiresProduct, boolean requiresThreshold) {
        this.scope = scope;
        this.requiresProduct = requiresProduct;
        this.requiresThreshold = requiresThreshold;
    }

    public Scope scope() {
        return scope;
    }

    public boolean requiresProduct() {
        return requiresProduct;
    }

    public boolean requiresThreshold() {
        return requiresThreshold;
    }

    public boolean isDefault() {
        return scope == Scope.DEFAULT;
    }

    public boolean isUserCreatable() {
        return scope == Scope.USER;
    }

    /** Default types auto-seeded for every user (one toggleable rule each). */
    public static List<NotificationType> defaults() {
        return Arrays.stream(values()).filter(NotificationType::isDefault).toList();
    }

    /** Which FE screen a notification of this type should deep-link to. */
    public NotificationDestination destination() {
        return switch (this) {
            // DEALS fica só pros tipos que NASCEM de uma oferta concreta (nunca
            // chegam com a tela vazia). O resumo semanal vive no corpo da própria
            // notificação → INBOX, onde dá pra ler (volume baixo não atrapalha).
            case PROMO_PERSONAL, PROMO_COMMUNITY, CHEAPER_MARKET, DEALS_DIGEST -> NotificationDestination.DEALS;
            case DIGEST -> NotificationDestination.SPENDING;
            case STOCKOUT -> NotificationDestination.REPLENISHMENT;
            case PRICE_DROP -> NotificationDestination.PRODUCT;
            case BUDGET -> NotificationDestination.BUDGET;
            case RECEIPT_RECOVERED, RECEIPT_READY, RECEIPT_FAILED -> NotificationDestination.RECEIPT;
            case SYSTEM -> NotificationDestination.INBOX;
        };
    }
}
