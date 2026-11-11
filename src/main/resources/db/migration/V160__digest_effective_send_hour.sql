-- Deals digest: the hour each user's digest is due is now stored on the user, so the hourly
-- job selects due users in SQL instead of loading everyone and recomputing the hour.
-- NULL = not assigned yet (the job assigns new accounts a default spread over 11:00-19:00).
ALTER TABLE users ADD COLUMN digest_effective_send_hour INTEGER;

-- Existing users keep the hour they already had.
-- 1) explicit override
UPDATE users
SET digest_effective_send_hour = digest_send_hour
WHERE digest_send_hour IS NOT NULL;

-- 2) inferred: the household's most common purchase hour minus one, with 5+ confirmed
--    receipts carrying a purchase time (ties go to the later hour)
WITH hourly AS (
    SELECT r.household_id,
           CAST(EXTRACT(HOUR FROM r.issued_at) AS INTEGER) AS hour_of_day,
           COUNT(*) AS receipt_count
    FROM receipts r
    WHERE r.status = 'CONFIRMED'
      AND r.issued_at IS NOT NULL
    GROUP BY r.household_id, CAST(EXTRACT(HOUR FROM r.issued_at) AS INTEGER)
),
ranked AS (
    SELECT hourly.household_id,
           hourly.hour_of_day,
           SUM(hourly.receipt_count) OVER (PARTITION BY hourly.household_id) AS total_receipts,
           ROW_NUMBER() OVER (PARTITION BY hourly.household_id
                              ORDER BY hourly.receipt_count DESC, hourly.hour_of_day DESC) AS position
    FROM hourly
)
UPDATE users
SET digest_effective_send_hour = (ranked.hour_of_day + 23) % 24
FROM ranked
WHERE ranked.household_id = users.household_id
  AND ranked.position = 1
  AND ranked.total_receipts >= 5
  AND users.digest_effective_send_hour IS NULL;

-- 3) everyone else stays on the previous default (16:00)
UPDATE users
SET digest_effective_send_hour = 16
WHERE digest_effective_send_hour IS NULL;
