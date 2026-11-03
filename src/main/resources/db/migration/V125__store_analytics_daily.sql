-- Daily store analytics snapshots fetched from App Store Connect (iOS)
-- and, in the future, Google Play Developer Reporting API (Android).
-- One row per platform + date + source_type combination.
CREATE TABLE store_analytics_daily (
    id          UUID      NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    platform    VARCHAR(10)  NOT NULL,          -- 'IOS' | 'ANDROID'
    report_date DATE         NOT NULL,
    source_type VARCHAR(80)  NOT NULL DEFAULT 'TOTAL',
    impressions BIGINT       NOT NULL DEFAULT 0,
    page_views  BIGINT       NOT NULL DEFAULT 0,
    app_units   BIGINT       NOT NULL DEFAULT 0, -- installs / first-time downloads
    synced_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (platform, report_date, source_type)
);

CREATE INDEX idx_store_analytics_platform_date ON store_analytics_daily (platform, report_date DESC);
