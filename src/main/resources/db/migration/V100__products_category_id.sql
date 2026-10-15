-- Dual-write phase of the taxonomy migration: products gain a FK into the new
-- `categories` tree alongside the legacy `category` enum. Readers will prefer
-- category_id with a fallback to the enum until Phase 2 refines each product via
-- EAN/OFF/LLM. This migration only seeds the DEFAULT mapping (enum → category by
-- `legacy_enum`); it deliberately does NOT try to split MEAT_DAIRY into
-- Carnes vs Laticínios — that needs per-product signal (Phase 2).
ALTER TABLE products ADD COLUMN category_id UUID REFERENCES categories(id) ON DELETE SET NULL;
CREATE INDEX idx_products_category_id ON products(category_id);

-- Backfill: map each product's old enum to its default category node.
-- Products with a null enum stay null (uncategorized), same as before.
UPDATE products p
SET category_id = c.id
FROM categories c
WHERE c.legacy_enum = p.category
  AND p.category IS NOT NULL;
