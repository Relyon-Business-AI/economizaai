-- Multi-channel notification preferences: a user can now enable SEVERAL channels
-- per notification type (e.g. inbox + push + email), instead of exactly one.
-- The old UNIQUE(user_id, type) allowed a single row per type; the new
-- UNIQUE(user_id, type, channel) allows one row per enabled (type, channel) pair.
-- Existing rows (one channel per type) migrate as-is — each becomes that type's
-- single enabled channel. A row with channel='NONE' still means "fully muted".

ALTER TABLE notification_preferences
    DROP CONSTRAINT IF EXISTS notification_preferences_user_id_type_key;

ALTER TABLE notification_preferences
    ADD CONSTRAINT notification_preferences_user_type_channel_key
    UNIQUE (user_id, type, channel);
