-- Campaigns can now target an EXPLICIT user list (hand-picked in the admin UI)
-- instead of a filter-based audience. A campaign has exactly one source:
-- audience_id OR rows in notification_campaign_recipients (enforced in service).

ALTER TABLE notification_campaigns ALTER COLUMN audience_id DROP NOT NULL;

CREATE TABLE notification_campaign_recipients (
    campaign_id UUID NOT NULL REFERENCES notification_campaigns(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    PRIMARY KEY (campaign_id, user_id)
);
