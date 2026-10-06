-- Merchant accounts (docs/MERCHANT_ACCOUNTS.md): a MERCHANT user is granted
-- access to a CHAIN via its cnpj_root (first 8 CNPJ digits) — one grant covers
-- every store of the chain. N:N: a user can manage several chains, a chain can
-- have several users. Grants are admin-managed in the MVP (no self-serve claim).

CREATE TABLE merchant_access (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    cnpj_root VARCHAR(8) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_merchant_access_user_chain UNIQUE (user_id, cnpj_root)
);

CREATE INDEX idx_merchant_access_user ON merchant_access(user_id);
CREATE INDEX idx_merchant_access_cnpj_root ON merchant_access(cnpj_root);
