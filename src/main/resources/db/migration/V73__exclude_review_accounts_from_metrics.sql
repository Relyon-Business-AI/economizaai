-- Exclude store-review / robo test accounts from ALL admin metrics WITHOUT deleting
-- them. The acquisition/subscription analytics queries already skip admins and
-- @economizaai.app test accounts; this flag extends that to arbitrary accounts, and
-- the query filter additionally skips the @cloudtestlabaccounts.com domain (Google
-- Firebase Test Lab — new ones appear on every Play Console build, so it's a domain
-- rule, not a one-off).
ALTER TABLE users ADD COLUMN excluded_from_metrics BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill the review/test accounts seen during the 1.2.3 store submission. The
-- @cloudtestlabaccounts.com robots are also covered by the domain filter; the gmail
-- personas are app-review accounts on a real domain (all have zero receipts — the
-- tell-tale of a reviewer/bot, never a real user).
UPDATE users SET excluded_from_metrics = TRUE
WHERE lower(email) LIKE '%@cloudtestlabaccounts.com'
   OR lower(email) IN (
       'chadmcdaniel.22150@gmail.com',
       'testreviewer123@gmail.com',
       'faithglover.01063@gmail.com',
       'kellietucker.19304@gmail.com'
   );
