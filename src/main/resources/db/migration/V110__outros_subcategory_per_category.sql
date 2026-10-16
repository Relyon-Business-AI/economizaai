-- "Outros ‹categoria›" em toda categoria que tem subcategorias e ainda não tinha um
-- catch-all. Dá um lar correto pros itens que pertencem à categoria mas não a nenhuma
-- folha específica (ex.: "bebida alcoólica mista" não é cerveja/vinho/destilado) — em vez
-- de a IA forçar uma folha errada. Laticínios (› Outros laticínios) e Mercearia (› Outros
-- da mercearia) já tinham. sort_order=90 => aparece por último dentro da categoria.

INSERT INTO categories (parent_id, slug, name_pt, name_en, sort_order) VALUES
    ((SELECT id FROM categories WHERE slug='hortifruti'),         'hortifruti-outros',         'Outros hortifrúti',          'Produce — other',    90),
    ((SELECT id FROM categories WHERE slug='carnes'),             'carnes-outros',             'Outras carnes',              'Meat — other',       90),
    ((SELECT id FROM categories WHERE slug='padaria'),            'padaria-outros',            'Outros da padaria',          'Bakery — other',     90),
    ((SELECT id FROM categories WHERE slug='doces-snacks'),       'doces-snacks-outros',       'Outros doces e snacks',      'Snacks — other',     90),
    ((SELECT id FROM categories WHERE slug='bebidas'),            'bebidas-outros',            'Outras bebidas',             'Beverages — other',  90),
    ((SELECT id FROM categories WHERE slug='bebidas-alcoolicas'), 'bebidas-alcoolicas-outros', 'Outras bebidas alcoólicas',  'Alcoholic — other',  90),
    ((SELECT id FROM categories WHERE slug='casa-descartaveis'),  'casa-descartaveis-outros',  'Outros de casa',             'Home — other',       90),
    ((SELECT id FROM categories WHERE slug='saude'),              'saude-outros',              'Outros de saúde',            'Health — other',     90);
