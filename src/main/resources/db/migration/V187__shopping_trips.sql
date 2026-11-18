-- Registro de uma compra feita pelo "modo de compra" do app: o que o plano previa (rota, regras,
-- precos) e o que aconteceu em cada parada (itens marcados, "nao achei", nota que fechou a parada,
-- pago contra previsto). O app envia uma vez, quando a compra termina. So para analise: nenhum
-- endpoint devolve estes dados.
CREATE TABLE shopping_trips (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    household_id         UUID NOT NULL REFERENCES households (id) ON DELETE CASCADE,
    -- Lista de origem. Sem FK de proposito: a lista pode ser apagada e o registro continua valendo.
    shopping_list_id     UUID,
    -- Identificador gerado no aparelho: reenviar a mesma compra nao cria outra linha.
    client_trip_id       VARCHAR(64) NOT NULL,
    started_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    -- COMPLETED = todas as paradas concluidas; ENDED_EARLY = encerrou antes da ultima.
    outcome              VARCHAR(20) NOT NULL,
    -- Regras da rota usadas no plano (nulas quando o plano foi montado sem regras).
    max_distance_km      NUMERIC(6, 1),
    max_stops            INTEGER,
    min_saving_per_stop  NUMERIC(12, 2),
    -- O plano como foi montado.
    planned_total        NUMERIC(12, 2) NOT NULL,
    planned_stop_count   INTEGER NOT NULL,
    unpriced_item_count  INTEGER NOT NULL DEFAULT 0,
    -- Soma do que as regras deixaram de fora (itens mais baratos em loja fora da rota).
    skipped_saving_total NUMERIC(12, 2) NOT NULL DEFAULT 0,
    -- Consultas de preco por codigo de barras feitas durante a compra.
    price_lookup_count   INTEGER NOT NULL DEFAULT 0,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_shopping_trips_household_client UNIQUE (household_id, client_trip_id)
);

CREATE INDEX idx_shopping_trips_household ON shopping_trips (household_id, started_at);

CREATE TABLE shopping_trip_stops (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id                 UUID NOT NULL REFERENCES shopping_trips (id) ON DELETE CASCADE,
    -- Ordem da parada na rota, a partir de 0.
    position                INTEGER NOT NULL,
    market_cnpj             VARCHAR(14) NOT NULL,
    market_name             VARCHAR(255),
    distance_km             NUMERIC(8, 3),
    concluded               BOOLEAN NOT NULL,
    concluded_at            TIMESTAMP WITH TIME ZONE,
    -- Total previsto dos itens que terminaram nesta parada.
    planned_total           NUMERIC(12, 2) NOT NULL,
    -- Nota que fechou a parada (nula quando concluiu sem nota). Apagar a nota so desfaz o vinculo.
    receipt_id              UUID REFERENCES receipts (id) ON DELETE SET NULL,
    -- Pago e previsto dos itens da parada que estavam na nota.
    receipt_paid_total      NUMERIC(12, 2),
    receipt_predicted_total NUMERIC(12, 2)
);

CREATE INDEX idx_shopping_trip_stops_trip ON shopping_trip_stops (trip_id);
CREATE INDEX idx_shopping_trip_stops_market ON shopping_trip_stops (market_cnpj);

CREATE TABLE shopping_trip_items (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stop_id                UUID NOT NULL REFERENCES shopping_trip_stops (id) ON DELETE CASCADE,
    product_id             UUID NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    quantity               NUMERIC(12, 3) NOT NULL,
    -- Preco previsto por unidade na parada em que o item terminou (nulo = sem preco previsto).
    planned_unit_price     NUMERIC(12, 2),
    -- LOCAL_HISTORY ou COMMUNITY_INDEX; nulo quando o item nao veio do plano.
    price_source           VARCHAR(20),
    -- IN_CART = marcado; NOT_FOUND = "nao achei"; PENDING = ficou por marcar.
    status                 VARCHAR(12) NOT NULL,
    in_receipt             BOOLEAN NOT NULL DEFAULT FALSE,
    -- Preco por unidade que saiu na nota (nulo quando o item nao estava nela).
    paid_unit_price        NUMERIC(12, 2),
    -- Parada em que o plano punha o item. Diferente de position da parada = foi levado para outra
    -- pelo "nao achei". Nulo = entrou durante a compra.
    planned_stop_position  INTEGER,
    added_during_trip      BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_shopping_trip_items_stop ON shopping_trip_items (stop_id);
CREATE INDEX idx_shopping_trip_items_product ON shopping_trip_items (product_id);
