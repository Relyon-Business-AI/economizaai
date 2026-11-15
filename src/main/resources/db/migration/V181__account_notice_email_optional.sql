-- The e-mail of the ACCOUNT notice is no longer always on: it is an option like the others,
-- off until the user switches it on. V180 stored no e-mail row for ACCOUNT (the option was
-- locked on), so whoever had chosen e-mail for the SYSTEM type would lose it. Carry that
-- choice over. Users without such a row keep what they receive today (push, or e-mail while
-- the account has no push token and never configured the notice).
INSERT INTO notification_notice_options (user_id, notice, channel, enabled)
SELECT DISTINCT preference.user_id, 'ACCOUNT', 'EMAIL', TRUE
FROM notification_preferences preference
WHERE preference.type = 'SYSTEM'
  AND preference.channel = 'EMAIL'
ON CONFLICT (user_id, notice, channel) DO NOTHING;
