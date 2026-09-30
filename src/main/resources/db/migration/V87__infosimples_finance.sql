-- Infosimples account finance tracking: daily snapshots of the live account
-- (GET /api/admin/account) and a per-month ledger (recarga / consumo cobrado /
-- varrido pela franquia). History rows for jul-set/2026 are seeded from the
-- painel extratos (financas/extratos, lido em 2026-09-30) — the API exposes no
-- history, so past months can only come from there; future months close
-- automatically from the snapshots.

CREATE TABLE infosimples_account_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    taken_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    balance NUMERIC(12,2),
    current_usage NUMERIC(12,2),
    min_bill NUMERIC(12,2),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
CREATE INDEX idx_infosimples_snapshot_taken_at ON infosimples_account_snapshot (taken_at DESC);

CREATE TABLE infosimples_month_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    month CHAR(7) NOT NULL UNIQUE,          -- '2026-09'
    recarga NUMERIC(12,2) NOT NULL DEFAULT 0,
    consumo NUMERIC(12,2),                  -- null while the month is open
    varrido NUMERIC(12,2),                  -- franchise sweep on day 1 of the NEXT month; null while open
    closed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Seed from the painel extratos (source of truth for the pre-tracking era).
INSERT INTO infosimples_month_history (month, recarga, consumo, varrido, closed) VALUES
    ('2026-07', 100.00, 11.36, 88.64, TRUE),   -- cadastro + 1ª recarga 01/07; varrido em 01/08
    ('2026-08', 0.00, 0.00, 0.00, TRUE),       -- sem saldo o mês todo (era Infosimples morta)
    ('2026-09', 100.00, NULL, NULL, FALSE);    -- recarga 21/09; fecha em 01/10
