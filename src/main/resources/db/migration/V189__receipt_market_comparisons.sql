-- "Esta compra em outros mercados", fotografada quando a nota e confirmada: o preco de cada item
-- da nota em ate 3 mercados proximos, naquele dia. Abrir a nota depois le daqui, sem recalcular,
-- para o numero nao mudar com os precos. O que a casa pagou vem sempre dos itens atuais da nota.
CREATE TABLE receipt_market_comparisons (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Uma comparacao por nota. Apagar a nota (ou a conta) apaga a comparacao.
    receipt_id  UUID NOT NULL UNIQUE REFERENCES receipts (id) ON DELETE CASCADE,
    computed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE receipt_market_comparison_prices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    comparison_id   UUID NOT NULL REFERENCES receipt_market_comparisons (id) ON DELETE CASCADE,
    -- Ordem do mercado na comparacao, a partir de 0 (o que cobre mais itens primeiro).
    market_position INTEGER NOT NULL,
    market_cnpj     VARCHAR(14) NOT NULL,
    market_name     VARCHAR(255),
    distance_km     NUMERIC(8, 3),
    product_id      UUID NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    -- Preco por unidade do produto nesse mercado, e quando ele foi visto la.
    unit_price      NUMERIC(12, 4) NOT NULL,
    observed_at     TIMESTAMP
);

CREATE INDEX idx_receipt_market_comparison_prices_comparison
    ON receipt_market_comparison_prices (comparison_id);
