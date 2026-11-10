-- Cache de resolução de TIPO de comparação para nomes de catálogo (Open Food Facts) que não
-- casam direto com o nosso catálogo pt (ex.: OFF guarda "White Rice" para um arroz brasileiro).
-- ProductTypeResolver resolve esse nome órfão para um generic_name_norm existente (mapa estático
-- ou IA closed-set) e grava aqui, de modo que cada EAN custe no máximo UMA chamada de IA.
-- Nullable: ausente = ainda não resolvido (ou resolução deu NONE → segue estado vazio honesto).
ALTER TABLE ean_catalog ADD COLUMN resolved_generic_name_norm VARCHAR(160);
