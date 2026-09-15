-- Acquisition tracking: where each user came from (UTM + click id + derived channel),
-- captured once at registration and immutable thereafter (mirrors registration_platform).
-- All nullable — pre-existing users and signups without landing params carry NULLs.
ALTER TABLE users ADD COLUMN utm_source              VARCHAR(120);
ALTER TABLE users ADD COLUMN utm_medium              VARCHAR(120);
ALTER TABLE users ADD COLUMN utm_campaign            VARCHAR(200);
ALTER TABLE users ADD COLUMN utm_content             VARCHAR(200);
ALTER TABLE users ADD COLUMN utm_term                VARCHAR(200);
-- fbclid (Meta) / gclid (Google) — the platform click identifier off the landing URL.
ALTER TABLE users ADD COLUMN attribution_click_id    VARCHAR(500);
ALTER TABLE users ADD COLUMN attribution_referrer    VARCHAR(500);
ALTER TABLE users ADD COLUMN attribution_landing_path VARCHAR(500);
-- Derived at signup from the above: instagram_paid / google_paid / referral / organic / direct.
ALTER TABLE users ADD COLUMN acquisition_channel     VARCHAR(40);

-- Acquisition analytics group and order by these.
CREATE INDEX idx_users_acquisition_channel ON users (acquisition_channel);
CREATE INDEX idx_users_utm_campaign ON users (utm_campaign);

-- Daily Meta (Facebook/Instagram) ad-spend snapshots pulled from the Marketing API
-- insights endpoint. One row per campaign per day; re-syncs upsert on (campaign_id, spend_date).
-- Populated by MetaAdSpendSyncJob only when META_ADS_TOKEN + META_AD_ACCOUNT_ID are set.
CREATE TABLE meta_ad_spend (
    id             UUID PRIMARY KEY,
    ad_account_id  VARCHAR(60)  NOT NULL,
    campaign_id    VARCHAR(60)  NOT NULL,
    campaign_name  VARCHAR(300),
    spend_date     DATE         NOT NULL,
    spend          NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency       VARCHAR(3)   NOT NULL DEFAULT 'BRL',
    impressions    BIGINT       NOT NULL DEFAULT 0,
    clicks         BIGINT       NOT NULL DEFAULT 0,
    reach          BIGINT       NOT NULL DEFAULT 0,
    synced_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_meta_ad_spend_campaign_day UNIQUE (campaign_id, spend_date)
);

CREATE INDEX idx_meta_ad_spend_date ON meta_ad_spend (spend_date);
