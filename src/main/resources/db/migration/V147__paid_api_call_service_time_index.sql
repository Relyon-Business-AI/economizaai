-- Serve a soma diária por serviço (teto diário por serviço), consultada a cada chamada paga.
CREATE INDEX IF NOT EXISTS idx_paid_api_call_service_time
    ON paid_api_call (service, created_at);
