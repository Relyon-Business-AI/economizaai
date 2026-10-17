-- FASE 2 do refactor da taxonomia: o NÓ (categories/category_id) vira a fonte da verdade e o
-- enum legado vira projeção derivada (ProductCategoryNodeListener agora é node-first).
-- Este backfill garante que TODA linha que só tinha o enum ganha o nó-grupo equivalente —
-- a partir daqui nenhuma linha categorizada fica sem nó.

-- Produtos: piso do nó pros que têm enum mas nó nulo (linhas antigas nunca re-salvas).
UPDATE products p SET category_id = c.id
FROM categories c
WHERE p.category_id IS NULL AND p.category IS NOT NULL
  AND c.parent_id IS NULL AND c.legacy_enum = p.category;

-- Dicionário curado: regras com enum mas sem nó apontam pro nó-grupo.
UPDATE curated_dictionary_entries e SET category_id = c.id
FROM categories c
WHERE e.category_id IS NULL AND e.category IS NOT NULL
  AND c.parent_id IS NULL AND c.legacy_enum = e.category;

-- Dicionário aprendido: idem.
UPDATE learned_dictionary e SET category_id = c.id
FROM categories c
WHERE e.category_id IS NULL AND e.category IS NOT NULL
  AND c.parent_id IS NULL AND c.legacy_enum = e.category;

-- Catálogo EAN: idem.
UPDATE ean_catalog e SET category_id = c.id
FROM categories c
WHERE e.category_id IS NULL AND e.category IS NOT NULL
  AND c.parent_id IS NULL AND c.legacy_enum = e.category;

-- Snapshot do NÓ na confirmação (par do category_at_confirmation): histórico confirmado passa a
-- congelar também a subcategoria — pré-requisito pra aposentar o snapshot de enum na Fase D.
ALTER TABLE receipt_items ADD COLUMN category_node_at_confirmation UUID REFERENCES categories(id);
