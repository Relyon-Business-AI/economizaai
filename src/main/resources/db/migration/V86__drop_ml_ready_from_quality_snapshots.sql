-- Retira a coluna ml_ready dos snapshots de qualidade. A camada de "modelo ML"
-- (classificador treinado em shadow) foi rascunhada mas NUNCA treinada/ativada e
-- está sendo aposentada — a coluna sempre foi false e não alimentava nada real.
-- A IA em uso de fato (LLM/Anthropic) e a auto-promoção do dicionário continuam.
ALTER TABLE categorization_quality_snapshots DROP COLUMN IF EXISTS ml_ready;
ALTER TABLE categorization_quality_snapshots DROP COLUMN IF EXISTS ml_accuracy_pct;
