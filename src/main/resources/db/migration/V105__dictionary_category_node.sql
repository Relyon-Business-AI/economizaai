-- Fase 2 do motor na taxonomia: regras de dicionário (curado + aprendido) podem
-- carregar a FOLHA fina (products.category_id) além do enum. Aditivo, nullable —
-- regra sem nó mantém o comportamento enum-only. SET NULL se o nó sumir.
ALTER TABLE curated_dictionary_entries
    ADD COLUMN category_id UUID REFERENCES categories(id) ON DELETE SET NULL;

ALTER TABLE learned_dictionary
    ADD COLUMN category_id UUID REFERENCES categories(id) ON DELETE SET NULL;
