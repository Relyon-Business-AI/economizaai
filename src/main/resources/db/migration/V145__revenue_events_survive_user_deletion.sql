-- revenue_events.user_id was NOT NULL with a plain FK, so deleting the account of any
-- user who ever had a billing event (paid or zero-price grant) failed with an FK
-- violation → HTTP 500 on account deletion (LGPD erasure / App Store 5.1.1(v)).
-- The revenue row is a financial record and must survive; it just loses the link.
ALTER TABLE revenue_events ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE revenue_events DROP CONSTRAINT IF EXISTS revenue_events_user_id_fkey;
ALTER TABLE revenue_events
    ADD CONSTRAINT revenue_events_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL;
