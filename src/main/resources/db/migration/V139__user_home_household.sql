-- Each user's home household (domicílio de origem) and when they joined their current one.
-- home_household_id is NULL while the user is in their own home (an original host);
-- set only while they are a GUEST in someone else's household. Leaving returns a guest
-- there; household_joined_at bounds what they copy out of the shared household.
ALTER TABLE users ADD COLUMN home_household_id UUID REFERENCES households(id) ON DELETE SET NULL;
ALTER TABLE users ADD COLUMN household_joined_at TIMESTAMP;

-- Backfill guests already living in a shared household: their home is the origin of
-- their earliest receipt that came from elsewhere (the same rule the service used to
-- derive on the fly). Joined-at is unknown for them → now(), so a legacy guest who
-- leaves copies nothing pre-dating this migration.
UPDATE users u
SET home_household_id = origin.origin_household_id,
    household_joined_at = now()
FROM (
    SELECT DISTINCT ON (r.user_id) r.user_id, r.origin_household_id
    FROM receipts r
    JOIN users member ON member.id = r.user_id
    WHERE r.origin_household_id IS NOT NULL
      AND r.origin_household_id <> member.household_id
    ORDER BY r.user_id, r.created_at
) origin
WHERE origin.user_id = u.id
  AND EXISTS (SELECT 1 FROM households h WHERE h.id = origin.origin_household_id);

CREATE INDEX IF NOT EXISTS idx_users_home_household_id ON users(home_household_id);
