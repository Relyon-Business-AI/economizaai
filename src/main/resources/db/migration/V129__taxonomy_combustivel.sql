-- Grupo "Combustível" na taxonomia (docs/FUEL_PRICES.md): itens de posto
-- (segment GAS_STATION) ganham casa própria em vez de caírem em Outros.
-- Aditivo e inerte até o classificador/dicionário apontar pra cá; Saúde já
-- cobre a vertical farmácia (medicamentos, suplementos).

INSERT INTO categories (slug, name_pt, name_en, legacy_enum, sort_order) VALUES
    ('combustivel', 'Combustível', 'Fuel', NULL, 145);

INSERT INTO categories (parent_id, slug, name_pt, name_en, sort_order) VALUES
    ((SELECT id FROM categories WHERE slug='combustivel'), 'gasolina-comum',         'Gasolina comum',          'Regular gasoline',   1),
    ((SELECT id FROM categories WHERE slug='combustivel'), 'gasolina-aditivada',     'Gasolina aditivada',      'Premium gasoline',   2),
    ((SELECT id FROM categories WHERE slug='combustivel'), 'etanol',                 'Etanol',                  'Ethanol',            3),
    ((SELECT id FROM categories WHERE slug='combustivel'), 'diesel',                 'Diesel',                  'Diesel',             4),
    ((SELECT id FROM categories WHERE slug='combustivel'), 'gnv',                    'GNV',                     'CNG',                5),
    ((SELECT id FROM categories WHERE slug='combustivel'), 'lubrificantes-aditivos', 'Lubrificantes e aditivos','Lubricants & additives', 6);
