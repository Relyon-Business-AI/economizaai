-- In-app announcements: one card on the Home screen (a new feature, a tip or a notice) that
-- the owners publish from the admin. starts_at / ends_at hold Brasília wall-clock (same basis
-- as receipts.issued_at); a null ends_at means "until switched off". The card shows to a user
-- until that user dismisses it: one announcement_dismissals row per (user, announcement), the
-- unique pair making a repeated or concurrent dismissal a no-op.

CREATE TABLE announcements (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind         VARCHAR(20) NOT NULL,
    title        VARCHAR(80) NOT NULL,
    body         VARCHAR(160),
    action_label VARCHAR(30),
    destination  VARCHAR(40),
    starts_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at      TIMESTAMP WITH TIME ZONE,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_announcements_active_starts ON announcements(active, starts_at);

CREATE TABLE announcement_dismissals (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    announcement_id UUID NOT NULL REFERENCES announcements(id) ON DELETE CASCADE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_announcement_dismissals_user_announcement UNIQUE (user_id, announcement_id)
);

CREATE INDEX idx_announcement_dismissals_announcement ON announcement_dismissals(announcement_id);
