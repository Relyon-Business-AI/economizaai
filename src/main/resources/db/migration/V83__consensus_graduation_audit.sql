-- Audit trail for consensus graduations: which households' corrections promoted
-- which product to which category. Before this, a bad consensus (2-3 users wrong
-- together, or gamed) was irreversible because nobody knew who voted. Rows are
-- append-only history — one per graduation event.
CREATE TABLE consensus_graduation_audit (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id    UUID NOT NULL,
    category      VARCHAR(30) NOT NULL,
    household_ids TEXT NOT NULL, -- comma-separated household UUIDs that voted for the winning category
    votes         INT NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_consensus_audit_product ON consensus_graduation_audit (product_id);
