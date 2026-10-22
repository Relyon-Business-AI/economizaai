-- Records the provider's result code per paid call so we stop being blind to WHY
-- a call failed (e.g. Infosimples 607 "parâmetro inválido" = corrupted chave vs 612
-- "não retornou dados" = genuinely absent vs 609 provider instability). Nullable:
-- older rows and non-coded failures (I/O errors, timeouts) carry null.
ALTER TABLE paid_api_call ADD COLUMN result_code INTEGER;
