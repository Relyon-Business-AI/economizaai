-- Quiet hours (21:00-08:00 America/Sao_Paulo): a notification raised in the window is
-- written to the inbox at once, but its push/e-mail is held back until the window ends.
-- dispatch_deferred marks the rows still waiting; the hourly drain job claims and sends them.
ALTER TABLE notifications ADD COLUMN dispatch_deferred BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_notifications_dispatch_deferred
    ON notifications (user_id, created_at)
    WHERE dispatch_deferred;
