-- Log append-only de cada escaneamento de código de barras (fluxo admin "Comparar preço").
-- Uma linha por scan: o EAN lido, se casou com um produto exato nosso, o tipo genérico
-- (generic_name_norm, ex.: "creme de leite") e um retrato da localização resolvida pela
-- MESMA cadeia "perto de você" (explícita → estimada pelas notas → UF). Alimenta histórico,
-- analytics (o que o povo escaneia, taxa de match) e prioridade de onboarding de produtos.
-- LGPD: guarda só o ponto de referência já usado nos recortes públicos; sem dado sensível.
CREATE TABLE product_scan (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    household_id UUID NOT NULL REFERENCES households(id) ON DELETE CASCADE,
    ean VARCHAR(14) NOT NULL,
    -- EXACT = existe Product com esse EAN; GENERIC = EAN no catálogo mas sem Product
    -- canônico; NONE = EAN desconhecido.
    match_type VARCHAR(20) NOT NULL,
    matched_product_id UUID REFERENCES products(id) ON DELETE SET NULL,
    -- Chave do tipo de produto (Product.generic_name_norm), ex.: "creme de leite".
    generic_name_norm VARCHAR(160),
    -- Retrato da localização no momento do scan (cadeia ViewerLocationService).
    latitude NUMERIC(9,6),
    longitude NUMERIC(9,6),
    uf VARCHAR(2),
    location_source VARCHAR(20),
    scanned_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_scan_user ON product_scan(user_id, scanned_at DESC);
CREATE INDEX idx_product_scan_ean ON product_scan(ean);
CREATE INDEX idx_product_scan_generic ON product_scan(generic_name_norm) WHERE generic_name_norm IS NOT NULL;
