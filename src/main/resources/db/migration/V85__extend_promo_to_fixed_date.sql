-- Launch-promo cutover to a FIXED date (2026-09-29): every existing account gets
-- Premium until 2026-12-31 (end of day, BRT), replacing the per-user rolling expiry
-- so the whole base flips on one announced date (single "pricing launch" event
-- instead of a continuous dribble of expirations with no checkout to land on).
-- New signups get the same fixed date via SubscriptionService (promo.until).
--
-- Defensive scope: only manual/absent-provider rows are touched — a real billing
-- provider subscription (e.g. revenuecat) is never overridden, and a manual grant
-- with NO expiry (admin/ops perpetual flip) stays perpetual.
INSERT INTO subscriptions (user_id, provider, provider_ref, status, current_period_end)
SELECT id, 'manual', NULL, 'ACTIVE', TIMESTAMP WITH TIME ZONE '2026-12-31 23:59:59-03'
FROM users
ON CONFLICT (user_id) DO UPDATE
    SET status             = 'ACTIVE',
        current_period_end = GREATEST(subscriptions.current_period_end, EXCLUDED.current_period_end),
        updated_at         = now()
    WHERE (subscriptions.provider IS NULL OR subscriptions.provider = 'manual')
      AND subscriptions.current_period_end IS NOT NULL;

UPDATE users u
SET subscription_tier = 'PRO'
FROM subscriptions s
WHERE s.user_id = u.id
  AND s.status = 'ACTIVE';
