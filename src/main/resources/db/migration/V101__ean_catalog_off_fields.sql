-- Capture more of what OFF/EAN lookups already return but we were throwing away,
-- so the (rate-limited) OFF re-fetch campaign in Phase 2 persists everything we
-- might want later in ONE pass and never has to re-fetch:
--   categories_raw : verbatim OFF category tags → lets us re-map to a finer
--                    taxonomy later without another fetch
--   category_id    : new two-level taxonomy node mapped from those raw tags
--   image_url      : product photo (future app UI)
--   quantity       : pack-size string (e.g. "500 g") → price-per-unit normalization
--   labels_tags    : organic / gluten-free / lactose-free / vegan … (future filters,
--                    feeds the user-requested "Orgânicos" category)
--   nutriscore     : Nutri-Score grade a–e (future health lens)
--   nova_group     : NOVA ultra-processing group 1–4 (future health lens)
ALTER TABLE ean_catalog
    ADD COLUMN category_id    UUID REFERENCES categories(id) ON DELETE SET NULL,
    ADD COLUMN categories_raw TEXT,
    ADD COLUMN image_url      VARCHAR(500),
    ADD COLUMN quantity       VARCHAR(50),
    ADD COLUMN labels_tags    TEXT,
    ADD COLUMN nutriscore     VARCHAR(1),
    ADD COLUMN nova_group     INT;

CREATE INDEX idx_ean_catalog_category_id ON ean_catalog(category_id);
