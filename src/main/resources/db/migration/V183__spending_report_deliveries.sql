-- One row per spending report handed to a user's channels: which frequency, which period it
-- covers (period_start = first day covered) and the notification it became. The unique
-- constraint is the send-once guard: the run that inserts the row sends the report, any other
-- run of the same period (overlap, restart, a second instance) loses the insert and skips.
-- The rows stay as the history of reports each user received.

CREATE TABLE spending_report_deliveries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    frequency       VARCHAR(10) NOT NULL,
    period_start    DATE NOT NULL,
    notification_id UUID REFERENCES notifications(id) ON DELETE SET NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_spending_report_deliveries_user_frequency_period UNIQUE (user_id, frequency, period_start)
);
