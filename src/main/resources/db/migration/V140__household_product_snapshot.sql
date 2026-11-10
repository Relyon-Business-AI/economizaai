-- Per-household snapshot of a consulted product's cheapest price at consult time, so the
-- "produtos escaneados" history can show a "mudou de preço desde a última consulta" delta
-- without recomputing live prices for every row (the FE fetches live per visible item).
--
-- Key: product_id when a canonical product was resolved, else the generic type
-- (generic_name_norm) for a catalog-preview-only match. snapshot_key collapses the two into
-- one stable, non-null unique key per household (Postgres treats NULLs as distinct, so a
-- plain unique on (household_id, product_id) wouldn't dedupe the type-only rows).
CREATE TABLE household_product_snapshot (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    household_id         UUID NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    product_id           UUID REFERENCES products (id) ON DELETE CASCADE,
    generic_name_norm    VARCHAR(160),
    snapshot_key         VARCHAR(200) NOT NULL,
    last_best_unit_price NUMERIC(12, 2),
    last_best_scope      VARCHAR(20),
    scan_count           INTEGER NOT NULL DEFAULT 1,
    last_consulted_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_household_product_snapshot UNIQUE (household_id, snapshot_key)
);

-- History list reads by household, newest consult first — covering index.
CREATE INDEX idx_household_product_snapshot_household_consulted
    ON household_product_snapshot (household_id, last_consulted_at DESC);
