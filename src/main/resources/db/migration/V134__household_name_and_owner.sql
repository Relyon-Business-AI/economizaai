-- Household sharing (2026-11): a display name and a host (anfitrião).
-- The host is the only member allowed to remove other members. Nullable so an
-- account deletion never fails (ON DELETE SET NULL); HouseholdService self-heals a
-- null/stale host to the oldest remaining member.
ALTER TABLE households ADD COLUMN name VARCHAR(60);
ALTER TABLE households ADD COLUMN owner_user_id UUID REFERENCES users(id) ON DELETE SET NULL;

-- Backfill: the oldest member of each household becomes its host. Solo households
-- (the overwhelming majority — the app shipped the screen as "coming soon") get
-- their single member.
UPDATE households h
SET owner_user_id = (
    SELECT u.id
    FROM users u
    WHERE u.household_id = h.id
    ORDER BY u.created_at ASC, u.id ASC
    LIMIT 1
)
WHERE h.owner_user_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_households_owner_user_id ON households(owner_user_id);
