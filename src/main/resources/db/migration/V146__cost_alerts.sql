-- Avisos de custo ao admin (teto diário por serviço e saldo pré-pago dos provedores).
-- cost_alert é a caixa de saída: no máximo UMA linha por (alerta, limiar, período), e a
-- linha só sai de PENDING depois que o provedor aceitou o envio — sobrevive a restart.
CREATE TABLE cost_alert (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    alert_key         VARCHAR(64)  NOT NULL,
    threshold         INTEGER      NOT NULL,
    period_key        VARCHAR(32)  NOT NULL,
    headline          VARCHAR(500) NOT NULL,
    detail            TEXT         NOT NULL,
    status            VARCHAR(24)  NOT NULL,
    whatsapp_attempts INTEGER      NOT NULL DEFAULT 0,
    whatsapp_sent_at  TIMESTAMP WITH TIME ZONE,
    email_sent_at     TIMESTAMP WITH TIME ZONE,
    next_attempt_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_cost_alert_key_threshold_period UNIQUE (alert_key, threshold, period_key)
);

CREATE INDEX idx_cost_alert_status_next_attempt ON cost_alert (status, next_attempt_at);

-- Última leitura de saldo de cada provedor pré-pago e a referência do "80%"
-- (o saldo logo após a última recarga detectada).
CREATE TABLE provider_balance_state (
    provider          VARCHAR(32) PRIMARY KEY,
    reference_balance NUMERIC(14,4) NOT NULL,
    reference_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_balance      NUMERIC(14,4) NOT NULL,
    last_checked_at   TIMESTAMP WITH TIME ZONE NOT NULL
);
