-- Latência (ms) de cada chamada de IA — permite comparar VELOCIDADE entre modelos.
-- Null nas chamadas antigas (antes do rastreio).
ALTER TABLE ai_usage_log ADD COLUMN duration_ms INTEGER;
