-- Churn survey: why users delete their account. Written in the same tx that hard-deletes
-- the user, BEFORE the user row goes. Deliberately ANONYMIZED (no user_id / email) — only
-- non-identifying snapshots, so churn can be read by segment without PII (LGPD).
CREATE TABLE account_deletion_feedback (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reason             VARCHAR(32),            -- AccountDeletionReason; null = user skipped
    details            VARCHAR(1000),          -- free text (OTHER)
    subscription_tier  VARCHAR(16),            -- FREE / PRO snapshot
    account_age_days   INTEGER,                -- how long the account existed
    receipt_count      INTEGER,                -- how many notas it had (engagement proxy)
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- The admin panel reads recent windows grouped by reason.
CREATE INDEX idx_account_deletion_feedback_created_at ON account_deletion_feedback (created_at);
