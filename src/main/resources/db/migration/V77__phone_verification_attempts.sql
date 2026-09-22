-- Failed-guess counter for phone OTPs, mirroring password_reset_tokens.attempts:
-- the code locks after a small budget of wrong guesses instead of relying on the
-- 10^6 code space alone.
ALTER TABLE phone_verification_tokens
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
