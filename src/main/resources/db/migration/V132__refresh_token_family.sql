-- Refresh-token "família" = a cadeia de rotações que nasce de UM login. A detecção de reuso
-- passa a revogar apenas a família afetada (um dispositivo), não TODAS as sessões do usuário —
-- antes, um tropeço no web (ex.: 2 abas) derrubava o app junto. Tokens antigos viram cada um a
-- sua própria família (family_id = id), então nenhuma sessão existente é invalidada por isto.
ALTER TABLE refresh_tokens ADD COLUMN family_id UUID;
UPDATE refresh_tokens SET family_id = id WHERE family_id IS NULL;
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
