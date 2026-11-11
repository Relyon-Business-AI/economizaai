-- A scan stranded in PROCESSING (restart, long queue wait) gets ONE re-queue before the
-- sweeper fails it; this flag records that the second chance was already used.
ALTER TABLE receipts ADD COLUMN stranded_requeued BOOLEAN NOT NULL DEFAULT FALSE;
