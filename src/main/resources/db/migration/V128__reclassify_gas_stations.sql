-- Novo segmento GAS_STATION (CNAE primário 4731*/4732*): postos saem da fila de
-- revisão (que só lista OTHER/UNKNOWN) e ficam marcados à parte como insumo da
-- futura feature de preço de combustível. Reclassifica os já escaneados — o
-- cnae_codes guarda o CNAE PRIMÁRIO primeiro, então o prefixo do campo decide.
-- O support_override existente (ex.: postos já bloqueados na moderação) é
-- preservado; ele e o segment são independentes.

UPDATE market_locations
SET segment = 'GAS_STATION', updated_at = now()
WHERE segment IN ('OTHER', 'UNKNOWN')
  AND (cnae_codes LIKE '4731%' OR cnae_codes LIKE '4732%');
