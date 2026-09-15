-- Current budget/status snapshot per Meta campaign, refreshed by MetaAdSpendSyncJob
-- alongside the daily spend. One row per campaign; upsert on campaign_id. Powers the
-- "budget remaining / campaign ended" view on the acquisition dashboard.
CREATE TABLE meta_campaign (
    id                UUID PRIMARY KEY,
    campaign_id       VARCHAR(60)  NOT NULL UNIQUE,
    name              VARCHAR(300),
    status            VARCHAR(40),                 -- Meta effective_status (ACTIVE/PAUSED/...)
    lifetime_budget   NUMERIC(12,2),               -- R$ (converted from Meta's minor units); null if unset
    budget_remaining  NUMERIC(12,2),               -- R$ still available; null if unset
    ends_at           TIMESTAMP WITH TIME ZONE,    -- Meta stop_time; null = open-ended
    synced_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
