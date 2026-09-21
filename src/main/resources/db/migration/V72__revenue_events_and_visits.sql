-- First-party REVENUE events: one row per paid-provider money event (RevenueCat
-- purchase/renewal), capturing the ACTUAL amount. Lets LTV/ROAS move from a
-- modeled proxy (PRO count × configured price) to real money over time.
-- Populated by RevenueCatWebhookService on activating events; deduped on the
-- provider's event id.
CREATE TABLE revenue_events (
    id            UUID PRIMARY KEY,
    user_id       UUID          NOT NULL REFERENCES users (id),
    provider      VARCHAR(40)   NOT NULL,
    provider_ref  VARCHAR(255),
    event_type    VARCHAR(60)   NOT NULL,
    product_id    VARCHAR(255),
    amount        NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency      VARCHAR(3)    NOT NULL DEFAULT 'BRL',
    occurred_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_revenue_event_provider_ref UNIQUE (provider, provider_ref)
);
CREATE INDEX idx_revenue_events_user ON revenue_events (user_id);
CREATE INDEX idx_revenue_events_occurred ON revenue_events (occurred_at);

-- First-party TOP-OF-FUNNEL: an anonymous "visit" beacon the web landing fires
-- on first load, so we can measure OUR OWN click→signup conversion per campaign,
-- independent of Meta's black-box click count (and resilient to iOS/ATT gaps).
-- LGPD-safe: anon_id is a random client id (no PII), ip_hash is a one-way hash.
CREATE TABLE visits (
    id                  UUID PRIMARY KEY,
    anon_id             VARCHAR(64)  NOT NULL,
    utm_source          VARCHAR(120),
    utm_medium          VARCHAR(120),
    utm_campaign        VARCHAR(200),
    utm_content         VARCHAR(200),
    utm_term            VARCHAR(200),
    click_id            VARCHAR(500),
    referrer            VARCHAR(500),
    landing_path        VARCHAR(500),
    acquisition_channel VARCHAR(40),
    platform            VARCHAR(20),
    ip_hash             VARCHAR(64),
    user_agent          VARCHAR(400),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_visits_created ON visits (created_at);
CREATE INDEX idx_visits_campaign ON visits (utm_campaign);
CREATE INDEX idx_visits_channel ON visits (acquisition_channel);
CREATE INDEX idx_visits_anon ON visits (anon_id);
