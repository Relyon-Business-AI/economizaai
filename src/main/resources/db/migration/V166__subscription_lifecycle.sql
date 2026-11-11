-- Subscription lifecycle:
--  * cancel_at_period_end: the user turned auto-renew off (or the provider scheduled a pause);
--    access continues until current_period_end, then the expiry sweep downgrades.
--  * launch_promo_granted_at / launch_promo_until: the launch promo is granted ONCE per user and
--    remembered on the row, so a paid subscription that ends before the promo does falls back to it.
ALTER TABLE subscriptions ADD COLUMN cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE subscriptions ADD COLUMN launch_promo_granted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE subscriptions ADD COLUMN launch_promo_until TIMESTAMP WITH TIME ZONE;

-- Promo grants made by the application were stored as 23:59:59 UTC (20:59 in Brasília) while the
-- V85 backfill used 23:59:59 Brasília time. Align them: the promo runs to the end of 31/12 in Brazil.
UPDATE subscriptions
SET current_period_end = TIMESTAMP WITH TIME ZONE '2026-12-31 23:59:59-03'
WHERE (provider IS NULL OR provider = 'manual')
  AND current_period_end = TIMESTAMP WITH TIME ZONE '2026-12-31 23:59:59+00';

-- Existing promo grants: manual rows WITH a period end (open-ended manual rows are admin grants).
UPDATE subscriptions
SET launch_promo_granted_at = created_at,
    launch_promo_until      = current_period_end
WHERE (provider IS NULL OR provider = 'manual')
  AND current_period_end IS NOT NULL;
