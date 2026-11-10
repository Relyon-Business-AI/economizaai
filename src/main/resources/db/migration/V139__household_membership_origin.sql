-- Household rules v2 (2026-11): membership is recorded on the USER, never deduced
-- from receipts.
--   origin_household_id  — the household the user "owns" and returns to when leaving
--                          a shared one (nullable: FK SET NULL if that household is gone).
--   household_joined_at  — when the user entered the CURRENT household; everything the
--                          household generated after this instant is copied to a leaver.
-- Receipts copied on departure keep a pointer to the source and never feed the price index.
ALTER TABLE users ADD COLUMN origin_household_id UUID REFERENCES households(id) ON DELETE SET NULL;
ALTER TABLE users ADD COLUMN household_joined_at TIMESTAMP;

-- Backfill: today's members own the household they're in (the app only just started
-- letting people share), and have been there since the account was created.
UPDATE users SET origin_household_id = household_id WHERE origin_household_id IS NULL;
UPDATE users SET household_joined_at = created_at WHERE household_joined_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_origin_household_id ON users(origin_household_id);

ALTER TABLE receipts ADD COLUMN copied_from_receipt_id UUID REFERENCES receipts(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_receipts_copied_from ON receipts(copied_from_receipt_id) WHERE copied_from_receipt_id IS NOT NULL;
