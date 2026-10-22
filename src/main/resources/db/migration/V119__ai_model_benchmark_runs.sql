-- Rodadas de benchmark de acurácia por modelo (golden set via LLM).
CREATE TABLE ai_model_benchmark_runs (
    id            UUID PRIMARY KEY,
    model         VARCHAR(80)  NOT NULL,
    provider      VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    sample_size   INTEGER      NOT NULL,
    correct       INTEGER      NOT NULL DEFAULT 0,
    uncategorized INTEGER      NOT NULL DEFAULT 0,
    accuracy_pct  NUMERIC(5,1),
    error         VARCHAR(500),
    finished_at   TIMESTAMP WITH TIME ZONE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);
