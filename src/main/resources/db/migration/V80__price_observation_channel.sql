-- Índice colaborativo separado por CANAL: observações físicas (IN_STORE) e online (ONLINE)
-- são séries distintas — preço de balcão ≠ preço de delivery/marketplace (frete, assinatura,
-- preço de lista). Toda leitura do índice físico filtra channel = 'IN_STORE'; o índice online
-- é gateado por ITEM (EAN → produto canônico), não pelo segmento da loja.
ALTER TABLE price_observations ADD COLUMN channel VARCHAR(10) NOT NULL DEFAULT 'IN_STORE';

-- Backfill preciso: o canal de cada observação vem do canal da nota que a gerou (via audit).
-- (As observações existentes são todas de mercado físico; o join só confirma isso.)
UPDATE price_observations po
SET channel = r.channel
FROM price_observation_audits a
JOIN receipts r ON r.id = a.receipt_id
WHERE a.observation_id = po.id;

-- Consulta do índice online é por (produto, canal) nacional, sem geo.
CREATE INDEX idx_price_observations_product_channel
    ON price_observations (product_id, channel, observed_at DESC);
