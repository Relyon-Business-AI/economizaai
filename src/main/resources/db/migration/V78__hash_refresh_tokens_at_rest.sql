-- Refresh tokens were stored raw; a DB dump would yield 30-day sessions for
-- every user. Store the SHA-256 hex instead (same at-rest scheme as the
-- password-reset / e-mail-verification codes). Hashing the existing rows keeps
-- every outstanding token valid — the service now looks up by sha256(presented).
UPDATE refresh_tokens
SET token = encode(sha256(token::bytea), 'hex');
