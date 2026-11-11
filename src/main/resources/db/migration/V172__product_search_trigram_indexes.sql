-- Product search (GET /api/v1/products) matches a typed term as a substring of the product
-- name, generic name and absorbed receipt descriptions: LOWER(column) LIKE '%term%'.
-- A btree cannot serve a leading-wildcard LIKE; a GIN trigram index on the same expression can.
--
-- The indexes are a pure accelerator (the queries are correct without them), so a database
-- role that may not create the extension must not fail the deploy: in that case the block
-- logs a NOTICE and skips the indexes. Verify after deploy with:
--   SELECT indexname FROM pg_indexes WHERE indexname LIKE '%_trgm';
DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;

    CREATE INDEX IF NOT EXISTS idx_products_normalized_name_trgm
        ON products USING gin (lower(normalized_name) gin_trgm_ops);

    CREATE INDEX IF NOT EXISTS idx_products_generic_name_trgm
        ON products USING gin (lower(generic_name) gin_trgm_ops);

    CREATE INDEX IF NOT EXISTS idx_product_aliases_normalized_description_trgm
        ON product_aliases USING gin (lower(normalized_description) gin_trgm_ops);
EXCEPTION
    WHEN insufficient_privilege OR undefined_file OR feature_not_supported THEN
        RAISE NOTICE 'pg_trgm unavailable (%): product search trigram indexes skipped', SQLERRM;
END
$$;
