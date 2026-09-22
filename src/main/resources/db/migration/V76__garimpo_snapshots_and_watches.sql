-- Garimpo de promoções: marketplace deal-hunting on top of the e-commerce providers.
-- Price snapshots are an append-only change-log (one row per observed price CHANGE per
-- product) — the history that tells a real discount from a fake one. Watches are
-- standing searches swept on a schedule; hits notify a webhook (group bot). Both are
-- admin-only surfaces; no user data involved.
CREATE TABLE garimpo_price_snapshots (
    id               UUID PRIMARY KEY,
    provider         VARCHAR(40)   NOT NULL,
    external_id      VARCHAR(60)   NOT NULL,
    title            VARCHAR(512)  NOT NULL,
    price            NUMERIC(12,2) NOT NULL,
    original_price   NUMERIC(12,2),
    discount_percent INTEGER,
    currency         VARCHAR(3)    NOT NULL DEFAULT 'BRL',
    external_url     VARCHAR(1024),
    affiliate_url    VARCHAR(1024),
    image_url        VARCHAR(1024),
    seller_name      VARCHAR(255),
    free_shipping    BOOLEAN       NOT NULL DEFAULT false,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_garimpo_snapshots_product
    ON garimpo_price_snapshots (provider, external_id, created_at DESC);

CREATE TABLE garimpo_watches (
    id                   UUID PRIMARY KEY,
    search_term          VARCHAR(255) NOT NULL,
    provider             VARCHAR(40)  NOT NULL DEFAULT 'mercadolivre',
    target_price         NUMERIC(12,2),
    min_discount_percent INTEGER,
    active               BOOLEAN      NOT NULL DEFAULT true,
    last_run_at          TIMESTAMP WITH TIME ZONE,
    created_by           VARCHAR(255),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_garimpo_watches_active ON garimpo_watches (active);
