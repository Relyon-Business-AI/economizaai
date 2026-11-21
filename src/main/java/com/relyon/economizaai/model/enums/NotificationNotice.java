package com.relyon.economizaai.model.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * WHAT the user is told about. Each notice lists the channels that exist for it and the user
 * switches every one of them on or off independently: a notification leaves through each
 * channel that is on, and a notice with nothing on is not delivered.
 *
 * <p>Declaration order is the order the settings screen lists the notices in; the channel
 * order is the order of each notice's options (and of the dispatch).
 */
public enum NotificationNotice {

    RECEIPT_READY(NotificationChannel.PUSH),
    PERSONAL_OFFERS(NotificationChannel.PUSH),
    STOCKOUT(NotificationChannel.PUSH),
    PRICE_ALERTS(NotificationChannel.PUSH, NotificationChannel.EMAIL, NotificationChannel.WHATSAPP),
    DEALS_DIGEST(NotificationChannel.PUSH, NotificationChannel.EMAIL, NotificationChannel.WHATSAPP),
    /**
     * The spending report, weekly and/or monthly ({@link SpendingReportFrequency}). The key
     * still says "weekly" because the published app indexes the notices by it.
     */
    WEEKLY_REPORT(NotificationChannel.EMAIL, NotificationChannel.WHATSAPP),
    /** Terms, security, billing. */
    ACCOUNT(NotificationChannel.PUSH, NotificationChannel.EMAIL);

    private final List<NotificationChannel> channels;

    NotificationNotice(NotificationChannel... channels) {
        this.channels = List.of(channels);
    }

    /** The channels that exist for this notice, in display and dispatch order. */
    public List<NotificationChannel> channels() {
        return channels;
    }

    public boolean offers(NotificationChannel channel) {
        return channels.contains(channel);
    }

    /** Whether the option is on for a user who never touched it: only the push is. */
    public boolean isOnByDefault(NotificationChannel channel) {
        return channel == NotificationChannel.PUSH;
    }

    /** Every notification type that belongs to this notice. */
    public List<NotificationType> types() {
        return Arrays.stream(NotificationType.values())
                .filter(type -> of(type).filter(this::equals).isPresent())
                .toList();
    }

    /**
     * The notice a notification type belongs to. Empty for the types that sit outside the
     * notice model and keep the per-type channel rows they always had: the retired toggles,
     * the rule-driven spending digest, the in-app push invitation and the household
     * membership notices.
     */
    public static Optional<NotificationNotice> of(NotificationType type) {
        return switch (type) {
            case RECEIPT_READY, RECEIPT_FAILED, RECEIPT_RECOVERED -> Optional.of(RECEIPT_READY);
            case PROMO_PERSONAL -> Optional.of(PERSONAL_OFFERS);
            case STOCKOUT -> Optional.of(STOCKOUT);
            case PRICE_DROP, BUDGET -> Optional.of(PRICE_ALERTS);
            case DEALS_DIGEST -> Optional.of(DEALS_DIGEST);
            case WEEKLY_REPORT, MONTHLY_REPORT -> Optional.of(WEEKLY_REPORT);
            case SYSTEM -> Optional.of(ACCOUNT);
            case PROMO_COMMUNITY, CHEAPER_MARKET, DIGEST, PUSH_OPTIN,
                 HOUSEHOLD_MEMBER_JOINED, HOUSEHOLD_MEMBER_LEFT, PRICE_INFLATION -> Optional.empty();
        };
    }

    /** Resolves the key the API exchanges ({@code "STOCKOUT"}); empty when it names no notice. */
    public static Optional<NotificationNotice> fromKey(String key) {
        return Arrays.stream(values()).filter(notice -> notice.name().equals(key)).findFirst();
    }
}
