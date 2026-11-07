-- =============================================================
-- Script: Corrigir email_verified para usuários de login social
-- =============================================================
-- Usuários que entraram via Google/Apple já tiveram o email
-- verificado pelo provider. Este script corrige registros
-- antigos que ficaram com email_verified = false.
--
-- INSTRUÇÕES:
--   1. Rode o bloco DIAGNÓSTICO primeiro (SELECT) para ver o impacto
--   2. Rode o bloco CORREÇÃO (UPDATE) se os números estiverem corretos
--   3. Rode o DIAGNÓSTICO de novo para confirmar
-- =============================================================

-- =====================
-- 1. DIAGNÓSTICO
-- =====================

-- Quantos usuários sociais existem e quantos estão sem verificação?
SELECT
    auth_provider,
    COUNT(*)                                          AS total,
    COUNT(*) FILTER (WHERE email_verified = true)     AS verificados,
    COUNT(*) FILTER (WHERE email_verified = false)    AS nao_verificados
FROM users
WHERE auth_provider IN ('GOOGLE', 'APPLE')
GROUP BY auth_provider
ORDER BY auth_provider;

-- Lista dos usuários sociais NÃO verificados (para conferência)
SELECT id, name, email, auth_provider, email_verified, email_verified_at, created_at
FROM users
WHERE auth_provider IN ('GOOGLE', 'APPLE')
  AND email_verified = false
ORDER BY created_at;

-- Visão geral do funil: todos os providers
SELECT
    auth_provider,
    COUNT(*)                                          AS total,
    COUNT(*) FILTER (WHERE email_verified = true)     AS verificados,
    COUNT(*) FILTER (WHERE email_verified = false)    AS nao_verificados
FROM users
WHERE excluded_from_metrics = false
GROUP BY auth_provider
ORDER BY auth_provider;


-- =====================
-- 2. CORREÇÃO
-- =====================

-- Seta email_verified = true e email_verified_at = created_at
-- para todos os usuários Google/Apple que estão com false.
-- Usa created_at como timestamp de verificação porque o provider
-- já tinha verificado no momento do cadastro.
UPDATE users
SET email_verified    = true,
    email_verified_at = created_at
WHERE auth_provider IN ('GOOGLE', 'APPLE')
  AND email_verified = false;

-- Resultado esperado: UPDATE N (onde N = total de nao_verificados do diagnóstico)
