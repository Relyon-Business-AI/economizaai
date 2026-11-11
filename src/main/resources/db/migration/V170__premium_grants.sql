-- Premium time earned outside billing (referral rewards). Each row is a [starts_at, ends_at)
-- window; a user is Premium while a subscription is ACTIVE or any grant window is running.
-- Kept apart from `subscriptions` so a reward never replaces or shortens a paid subscription.
CREATE TABLE premium_grants (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind           VARCHAR(30) NOT NULL,
    source_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    starts_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    ends_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_premium_grants_user_source_kind UNIQUE (user_id, source_user_id, kind)
);

CREATE INDEX idx_premium_grants_user_ends ON premium_grants(user_id, ends_at);

-- The first-qualified-referral reward happens only once per inviter.
CREATE UNIQUE INDEX uq_premium_grants_first_referral
    ON premium_grants(user_id) WHERE kind = 'REFERRAL_FIRST';
