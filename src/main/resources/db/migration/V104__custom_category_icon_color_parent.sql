-- Categorias custom do household ganham ícone, cor e um grupo-pai (aninhar na
-- taxonomia). parent_category_id aponta para um GRUPO da tabela categories; se o
-- grupo for removido a custom vira solta (SET NULL), não some.
ALTER TABLE household_custom_categories
    ADD COLUMN icon VARCHAR(40),
    ADD COLUMN color VARCHAR(9),
    ADD COLUMN parent_category_id UUID REFERENCES categories(id) ON DELETE SET NULL;

CREATE INDEX idx_custom_categories_parent ON household_custom_categories (parent_category_id);
