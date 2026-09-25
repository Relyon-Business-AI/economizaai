-- AI assist layer (Fase 1: sweep com revisão humana).
-- ai_findings: achados propostos pela IA aguardando aprovação/rejeição do admin.
-- ai_usage_log: cada chamada à API de IA, com tokens e custo estimado, rotulada
--   por atividade — alimenta o painel de gastos.
-- ai_sweep_runs: histórico de varreduras (status/contagens) pro admin acompanhar.

CREATE TABLE ai_sweep_runs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    status        VARCHAR(20) NOT NULL,          -- RUNNING | DONE | FAILED
    findings      INT NOT NULL DEFAULT 0,
    error         VARCHAR(500),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE TABLE ai_findings (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sweep_run_id   UUID NOT NULL,
    type           VARCHAR(30) NOT NULL,          -- MISSING_RULE | MISSING_BRAND | SUSPECT_CATEGORY | DUPLICATE | CONSENSUS_REVIEW | MERCHANT_REVIEW | FRIENDLY_NAME | ANOMALY
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | APPROVED | REJECTED
    title          VARCHAR(300) NOT NULL,         -- resumo de uma linha pro admin
    detail         TEXT,                          -- explicação/raciocínio da IA
    payload        TEXT NOT NULL,                 -- JSON com a proposta aplicável (chaves dependem do type)
    confidence     NUMERIC(4,3),                  -- 0..1 auto-reportada pela IA
    activity       VARCHAR(40),                   -- atividade que gerou (liga achado ao custo no painel)
    applied_at     TIMESTAMP WITH TIME ZONE,      -- quando a aprovação foi aplicada
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_findings_status_type ON ai_findings (status, type);
CREATE INDEX idx_ai_findings_sweep ON ai_findings (sweep_run_id);

CREATE TABLE ai_usage_log (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    activity       VARCHAR(40) NOT NULL,          -- RULE_SUGGESTION | BRAND_SUGGESTION | CATEGORY_REVIEW | DUPLICATE_JUDGE | CONSENSUS_JUDGE | MERCHANT_CLASSIFY | FRIENDLY_NAMES | ANOMALY_SCAN | TEST_CLASSIFY
    model          VARCHAR(60) NOT NULL,
    input_tokens   INT NOT NULL DEFAULT 0,
    output_tokens  INT NOT NULL DEFAULT 0,
    cost_usd       NUMERIC(10,6) NOT NULL DEFAULT 0,  -- estimado pela tabela de preços local
    success        BOOLEAN NOT NULL DEFAULT true,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_usage_activity_time ON ai_usage_log (activity, created_at);
CREATE INDEX idx_ai_usage_time ON ai_usage_log (created_at);
