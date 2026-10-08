-- Marks a contingency note that is in the auto-resync loop, so the ingest success path knows to
-- notify the owner that their note (stuck "aguardando SEFAZ") was recovered. Set by the
-- ContingencyResyncSweeper when it re-queues a CONTINGENCY_PENDING note; cleared on recovery/give-up.
ALTER TABLE receipts ADD COLUMN resyncing_from_contingency BOOLEAN NOT NULL DEFAULT FALSE;
