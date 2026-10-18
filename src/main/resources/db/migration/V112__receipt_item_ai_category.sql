-- Sugestão de categoria/subcategoria da IA por item da nota (camada final do cascade).
-- Preenchida ASSÍNCRONA logo que a nota fica PENDING_CONFIRMATION, só pros itens que o
-- determinístico (EAN/alias/dicionário/keyword) não resolveu. A revisão mostra a sugestão
-- (ajustável como qualquer outra) e o confirm a aplica ao criar o produto canônico.
ALTER TABLE receipt_items ADD COLUMN ai_category_id UUID REFERENCES categories(id);
