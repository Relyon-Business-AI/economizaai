-- revenue_events.user_id was NOT NULL with a plain FK, so deleting the account of any
-- user who ever had a billing event (paid or zero-price grant) failed with an FK
-- violation → HTTP 500 on account deletion (LGPD erasure / App Store 5.1.1(v)).
-- The revenue row is a financial record and must survive; it just loses the link.
ALTER TABLE revenue_events ALTER COLUMN user_id DROP NOT NULL;

-- Drop whatever FK currently guards user_id (the V72 one was created inline, so its
-- name is the Postgres default — looked up instead of assumed).
DO $$
DECLARE
    existing_fk RECORD;
BEGIN
    FOR existing_fk IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey)
        WHERE con.conrelid = 'revenue_events'::regclass
          AND con.contype = 'f'
          AND att.attname = 'user_id'
    LOOP
        EXECUTE format('ALTER TABLE revenue_events DROP CONSTRAINT %I', existing_fk.conname);
    END LOOP;
END $$;

ALTER TABLE revenue_events
    ADD CONSTRAINT fk_revenue_events_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL;
