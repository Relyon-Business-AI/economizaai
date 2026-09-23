-- Channel = onde a compra aconteceu: IN_STORE (loja física) x ONLINE (compra remota).
-- Derivado do indPres da nota (parseado no ingest); ortogonal ao segmento (mercado/outras).
-- Preciso pra, no futuro, um índice colaborativo separado por canal (online x físico).
ALTER TABLE receipts ADD COLUMN channel VARCHAR(10) NOT NULL DEFAULT 'IN_STORE';

-- Backfill das notas já existentes por MODELO fiscal (aproximação): NFC-e (65) é sempre
-- presencial → IN_STORE (o default já cobre); NF-e (55) de consumidor é, na prática, compra
-- online → ONLINE. (Os raros NF-e 55 B2B ficariam ONLINE por engano, mas não entram no índice
-- de mercado de qualquer forma. Ingests novos usam o indPres exato do parser.)
UPDATE receipts SET channel = 'ONLINE' WHERE SUBSTRING(chave_acesso FROM 21 FOR 2) = '55';
