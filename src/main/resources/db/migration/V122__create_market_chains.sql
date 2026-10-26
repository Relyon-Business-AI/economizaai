-- Market chains: one row per supermarket network (cnpj_root = first 8 digits).
CREATE TABLE market_chains (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cnpj_root     VARCHAR(8) NOT NULL UNIQUE,
    name          VARCHAR(255) NOT NULL,
    logo_url      TEXT,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- Link market_locations to their chain.
ALTER TABLE market_locations ADD COLUMN chain_id UUID REFERENCES market_chains(id);

-- Seed chains from existing market_locations distinct cnpj_roots.
INSERT INTO market_chains (cnpj_root, name)
SELECT DISTINCT ml.cnpj_root, COALESCE(ml.name, ml.cnpj_root)
FROM market_locations ml
WHERE ml.cnpj_root IS NOT NULL
ON CONFLICT (cnpj_root) DO NOTHING;

-- Back-link every market_location to its chain.
UPDATE market_locations ml
SET chain_id = mc.id
FROM market_chains mc
WHERE ml.cnpj_root = mc.cnpj_root
  AND ml.chain_id IS NULL;
