-- The portal HTML/XML kept as evidence of a receipt moves out of the receipts row, so loading
-- a receipt (or its items) no longer carries the whole document along.
-- receipts.raw_html is intentionally left in place (no longer read or written by the app) as a
-- rollback safety net; a later migration drops it.
CREATE TABLE receipt_raw_html (
    receipt_id UUID PRIMARY KEY REFERENCES receipts (id) ON DELETE CASCADE,
    raw_html   TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

INSERT INTO receipt_raw_html (receipt_id, raw_html, created_at, updated_at)
SELECT id, raw_html, created_at, updated_at
FROM receipts
WHERE raw_html IS NOT NULL;
