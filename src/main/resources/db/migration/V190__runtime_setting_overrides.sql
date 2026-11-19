-- Business settings the owners change from the admin without a deploy (plan limits, price
-- windows, notification rules, spend ceilings, supported states...). One row per setting that
-- was CHANGED in the admin: no row means the value configured for the environment
-- (application.yaml / env var) is in effect, so removing a row restores that value.

CREATE TABLE runtime_setting_overrides (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    setting_key VARCHAR(120) NOT NULL,
    setting_value TEXT NOT NULL,
    updated_by  VARCHAR(255),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_runtime_setting_overrides_key UNIQUE (setting_key)
);
