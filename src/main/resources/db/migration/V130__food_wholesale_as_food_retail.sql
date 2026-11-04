-- Atacado especializado de alimentos (CNAE primário 463*) agora classifica como
-- FOOD_RETAIL (mesma decisão do 4691/atacarejo): o dono aprovou todos os casos
-- reais da fila com esse perfil (Marquesa, Adição, Center Sul, Padre Cícero).
-- Reclassifica os já escaneados que caíram em OTHER/UNKNOWN — inclusive os que o
-- dono já tinha promovido via override SUPPORTED (ficavam na lente "Outras" por
-- segmento errado). BLOCKED nunca é tocado. cnae_codes é ordenado com o CNAE
-- PRIMÁRIO primeiro, então o LIKE equivale à regra do classificador.
UPDATE market_locations
SET segment = 'FOOD_RETAIL',
    segment_classified_at = now()
WHERE segment IN ('OTHER', 'UNKNOWN')
  AND cnae_codes LIKE '463%'
  AND (support_override IS NULL OR support_override = 'SUPPORTED');
