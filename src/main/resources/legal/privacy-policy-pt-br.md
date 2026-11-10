# Política de Privacidade — Economiza AI

**Versão 1.0** — vigente desde 2026-05-01.

> Esta é uma versão inicial. Antes do lançamento público, o documento
> deve ser revisado por um advogado especializado em LGPD.

## 1. Quem somos

O Economiza AI é um aplicativo de inteligência de preços baseado em NFC-e
brasileiras. Esta política descreve como coletamos, usamos, armazenamos
e protegemos seus dados pessoais, em conformidade com a Lei Geral de
Proteção de Dados (LGPD — Lei 13.709/2018).

## 2. Quais dados coletamos

**Dados que você fornece:**
- Nome, e-mail e senha (criptografada).
- Tamanho do domicílio (opcional, futuro).
- Preferências de produto (opcional, futuro).

**Dados gerados pelo seu uso:**
- Histórico de notas fiscais que você submete (NFC-e), incluindo
  mercado, data, itens, quantidades e preços.
- O HTML cru retornado pelo portal da SEFAZ é armazenado para permitir
  reprocessamento futuro do parsing.
- O CPF, quando presente na nota, é **mascarado antes de qualquer
  gravação** (regex sweep automático).

## 3. Bases legais

- **Execução de contrato (Art. 7º, V LGPD):** dados necessários para
  prestação do serviço (cadastro, autenticação, histórico pessoal).
- **Consentimento (Art. 7º, I LGPD):** contribuição anonimizada para a
  base colaborativa de preços. Você pode revogar a qualquer momento via
  `PATCH /api/v1/users/me/contribution { "contributionOptIn": false }`.

## 4. Compartilhamento

- **Membros do mesmo domicílio:** veem o histórico consolidado das
  notas submetidas por qualquer membro.
- **Base colaborativa de preços (futuro):** se você optar por
  contribuir, dados de preço (produto, mercado, data, valor unitário)
  são gravados em uma tabela separada **sem qualquer vínculo com seu
  identificador**. Consultas públicas a essa base são protegidas por
  k-anonimidade (mínimo de 3 contribuintes distintos por consulta).
- **Terceiros:** nenhum dado pessoal é compartilhado com terceiros sem
  o seu consentimento explícito.

## 5. Seus direitos (Art. 18 LGPD)

Você pode exercer os seguintes direitos a qualquer momento:

| Direito | Como exercer |
|---|---|
| Confirmação e acesso | `GET /api/v1/users/me` |
| Exportação de dados | `GET /api/v1/users/me/export` |
| Correção de dados | `PUT /api/v1/users/me` |
| Eliminação | `DELETE /api/v1/users/me` |
| Revogar contribuição colaborativa | `PATCH /api/v1/users/me/contribution` |

## 6. Retenção

- Dados pessoais são mantidos enquanto sua conta estiver ativa.
- Ao deletar a conta, dados pessoais e histórico individual são
  removidos imediatamente.
- Dados já anonimizados (sem vínculo com você) podem permanecer na
  base colaborativa indefinidamente.

## 7. Segurança

- Senhas armazenadas com BCrypt.
- Conexão sempre via HTTPS.
- CPF removido antes da persistência.
- JWTs com expiração de 24 horas.

## 8. Encarregado pelo Tratamento (DPO)

A definir antes do lançamento público.

## 9. Alterações

Esta política pode ser atualizada. Você será notificado e deverá
aceitar a nova versão para continuar utilizando o serviço.
