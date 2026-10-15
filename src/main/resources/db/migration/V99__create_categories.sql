-- Two-level category taxonomy (group → leaf), replacing the flat ProductCategory
-- enum over the next phases. A category with children acts as a GROUP; a category
-- can also hold products directly (leaf-less groups like Limpeza/Pet/Congelados).
-- `legacy_enum` records which old enum maps to this category as its DEFAULT target,
-- so a later phase can backfill products that have no EAN/OFF/LLM signal.
--
-- Phase 1 is ADDITIVE: this table is seeded but NOT yet wired into products /
-- dictionaries / overrides. The enum stays the active categorization until Phase 2
-- repoints everything (and correctly splits MEAT_DAIRY into Carnes vs Laticínios
-- via EAN/OFF/LLM — a split SQL alone can't do).
CREATE TABLE categories (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parent_id   UUID REFERENCES categories(id) ON DELETE RESTRICT,
    slug        VARCHAR(60) NOT NULL UNIQUE,
    name_pt     VARCHAR(60) NOT NULL,
    name_en     VARCHAR(60) NOT NULL,
    legacy_enum VARCHAR(30),
    sort_order  INT NOT NULL DEFAULT 0,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_categories_parent ON categories(parent_id);

-- Top-level groups. legacy_enum set on the 10 that are the default landing spot
-- for the old enum values (MEAT_DAIRY → carnes provisionally; Phase 2 splits dairy).
INSERT INTO categories (slug, name_pt, name_en, legacy_enum, sort_order) VALUES
    ('hortifruti',         'Hortifrúti',          'Produce',             'PRODUCE',       10),
    ('carnes',             'Carnes',              'Meat',                'MEAT_DAIRY',    20),
    ('laticinios-ovos',    'Laticínios e ovos',   'Dairy & eggs',        NULL,            30),
    ('padaria',            'Padaria',             'Bakery',              'BAKERY',        40),
    ('mercearia',          'Mercearia',           'Groceries',           'GROCERIES',     50),
    ('doces-snacks',       'Doces e snacks',      'Snacks & sweets',     NULL,            60),
    ('congelados',         'Congelados',          'Frozen',              NULL,            70),
    ('bebidas',            'Bebidas',             'Beverages',           'BEVERAGES',     80),
    ('bebidas-alcoolicas', 'Bebidas alcoólicas',  'Alcoholic beverages', NULL,            90),
    ('limpeza',            'Limpeza',             'Cleaning',            'CLEANING',     100),
    ('higiene',            'Higiene pessoal',     'Personal care',       'PERSONAL_CARE',110),
    ('casa-descartaveis',  'Casa e descartáveis', 'Home & disposables',  NULL,           120),
    ('saude',              'Saúde',               'Health',              'HEALTH',       130),
    ('pet',                'Pet',                 'Pet',                 'PET_SUPPLIES', 140),
    ('outros',             'Outros',              'Other',               'OTHER',        150);

-- Leaves. parent resolved by slug so we don't hardcode generated UUIDs.
INSERT INTO categories (parent_id, slug, name_pt, name_en, sort_order) VALUES
    ((SELECT id FROM categories WHERE slug='hortifruti'),        'frutas',             'Frutas',             'Fruits',         1),
    ((SELECT id FROM categories WHERE slug='hortifruti'),        'legumes-verduras',   'Legumes e verduras', 'Vegetables',     2),
    ((SELECT id FROM categories WHERE slug='hortifruti'),        'temperos-ervas',     'Temperos e ervas',   'Herbs',          3),

    ((SELECT id FROM categories WHERE slug='carnes'),            'carnes-bovinas',     'Bovinos',            'Beef',           1),
    ((SELECT id FROM categories WHERE slug='carnes'),            'aves',               'Aves',               'Poultry',        2),
    ((SELECT id FROM categories WHERE slug='carnes'),            'suinos',             'Suínos',             'Pork',           3),
    ((SELECT id FROM categories WHERE slug='carnes'),            'peixes',             'Peixes',             'Fish',           4),
    ((SELECT id FROM categories WHERE slug='carnes'),            'frutos-do-mar',      'Frutos do mar',      'Seafood',        5),

    ((SELECT id FROM categories WHERE slug='laticinios-ovos'),   'leite',              'Leite',              'Milk',           1),
    ((SELECT id FROM categories WHERE slug='laticinios-ovos'),   'queijos',            'Queijos',            'Cheese',         2),
    ((SELECT id FROM categories WHERE slug='laticinios-ovos'),   'iogurtes',           'Iogurtes',           'Yogurt',         3),
    ((SELECT id FROM categories WHERE slug='laticinios-ovos'),   'ovos',               'Ovos',               'Eggs',           4),
    ((SELECT id FROM categories WHERE slug='laticinios-ovos'),   'outros-laticinios',  'Outros laticínios',  'Other dairy',    5),

    ((SELECT id FROM categories WHERE slug='padaria'),           'paes',               'Pães',               'Breads',         1),
    ((SELECT id FROM categories WHERE slug='padaria'),           'bolos-confeitaria',  'Bolos e confeitaria','Cakes & pastry', 2),

    ((SELECT id FROM categories WHERE slug='mercearia'),         'graos-massas',       'Grãos e massas',     'Grains & pasta', 1),
    ((SELECT id FROM categories WHERE slug='mercearia'),         'enlatados-conservas','Enlatados e conservas','Canned goods', 2),
    ((SELECT id FROM categories WHERE slug='mercearia'),         'molhos-temperos',    'Molhos e temperos',  'Sauces',         3),
    ((SELECT id FROM categories WHERE slug='mercearia'),         'matinais',           'Matinais',           'Breakfast',      4),
    ((SELECT id FROM categories WHERE slug='mercearia'),         'mercearia-outros',   'Outros da mercearia','Other pantry',   5),

    ((SELECT id FROM categories WHERE slug='doces-snacks'),      'doces',              'Doces',              'Sweets',         1),
    ((SELECT id FROM categories WHERE slug='doces-snacks'),      'salgadinhos',        'Salgadinhos',        'Salty snacks',   2),
    ((SELECT id FROM categories WHERE slug='doces-snacks'),      'chocolates',         'Chocolates',         'Chocolate',      3),

    ((SELECT id FROM categories WHERE slug='bebidas'),           'agua',               'Água',               'Water',          1),
    ((SELECT id FROM categories WHERE slug='bebidas'),           'sucos',              'Sucos',              'Juices',         2),
    ((SELECT id FROM categories WHERE slug='bebidas'),           'refrigerantes',      'Refrigerantes',      'Sodas',          3),
    ((SELECT id FROM categories WHERE slug='bebidas'),           'cafe-cha',           'Café e chá',         'Coffee & tea',   4),

    ((SELECT id FROM categories WHERE slug='bebidas-alcoolicas'),'cervejas',           'Cervejas',           'Beer',           1),
    ((SELECT id FROM categories WHERE slug='bebidas-alcoolicas'),'vinhos',             'Vinhos',             'Wine',           2),
    ((SELECT id FROM categories WHERE slug='bebidas-alcoolicas'),'destilados',         'Destilados',         'Spirits',        3),

    ((SELECT id FROM categories WHERE slug='casa-descartaveis'), 'papel',              'Papel',              'Paper',          1),
    ((SELECT id FROM categories WHERE slug='casa-descartaveis'), 'descartaveis',       'Descartáveis',       'Disposables',    2),
    ((SELECT id FROM categories WHERE slug='casa-descartaveis'), 'utilidades',         'Utilidades',         'Household goods', 3),

    ((SELECT id FROM categories WHERE slug='saude'),             'medicamentos',       'Medicamentos',       'Medicine',       1),
    ((SELECT id FROM categories WHERE slug='saude'),             'suplementos',        'Suplementos',        'Supplements',    2),

    ((SELECT id FROM categories WHERE slug='outros'),            'nao-mercado',        'Não-mercado',        'Non-grocery',    1);
