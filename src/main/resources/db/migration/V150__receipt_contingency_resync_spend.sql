-- Contingency resync spends only on its last attempt (auditoria, item 9a).
-- resync_free_only: the next queued re-attempt must use free paths only (set by the resync
--   sweeper, cleared by the import worker when it dispatches the attempt).
-- contingency_paid_attempted: the single paid attempt of the resync window was already granted.
ALTER TABLE receipts ADD COLUMN resync_free_only BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE receipts ADD COLUMN contingency_paid_attempted BOOLEAN NOT NULL DEFAULT FALSE;
