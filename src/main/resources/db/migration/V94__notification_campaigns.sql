-- Admin notification ops: audiences (reusable user segments) + campaigns
-- (admin-authored sends over an audience). Campaign metrics reuse the existing
-- notifications outbox (sent/delivered/read) and notification_events telemetry
-- (PUSH_OPENED/DEAL_TAPPED/CONVERTED) — no new tracking tables.

CREATE TABLE notification_audiences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    -- Seeded audiences (e.g. Admins) are locked: not editable, not deletable.
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    -- All filters nullable = "don't filter on this". Combined with AND.
    role VARCHAR(20),
    subscription_tier VARCHAR(20),
    locale VARCHAR(5),
    has_push_token BOOLEAN,
    registered_within_days INTEGER,
    active_within_days INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE notification_campaigns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    type VARCHAR(40) NOT NULL DEFAULT 'SYSTEM',
    -- Free-form JSON extras forwarded to the push payload (deeplink etc).
    extras TEXT,
    -- NO ACTION: an audience referenced by any campaign cannot be deleted.
    audience_id UUID NOT NULL REFERENCES notification_audiences(id),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    scheduled_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    recipients_total INTEGER NOT NULL DEFAULT 0,
    recipients_sent INTEGER NOT NULL DEFAULT 0,
    recipients_failed INTEGER NOT NULL DEFAULT 0,
    created_by_email VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_campaigns_status ON notification_campaigns(status);

-- Ties each outbox row back to the campaign that produced it, so campaign
-- metrics are plain GROUP BYs over notifications + notification_events.
ALTER TABLE notifications
    ADD COLUMN campaign_id UUID REFERENCES notification_campaigns(id) ON DELETE SET NULL;

CREATE INDEX idx_notifications_campaign ON notifications(campaign_id) WHERE campaign_id IS NOT NULL;

-- Essential built-in audience: every ADMIN account — the safe test target.
INSERT INTO notification_audiences (name, description, built_in, role)
VALUES ('Admins', 'Contas administradoras — público de teste seguro para campanhas.', TRUE, 'ADMIN');
