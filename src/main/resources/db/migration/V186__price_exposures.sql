-- Registro do que o app MOSTROU para a pessoa: um preço de um produto em um mercado, em qual
-- tela e com qual plano na hora. É a base para medir depois "viu no app e comprou mais barato".
-- Uma linha por (usuário, produto, mercado, tela, dia): a primeira vez do dia é a que fica.
CREATE TABLE price_exposures (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    household_id      UUID NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    product_id        UUID NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    market_cnpj       VARCHAR(14) NOT NULL,
    unit_price        NUMERIC(12, 2) NOT NULL,
    surface           VARCHAR(30) NOT NULL,
    subscription_tier VARCHAR(20) NOT NULL,
    shown_on          DATE NOT NULL,
    shown_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_price_exposures_household_product ON price_exposures (household_id, product_id, shown_at);
CREATE INDEX idx_price_exposures_user_day ON price_exposures (user_id, surface, shown_on);
