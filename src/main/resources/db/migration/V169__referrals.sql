-- Referral program: a stable personal code per user and one referral row per invited account.
CREATE TABLE referral_codes (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    code       VARCHAR(12) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- invited_id is UNIQUE: an account has at most one referrer, ever. It is kept (as NULL) when the
-- invited account is deleted so the inviter's counters stay stable.
CREATE TABLE referrals (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inviter_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invited_id            UUID UNIQUE REFERENCES users(id) ON DELETE SET NULL,
    qualified_at          TIMESTAMP WITH TIME ZONE,
    qualifying_receipt_id UUID,
    subscribed_at         TIMESTAMP WITH TIME ZONE,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_referrals_inviter ON referrals(inviter_id);
