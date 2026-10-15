-- Uma subcategoria custom também pode entrar em OUTRA categoria custom do mesmo
-- household (não só numa categoria embutida da taxonomia). No máximo um pai:
-- parent_category_id (embutida) XOR parent_custom_category_id (custom). SET NULL se
-- a categoria-pai some → a filha vira solta (não some).
ALTER TABLE household_custom_categories
    ADD COLUMN parent_custom_category_id UUID REFERENCES household_custom_categories(id) ON DELETE SET NULL;

CREATE INDEX idx_custom_categories_parent_custom ON household_custom_categories (parent_custom_category_id);
