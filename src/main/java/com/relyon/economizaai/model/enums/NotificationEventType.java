package com.relyon.economizaai.model.enums;

/**
 * A single user-feedback / lifecycle signal in the notification telemetry log
 * ({@link com.relyon.economizaai.model.NotificationEvent}). This is the taxonomy
 * a future ranking/learning engine trains on.
 *
 * <p>Some events are produced by the server (delivery lifecycle, attributed
 * conversions); others are reported by the app client. Only the
 * {@link #isClientReportable() client-reportable} ones may be posted to the
 * public event-reporting endpoint — server-only types are rejected there.
 */
public enum NotificationEventType {

    /** Server emitted/queued the notification. */
    SENT(false),
    /** Channel confirmed delivery (push receipt, email accepted). */
    DELIVERED(false),
    /** User opened the push. */
    PUSH_OPENED(true),
    /** User opened the deals/notification screen (no originating push required). */
    SCREEN_OPENED(true),
    /** A surfaced deal entered the user's viewport. */
    DEAL_VIEWED(true),
    /** User tapped a surfaced deal. */
    DEAL_TAPPED(true),
    /** User added the deal's product to a shopping list. */
    ADDED_TO_LIST(true),
    /** Server-attributed conversion (a later receipt confirmed the purchase). */
    CONVERTED(false),
    /** User dismissed/swiped away the deal or notification. */
    DISMISSED(true),
    /** User muted this product/market/notification type. */
    MUTED(true);

    private final boolean clientReportable;

    NotificationEventType(boolean clientReportable) {
        this.clientReportable = clientReportable;
    }

    /** True when an app client is allowed to post this event to the public endpoint. */
    public boolean isClientReportable() {
        return clientReportable;
    }
}
