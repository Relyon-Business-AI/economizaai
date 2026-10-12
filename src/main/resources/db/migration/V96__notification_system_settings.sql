-- Configuração (via dashboard) das notificações de SISTEMA — o que era
-- hardcoded (cron + textos i18n) vira linha editável. Os jobs leem daqui a
-- cada execução; template NULL = usa o default do i18n.
CREATE TABLE notification_system_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job VARCHAR(40) NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    -- Hora local (America/Sao_Paulo) pros jobs com horário fixo.
    send_hour INTEGER,
    -- ISO 1=seg..7=dom (job semanal).
    day_of_week INTEGER,
    -- 1..28 (job mensal; cap em 28 pra existir em todo mês).
    day_of_month INTEGER,
    -- Overrides de template (placeholders {0},{1}... iguais ao i18n). NULL = default.
    title_pt TEXT,
    title_en TEXT,
    body_pt TEXT,
    body_en TEXT,
    -- Claim anti-duplicata do tick horário (e anti-corrida entre instâncias).
    last_run_date DATE,
    updated_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

INSERT INTO notification_system_settings (job, enabled, send_hour, day_of_week, day_of_month) VALUES
    ('WEEKLY_DIGEST',  TRUE, 8, 1, NULL),
    ('MONTHLY_DIGEST', TRUE, 8, NULL, 1),
    ('DEALS_DIGEST',   TRUE, NULL, NULL, NULL),
    ('BUDGET_ALERT',   TRUE, NULL, NULL, NULL),
    ('REPLENISHMENT',  TRUE, NULL, NULL, NULL);
