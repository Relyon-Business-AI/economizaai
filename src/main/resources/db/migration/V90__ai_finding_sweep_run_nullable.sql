-- Findings nascidos do fallback de scan em tempo real não pertencem a nenhum
-- sweep run. O NOT NULL fazia o INSERT do finding estourar no flush e derrubar
-- a transação inteira, perdendo a categorização que a IA já tinha pago.
ALTER TABLE ai_findings ALTER COLUMN sweep_run_id DROP NOT NULL;
