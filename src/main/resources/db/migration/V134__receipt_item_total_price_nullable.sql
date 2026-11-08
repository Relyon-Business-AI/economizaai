-- Fotos de nota podem ter uma linha cujo PREÇO o modelo não conseguiu ler (papel desbotado,
-- coluna cortada). Antes isso estourava o NOT NULL de total_price no meio da extração async,
-- deixando a nota presa em PROCESSING até o sweeper força-falhar. Agora o item entra com preço
-- nulo, aparece na revisão, e o usuário define o preço ou remove o item antes de confirmar
-- (a confirmação bloqueia item sem preço). Notas da SEFAZ sempre trazem preço — nada muda pra elas.
ALTER TABLE receipt_items ALTER COLUMN total_price DROP NOT NULL;
