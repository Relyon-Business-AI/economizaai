-- "Precos e inflacao": a inflacao oficial de alimentos (IBGE/IPCA) ao lado de como os precos
-- pagos nos mercados se moveram, segundo as notas do app.

-- Variacao mensal oficial (IPCA, tabela SIDRA 7060, variavel 63) por area e item.
-- A area "Brasil" nao tem estado; as demais pertencem a uma UF.
CREATE TABLE official_inflation_months (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    area_code       VARCHAR(10)  NOT NULL,
    area_name       VARCHAR(120) NOT NULL,
    state           VARCHAR(2),
    item_code       VARCHAR(10)  NOT NULL,
    item_name       VARCHAR(160) NOT NULL,
    reference_month DATE         NOT NULL,
    change_percent  NUMERIC(8, 4) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_official_inflation_area_item_month UNIQUE (area_code, item_code, reference_month)
);

CREATE INDEX idx_official_inflation_month ON official_inflation_months (reference_month);

-- Variacao mensal dos precos da comunidade por produto e estado: soma de peso x ln(preco do mes /
-- preco do mes anterior) dos pares (produto, loja, base de unidade), a soma dos pesos e quantos pares.
CREATE TABLE community_price_changes (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference_month  DATE       NOT NULL,
    state            VARCHAR(2) NOT NULL,
    product_id       UUID       NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    weighted_log_sum DOUBLE PRECISION NOT NULL,
    weight_sum       DOUBLE PRECISION NOT NULL,
    pair_count       INTEGER    NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_community_price_change UNIQUE (reference_month, state, product_id)
);

CREATE INDEX idx_community_price_changes_product ON community_price_changes (product_id);

-- Cobertura do mes em cada estado: pares comparados e lojas distintas (decide se o mes vale).
CREATE TABLE community_price_coverage (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference_month DATE       NOT NULL,
    state           VARCHAR(2) NOT NULL,
    pair_count      INTEGER    NOT NULL,
    store_count     INTEGER    NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_community_price_coverage UNIQUE (reference_month, state)
);
