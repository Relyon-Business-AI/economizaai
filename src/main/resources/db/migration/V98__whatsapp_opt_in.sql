-- WhatsApp opt-in for users (reverse opt-in: user messages our WhatsApp with a token,
-- the inbound webhook links the number + flips opt-in). Separate from phone_number (SMS).
ALTER TABLE users ADD COLUMN whatsapp_number VARCHAR(20);
ALTER TABLE users ADD COLUMN whatsapp_opt_in BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN whatsapp_opt_in_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE users ADD COLUMN whatsapp_link_token VARCHAR(64);
ALTER TABLE users ADD COLUMN whatsapp_weekly_report_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- The inbound webhook resolves a user by the token in "CONFIRMAR-<token>" / "RELATORIO-<token>".
CREATE UNIQUE INDEX idx_users_whatsapp_link_token ON users(whatsapp_link_token) WHERE whatsapp_link_token IS NOT NULL;
