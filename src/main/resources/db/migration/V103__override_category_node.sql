-- Let a household's category correction target a new-taxonomy node (fine leaf/group),
-- not only a global enum or a custom category. Exactly one of category /
-- custom_category_id / category_node_id is set on a row.
ALTER TABLE household_product_category_overrides
    ADD COLUMN category_node_id UUID REFERENCES categories(id) ON DELETE CASCADE;
