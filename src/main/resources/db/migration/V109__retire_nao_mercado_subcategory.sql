-- Aposenta a subcategoria "Outros › Não-mercado": na prática ela virou um balde fácil
-- onde o LLM jogava itens de uso doméstico (pilha, lâmpada, fósforo, luvas…) que deviam
-- ir pra "Casa e descartáveis › Utilidades/Descartáveis". Além disso ela é redundante com
-- a própria categoria "Outros" — o que realmente não se encaixa já fica no grupo Outros.
--
-- Não apagamos a linha (preserva FK/histórico): desativamos. O classificador só oferece
-- folhas ATIVAS (findAllByActiveTrueOrderBySortOrderAsc), então a IA para de sugerir;
-- o endpoint da taxonomia idem.

-- 1) Produtos que já estavam na folha nao-mercado caem pro grupo "Outros" (o catch-all).
UPDATE products
   SET category_id = (SELECT id FROM categories WHERE slug = 'outros'),
       updated_at  = now()
 WHERE category_id = (SELECT id FROM categories WHERE slug = 'nao-mercado');

-- 2) Rejeita as sugestões PENDENTES que apontavam pra nao-mercado (não devem ser aceitas
--    num nó morto; a próxima varredura reclassifica esses produtos).
UPDATE ai_findings
   SET status = 'REJECTED'
 WHERE status = 'PENDING'
   AND type = 'SUSPECT_CATEGORY'
   AND payload LIKE '%"categoryNodeSlug":"nao-mercado"%';

-- 3) Desativa a subcategoria.
UPDATE categories
   SET active = false,
       updated_at = now()
 WHERE slug = 'nao-mercado';
