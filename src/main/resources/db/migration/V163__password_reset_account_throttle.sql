-- Password-reset guesses are now also counted per ACCOUNT (not only per code):
-- wrong guesses in the current window, when the window started, and until when
-- verification is refused after too many of them.
ALTER TABLE users
    ADD COLUMN password_reset_failed_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN password_reset_failed_window_started_at TIMESTAMP,
    ADD COLUMN password_reset_locked_until TIMESTAMP;

-- The per-account request ceiling counts codes issued in the last hour.
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user_created
    ON password_reset_tokens (user_id, created_at);
