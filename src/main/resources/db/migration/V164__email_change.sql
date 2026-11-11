-- E-mail change with verification: the pending address and the hashed code sent to it.
-- The account e-mail itself only changes when the code is confirmed.
ALTER TABLE users
    ADD COLUMN pending_email VARCHAR(255),
    ADD COLUMN email_change_code_hash VARCHAR(255),
    ADD COLUMN email_change_expires_at TIMESTAMP,
    ADD COLUMN email_change_attempts INT NOT NULL DEFAULT 0;
