-- Idempotency claim for the "import finished" notification: stores the
-- updatedAt of the newest terminal IMPORT receipt the user was already
-- notified about. A later import wave produces newer terminal rows and earns
-- a fresh notification; re-checking the same finished wave stays silent.
ALTER TABLE users
    ADD COLUMN import_completion_notified_at TIMESTAMP WITH TIME ZONE;
