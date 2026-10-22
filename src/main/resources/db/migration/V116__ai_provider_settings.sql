-- Provedor + modelo por tier de IA, editável no admin em runtime. Seeds = o que
-- já roda hoje (Anthropic nos dois tiers); trocar pra OpenAI é um UPDATE via admin.
CREATE TABLE ai_provider_settings (
    id          UUID PRIMARY KEY,
    tier        VARCHAR(20)  NOT NULL UNIQUE,
    provider    VARCHAR(20)  NOT NULL,
    model       VARCHAR(80)  NOT NULL,
    updated_by  VARCHAR(255),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

INSERT INTO ai_provider_settings (id, tier, provider, model) VALUES
    (gen_random_uuid(), 'EXTRACTOR', 'ANTHROPIC', 'claude-haiku-4-5'),
    (gen_random_uuid(), 'CURATOR',   'ANTHROPIC', 'claude-sonnet-4-6');
