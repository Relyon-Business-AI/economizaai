-- One row per (user, milestone) end-of-launch-promo warning already sent (D-15, D-7, D-3, D-1),
-- so the daily job never notifies the same user twice for the same milestone.
CREATE TABLE launch_promo_warnings (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    milestone_days INTEGER NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_launch_promo_warnings_user_milestone UNIQUE (user_id, milestone_days)
);
