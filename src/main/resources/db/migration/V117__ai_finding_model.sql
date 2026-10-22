-- Atribui cada achado ao MODELO de IA que o produziu — permite comparar
-- qualidade (taxa de aceitação) entre modelos/provedores ao longo do tempo.
-- Achados antigos ficam NULL (produzidos antes do rastreio).
ALTER TABLE ai_findings ADD COLUMN model VARCHAR(80);
