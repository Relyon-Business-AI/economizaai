package com.relyon.economizaai.model.enums;

/**
 * Lifecycle of an admin notification campaign.
 *
 * <p>DRAFT → SCHEDULED → SENDING → SENT is the happy path. "Send now" is just
 * SCHEDULED with {@code scheduledAt = now} — one state machine, one dispatcher.
 * SENDING rows stranded by a restart are swept to FAILED (see CampaignScheduler),
 * and FAILED campaigns can be re-sent.
 */
public enum CampaignStatus {
    DRAFT,
    SCHEDULED,
    SENDING,
    SENT,
    CANCELLED,
    FAILED;

    /** States in which the campaign's content/audience may still be edited. */
    public boolean isEditable() {
        return this == DRAFT || this == SCHEDULED;
    }

    /** States from which a send (immediate or scheduled) may be triggered. */
    public boolean isSendable() {
        return this == DRAFT || this == SCHEDULED || this == FAILED;
    }
}
