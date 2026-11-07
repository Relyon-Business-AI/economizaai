-- Usuários que entraram via Google/Apple já tiveram o email verificado
-- pelo provider, mas alguns registros antigos ficaram com email_verified = false.
UPDATE users
SET email_verified    = true,
    email_verified_at = created_at
WHERE auth_provider IN ('GOOGLE', 'APPLE')
  AND email_verified = false;
