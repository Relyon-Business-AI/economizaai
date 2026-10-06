-- Merchant accounts Fase 1.5 + 2 (docs/MERCHANT_ACCOUNTS.md):
--   merchant_claims        — self-serve "sou este mercado" (código no e-mail do CNPJ ou fila admin)
--   merchant_promos        — promoções publicadas pelo lojista. TABELA SEPARADA do índice
--                            colaborativo de propósito: promo ANUNCIADA nunca vira observação
--                            de preço nem altera o ranking de "mais barato".
--   merchant_subscriptions — assinatura da conta de marketing por rede (grátis até a data
--                            promocional; pagamento ainda INERTE — ver DEV_NOTES.md).

CREATE TABLE merchant_claims (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    cnpj VARCHAR(14) NOT NULL,
    cnpj_root VARCHAR(8) NOT NULL,
    company_name VARCHAR(255),
    contact_phone VARCHAR(20),
    status VARCHAR(20) NOT NULL,
    -- verification code sent to the company e-mail registered at the Receita (BrasilAPI):
    -- only the SHA-256 hash is stored; compared constant-time with a small attempt budget.
    verification_code_hash VARCHAR(64),
    verification_email_masked VARCHAR(255),
    code_expires_at TIMESTAMP,
    code_attempts INT NOT NULL DEFAULT 0,
    rejection_reason TEXT,
    reviewed_by_user_id UUID,
    resolved_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_merchant_claims_user ON merchant_claims(user_id);
CREATE INDEX idx_merchant_claims_status ON merchant_claims(status);
-- one OPEN claim per (user, chain) at a time
CREATE UNIQUE INDEX uq_merchant_claims_open ON merchant_claims(user_id, cnpj_root)
    WHERE status IN ('AWAITING_CODE', 'PENDING_REVIEW');

CREATE TABLE merchant_promos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cnpj_root VARCHAR(8) NOT NULL,
    created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    product_id UUID REFERENCES products(id) ON DELETE SET NULL,
    ean VARCHAR(14) NOT NULL,
    description VARCHAR(255),
    promo_price NUMERIC(12,2) NOT NULL,
    regular_price NUMERIC(12,2),
    starts_at DATE NOT NULL,
    ends_at DATE NOT NULL,
    source VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_merchant_promos_chain ON merchant_promos(cnpj_root);
CREATE INDEX idx_merchant_promos_ean ON merchant_promos(ean);
CREATE INDEX idx_merchant_promos_window ON merchant_promos(starts_at, ends_at);

CREATE TABLE merchant_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cnpj_root VARCHAR(8) NOT NULL UNIQUE,
    plan VARCHAR(20) NOT NULL DEFAULT 'MARKETING',
    status VARCHAR(20) NOT NULL,
    free_until DATE,
    activated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
