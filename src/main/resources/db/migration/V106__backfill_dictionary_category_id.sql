-- Backfill único: as entradas de dicionário criadas ANTES das Fases 2–3 têm
-- category_id nulo (o nó só passou a ser dual-written daí pra frente). Aqui
-- preenchemos o category_id das linhas existentes com o NÓ-GRUPO default do enum
-- (categories.legacy_enum = <enum>, parent_id IS NULL). Só toca linhas nulas; se o
-- enum não tiver grupo default mapeado, fica nulo (inócuo).
UPDATE learned_dictionary d
   SET category_id = (SELECT c.id FROM categories c
                       WHERE c.legacy_enum = d.category AND c.parent_id IS NULL)
 WHERE d.category_id IS NULL
   AND d.category IS NOT NULL;

UPDATE curated_dictionary_entries d
   SET category_id = (SELECT c.id FROM categories c
                       WHERE c.legacy_enum = d.category AND c.parent_id IS NULL)
 WHERE d.category_id IS NULL
   AND d.category IS NOT NULL;
