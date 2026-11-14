-- Notification settings per notice AND per channel. Until now each notification TYPE had its
-- channel rows in notification_preferences (PUSH / EMAIL / NONE ...). From here on every NOTICE
-- (see NotificationNotice) lists the channels that exist for it and the user switches each one
-- on or off; one row here = one switch the user set. A missing row follows the default
-- (push on, e-mail and WhatsApp off, the locked account e-mail always on).
--
-- Not stored here: the WhatsApp option of the weekly report, which stays on
-- users.whatsapp_weekly_report_enabled (unchanged).
--
-- Nothing is deleted: notification_preferences keeps its rows (the types outside the notice
-- model still read them, and the previous release still finds the old choices if it is rolled
-- back to).

CREATE TABLE notification_notice_options (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    notice     VARCHAR(30) NOT NULL,
    channel    VARCHAR(20) NOT NULL,
    enabled    BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_notice_options_user_notice_channel UNIQUE (user_id, notice, channel)
);

-- The DAILY/WEEKLY choice remembered while the digest is off (digest_frequency = 'OFF').
ALTER TABLE users ADD COLUMN digest_frequency_choice VARCHAR(10);

-- Carry the per-type choices over, preserving what each user receives today.
--   * a notice gets PUSH on when any of its types had PUSH, off when its types were configured
--     without it (NONE = muted, or e-mail only);
--   * a notice that has an e-mail channel gets EMAIL on when any of its types had EMAIL;
--   * a push-only notice whose types were e-mail only gets NO row: it keeps the default, where
--     an account without a push token is still reached by e-mail, exactly as before;
--   * ACCOUNT (type SYSTEM) only stores its push switch — its e-mail is locked on.
-- Users without rows are not touched: their default is unchanged.
WITH notice_types (type, notice, offers_email) AS (
    VALUES ('RECEIPT_READY',     'RECEIPT_READY',   FALSE),
           ('RECEIPT_FAILED',    'RECEIPT_READY',   FALSE),
           ('RECEIPT_RECOVERED', 'RECEIPT_READY',   FALSE),
           ('PROMO_PERSONAL',    'PERSONAL_OFFERS', FALSE),
           ('STOCKOUT',          'STOCKOUT',        FALSE),
           ('PRICE_DROP',        'PRICE_ALERTS',    TRUE),
           ('BUDGET',            'PRICE_ALERTS',    TRUE),
           ('DEALS_DIGEST',      'DEALS_DIGEST',    TRUE),
           ('SYSTEM',            'ACCOUNT',         FALSE)
),
legacy_choice AS (
    SELECT preference.user_id,
           notice_types.notice,
           notice_types.offers_email,
           bool_or(preference.channel = 'PUSH')  AS chose_push,
           bool_or(preference.channel = 'EMAIL') AS chose_email
    FROM notification_preferences preference
    JOIN notice_types ON notice_types.type = preference.type
    GROUP BY preference.user_id, notice_types.notice, notice_types.offers_email
)
INSERT INTO notification_notice_options (user_id, notice, channel, enabled)
SELECT legacy_choice.user_id, legacy_choice.notice, 'PUSH', legacy_choice.chose_push
FROM legacy_choice
WHERE legacy_choice.chose_push
   OR legacy_choice.offers_email
   OR legacy_choice.notice = 'ACCOUNT'
   OR NOT legacy_choice.chose_email
UNION ALL
SELECT legacy_choice.user_id, legacy_choice.notice, 'EMAIL', TRUE
FROM legacy_choice
WHERE legacy_choice.offers_email
  AND legacy_choice.chose_email;

-- A digest that is off (digest_frequency = 'OFF') has every DEALS_DIGEST option off.
UPDATE notification_notice_options
SET enabled = FALSE
WHERE notice = 'DEALS_DIGEST'
  AND user_id IN (SELECT id FROM users WHERE digest_frequency = 'OFF');

INSERT INTO notification_notice_options (user_id, notice, channel, enabled)
SELECT account.id, 'DEALS_DIGEST', 'PUSH', FALSE
FROM users account
WHERE account.digest_frequency = 'OFF'
ON CONFLICT (user_id, notice, channel) DO NOTHING;

-- And the other way round: a digest whose type was muted (nothing left on) is switched off,
-- remembering the frequency it had.
UPDATE users account
SET digest_frequency_choice = account.digest_frequency,
    digest_frequency = 'OFF'
WHERE account.digest_frequency <> 'OFF'
  AND EXISTS (SELECT 1
              FROM notification_notice_options push_option
              WHERE push_option.user_id = account.id
                AND push_option.notice = 'DEALS_DIGEST'
                AND push_option.channel = 'PUSH'
                AND push_option.enabled = FALSE)
  AND NOT EXISTS (SELECT 1
                  FROM notification_notice_options email_option
                  WHERE email_option.user_id = account.id
                    AND email_option.notice = 'DEALS_DIGEST'
                    AND email_option.channel = 'EMAIL'
                    AND email_option.enabled = TRUE);
