-- Residual brands the variant-split missed: when the brand extractor can't tell two
-- real brands apart it returns the same (or no) value, so their items share a signature
-- and never split. These are confirmed from prod mis-merges.
--   coffees:     Bom Jesus / Pimpinela / Canecão / Preti(nho) — were colliding with the
--                generic "café" / with Melitta/Iguaçu/3 Corações;
--   detergents:  ODD / Minuano (Limpol already seeded);
--   pasta:       Orquídea / Emegê (Galo/Panco/Adria/Renata already seeded);
--   dairy:       Cemil / CCGL (Italac/Tirol/Aurora/Parmalat already seeded);
--   eggs:        MultOvos / Mantiqueira.
-- ON CONFLICT so a re-run / parallel seed is a no-op (normalized_key is UNIQUE).
INSERT INTO brand_registry_entries (normalized_key, display_name) VALUES
    ('bom jesus', 'Bom Jesus'),
    ('pimpinela', 'Pimpinela'),
    ('canecao', 'Canecão'),
    ('preti', 'Preti'),
    ('pretinho', 'Pretinho'),
    ('odd', 'ODD'),
    ('minuano', 'Minuano'),
    ('orquidea', 'Orquídea'),
    ('emege', 'Emegê'),
    ('cemil', 'Cemil'),
    ('ccgl', 'CCGL'),
    ('multovos', 'MultOvos'),
    ('mantiqueira', 'Mantiqueira')
ON CONFLICT (normalized_key) DO NOTHING;
