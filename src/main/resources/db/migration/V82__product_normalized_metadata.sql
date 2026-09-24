-- Normalized (accent-stripped, lowercased) mirrors of genericName/brand for dedup + matching.
-- Display keeps generic_name/brand as typed; dedup compares on these.
ALTER TABLE products ADD COLUMN generic_name_norm VARCHAR(160);
ALTER TABLE products ADD COLUMN brand_norm VARCHAR(160);

-- Dedup lookup: (generic_name_norm, brand_norm, pack_size, pack_unit).
CREATE INDEX idx_products_dedup_norm
    ON products (generic_name_norm, brand_norm, pack_size, pack_unit);
