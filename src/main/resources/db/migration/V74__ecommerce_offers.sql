-- E-commerce offers: admin-CURATED product matches (precision first) plus a place to
-- cache provider-fetched offers later. Keyed by EAN so a scanned item resolves to an
-- online offer. Inert until the feature is used — no behavior change on existing flows.
CREATE TABLE ecommerce_offers (
    id            UUID PRIMARY KEY,
    ean           VARCHAR(14)   NOT NULL,
    product_id    UUID          REFERENCES products (id),
    provider      VARCHAR(40)   NOT NULL,
    title         VARCHAR(255)  NOT NULL,
    external_url  VARCHAR(1024),
    affiliate_url VARCHAR(1024),
    image_url     VARCHAR(1024),
    price         NUMERIC(12,2) NOT NULL,
    freight       NUMERIC(12,2),
    currency      VARCHAR(3)    NOT NULL DEFAULT 'BRL',
    in_stock      BOOLEAN       NOT NULL DEFAULT true,
    active        BOOLEAN       NOT NULL DEFAULT true,
    curated       BOOLEAN       NOT NULL DEFAULT true,
    curated_by    VARCHAR(255),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_ecommerce_offers_ean ON ecommerce_offers (ean);
CREATE INDEX idx_ecommerce_offers_active ON ecommerce_offers (active);
