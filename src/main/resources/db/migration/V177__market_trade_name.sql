-- Nome fantasia do mercado (cadastro público do CNPJ). Entra na cascata do nome de exibição:
-- apelido da casa -> nome fantasia -> razão social limpa.
ALTER TABLE market_locations
    ADD COLUMN trade_name VARCHAR(255),
    ADD COLUMN trade_name_checked_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN trade_name_attempts INTEGER NOT NULL DEFAULT 0;

-- Fila do backfill: mercados cujo cadastro ainda não foi consultado.
CREATE INDEX idx_market_locations_trade_name_pending
    ON market_locations (trade_name_attempts, created_at)
    WHERE trade_name_checked_at IS NULL;
