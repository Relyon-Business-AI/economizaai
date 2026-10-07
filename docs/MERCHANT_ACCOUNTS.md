# Merchant Accounts — contas de mercado (design & estado)

**Status (2026-10-06, 2ª leva):** Fases 1, 1.5 e 2 implementadas no backend —
perfil + mini-painel, **claim self-serve**, **promos (manual + CSV/XLSX + batch JSON)**
e **assinatura de marketing** (grátis até 31/12/2026). Tudo **invisível para o
consumidor**: o único touchpoint de usuário (feed patrocinado) está atrás de flag
default OFF; o resto é gated por role. Pagamento segue INERTE (DEV_NOTES.md).
Este doc é a fonte única: visão, decisões, o que existe e como continuar.

---

## 1. A visão (north star)

Atrair os mercados para dentro do app e monetizá-los com uma **conta de marketing
mensal** onde eles publicam as próprias promoções. O argumento de venda NÃO é alcance
(Instagram/encarte fazem isso mais barato) — é o que só nós temos:

- **Promoção verificada**: o mercado anuncia "leite R$ 4,99" e cupons NFC-e reais
  escaneados confirmam — selo "preço verificado". Nenhum canal de mídia no Brasil faz isso.
- **Atribuição fechada**: promo exibida → usuário compra → cupom escaneado prova a
  conversão. "Sua promo gerou X compras verificadas" justifica mensalidade de um jeito
  que banner não justifica.
- A mesma infra alimenta o produto B2B de "promo effectiveness" (MONETIZATION.md §2) —
  uma construção, dois clientes.

Registrado em **MONETIZATION.md §4b** (modelo de receita, pricing ballpark, guardrails).

## 2. Sequenciamento (ovo-e-galinha)

Mercado só paga com densidade de usuários na cidade dele. Então:

| Fase | O quê | Cobra? | Estado |
|---|---|---|---|
| **1. Perfil do Mercado** | Conta MERCHANT vinculada à rede (cnpj_root); mini-painel: minhas lojas + meus preços vs. a região (agregados k-anônimos) | Grátis (isca/lead-gen) | **Shipado (§4)** |
| **1.5 Claim self-serve** | "Sou este mercado": código no e-mail do CNPJ (Receita/BrasilAPI) com fallback de fila admin | Grátis | **Shipado (§4b)** |
| **2. Conta de marketing** | Publicar promos (manual/CSV/XLSX/API), selo "Patrocinado" + "verificado" no feed | Flat mensal por rede (R$99–299, validar) — **grátis até 31/12/2026 (promo de lançamento)** | **Shipado dark** (feed atrás de flag; pagamento INERTE) |
| 3. Relatório de efetividade | "Sua promo gerou X compras verificadas" | Upsell → ponte pro B2B caro | Não construído |

Sem CPC/leilão/ad-tech cedo — flat e simples.

## 3. Decisões travadas

- **Role `MERCHANT`** (não "MARKETING"): a conta representa o lojista — amanhã ela vê
  analytics, publica promo, responde dados. Terceiro valor do enum `Role` (USER/ADMIN/MERCHANT).
- **Vínculo por `cnpj_root`** (8 primeiros dígitos = a REDE): um grant dá acesso a todas
  as lojas da rede. Tabela `merchant_access` (user ↔ cnpj_root), N:N — um user pode
  gerir mais de uma rede, uma rede pode ter mais de um user.
- **Grant é admin-only no MVP** (sem fluxo self-serve de "reivindicar perfil" ainda).
  O fluxo de claim por CNPJ/e-mail do domínio é Fase 1.5, quando houver demanda real.
- **K-anonimato dos DOIS lados (K=3 households distintos)**: o merchant é um cliente
  B2B — os agregados que ele vê (inclusive sobre a PRÓPRIA loja) passam pelo mesmo
  gate `minHouseholdsForPublic` do índice público. Ver "própria loja" parece inócuo,
  mas com poucos cupons + timestamps permitiria correlacionar uma compra a um cliente.
  Linha que falha o gate de qualquer lado simplesmente não aparece.
- **Promo anunciada ≠ preço observado** (Fase 2): tabelas separadas; claim de lojista
  nunca entra no índice colaborativo como observação.
- **Promo paga nunca altera o ranking de "mais barato"** (Fase 2): mesmo guardrail do
  MONETIZATION.md §4 — esse ranking é o produto.

## 4. O que existe (MVP Fase 1, 2026-10-06)

Tudo backend, invisível pra USER comum (403 fora da role).

**Role & vínculo**
- `Role.MERCHANT` + migration `V91__merchant_access.sql` (tabela `merchant_access`).
- `MerchantAccess` (entity) / `MerchantAccessRepository`.

**Admin (cria/gerencia contas merchant — é assim que se cria o perfil de teste da Economizaai):**
- `PATCH /api/v1/admin/users/{id}/role` — USER ↔ MERCHANT (nunca toca/concede ADMIN;
  demotion revoga os grants).
- `GET/POST /api/v1/admin/users/{id}/merchant-access` — lista/concede `cnpjRoot` (exige role MERCHANT).
- `DELETE /api/v1/admin/users/{id}/merchant-access/{cnpjRoot}` — revoga.
- Service: `AdminMerchantAccessService`; role change em `AdminUserService.setRole`.

**Painel do merchant (`/api/v1/merchant/**` → `hasRole("MERCHANT")` no SecurityConfig):**
- `GET /api/v1/merchant/profile` — redes que o user gerencia + lojas conhecidas de cada
  rede (das `market_locations` já criadas pelos cupons) + nº de cupons por loja.
- `GET /api/v1/merchant/price-comparison` — por produto observado na rede (janela =
  `lookbackDays` do índice): mediana da rede vs. mediana da região (mesmo estado, índice
  inteiro, IN_STORE), delta %, contagens. Só emite linha que passa K=3 + min-observações
  nos dois lados. Service: `MerchantPanelService`.

**Infra reaproveitada:** `PriceObservation`/`PriceObservationAudit` (novas queries por
`cnpj_root` e por estado), `CollaborativeProperties.Collaborative` (mesmos thresholds),
padrão de mediana do `PriceIndexService`.

**Testes:** `MerchantPanelServiceTest` (inclui asserts de k-anonimato dos dois lados,
exigência do CLAUDE.md), `AdminMerchantAccessServiceTest`, casos de `setRole` no
`AdminUserServiceTest`.

## 4b. O que existe (Fases 1.5 + 2, 2026-10-06 — 2ª leva)

**Claim self-serve (`/api/v1/merchant-claims`, qualquer usuário autenticado):**
- `POST /merchant-claims` {cnpj} → BrasilAPI (CNPJ da Receita). Com e-mail da empresa
  registrado: código de 6 dígitos vai PRO E-MAIL DA EMPRESA (só quem controla a caixa
  aprova) — status `AWAITING_CODE`. Sem e-mail utilizável: `PENDING_REVIEW` + alerta
  pro admin. Segurança do código: só SHA-256 persiste, comparação constant-time
  (`CodeHasher`), 5 tentativas, TTL 24h; expirado/estourado fecha o claim (REJECTED)
  e o usuário resubmete.
- `POST /merchant-claims/{id}/verify` {code} → aprova: promove a MERCHANT, cria o
  grant da rede e abre a assinatura com a promo de lançamento.
- `GET /merchant-claims` — meus claims.
- Admin: `GET /admin/merchant-claims` (fila PENDING_REVIEW),
  `POST /admin/merchant-claims/{id}/approve|reject`.
- Infra estendida: `CnpjLookup` agora captura `email` + `razao_social` da BrasilAPI;
  `AuthEmailSender.sendMerchantClaimCode` (e-mail brandado, async, fallback DEV-log).

**Promos do lojista (`/api/v1/merchant/promos`, role MERCHANT):**
- CRUD manual + `POST /promos/import` (multipart **CSV ou XLSX** — export de tabela
  de preço do ERP, headers pt com aliases/acentos: ean, preco[_promocional],
  preco_normal?, inicio, fim, descricao?; preços "R$ 4,99"/"4.99"; datas ISO ou
  dd/MM/yyyy) + `POST /promos/batch` (JSON, integração ERP/API). Relatório por linha
  (linha boa importa, linha ruim vira erro localizado). Cap 500 linhas
  (`import-max-rows`). **Barcode ficou de fora de propósito**: lojista conhece os
  próprios EANs; scanner físico é fluxo de corredor, não de publicação.
- Validações: EAN 8–14 dígitos, preço > 0 (scale 2), fim ≥ início, janela duplicada
  por (rede, EAN) → 409. Match EAN → produto canônico no write.
- **`verifiedByReceipts`**: cupom NFC-e real da rede, dentro da janela, com preço ≤
  anunciado (tolerância 1%) — o selo "verificado", nosso diferencial.
- Moderação admin: `GET /admin/merchant-promos`, `PATCH /admin/merchant-promos/{id}/active`.

**Assinatura de marketing (`merchant_subscriptions`, por REDE):**
- Promo de lançamento: todo claim aprovado abre status `PROMO`, **grátis até
  `economizaai.merchant.free-until` (31/12/2026)** — mesma mecânica de data fixa do
  Premium consumer. `GET /merchant/subscription` devolve {status, freeUntil, active}
  → o FE mostra o modal "grátis até 31/12/2026" quando status=PROMO.
- Gate único `MerchantSubscriptionService.requirePublishing` em TODO write de promo
  (402 sem assinatura ativa). Painel (perfil/comparação) continua grátis — é a isca.
- **Pagamento INERTE**: pós-promo, ACTIVE é setado manualmente até plugar o Mercado
  Pago (entrada no DEV_NOTES.md).

**Feed patrocinado (único touchpoint de consumidor — INERTE):**
- `GET /price-index/sponsored-promos` — endpoint SEPARADO do `/promos` orgânico de
  propósito (guardrail estrutural: promo anunciada nunca entra no ranking orgânico).
  Devolve [] até `MERCHANT_PROMOS_FEED_ENABLED=true`. Só redes com assinatura ativa;
  cada item carrega `verified`. FE renderiza SEMPRE com selo "Patrocinado".

## 5. Como criar o perfil de teste da Economizaai (owner)

1. Registrar um usuário normal (e-mail `@economizaai.app`, ex. `merchant-test@…` —
   mas lembrar que só `alexandre@economizaai.app` recebe e-mail de verdade).
2. Como ADMIN: `PATCH /api/v1/admin/users/{id}/role` body `{"role":"MERCHANT"}`.
3. `POST /api/v1/admin/users/{id}/merchant-access` body `{"cnpjRoot":"<8 dígitos de uma
   rede real já escaneada>"}` (pegar um `market_cnpj_root` existente de
   `price_observations`, ex. via admin market-intel).
4. Logar com essa conta → `GET /api/v1/merchant/profile` e `/price-comparison`.

Requests prontos na collection Postman (pasta **Merchant**).

## 6. Próximos passos (não construídos)

1. **Ligar o feed** quando houver densidade/lojistas reais: `MERCHANT_PROMOS_FEED_ENABLED=true`
   + FE da seção "Patrocinado" no app.
2. **Pagamento**: plugar Mercado Pago na assinatura merchant quando a promo de
   lançamento acabar (31/12/2026) — hoje ACTIVE é manual (DEV_NOTES).
3. **Fase 3 — relatório de efetividade**: conversões atribuídas (k-anônimas) por promo
   ("sua promo gerou X compras verificadas") → ponte pro B2B.
4. Painel: evolução temporal (minha mediana vs. região por semana), share de cupons na
   cidade, produtos onde estou mais caro (oportunidade de promo).
5. Encarte PDF via OCR/LLM como canal extra de import (caro/impreciso — só com demanda).
6. **FE do portal do lojista — CONSTRUÍDO e NO AR (2026-10-06):**
   - **https://merchant.economizaai.app** — micro-SPA (Vite + React + TS) no repo
     `Relyon-Business-AI/economiza-ai-merchant` (local:
     `~/Documents/projects/economiza-ai-merchant`), servida como Cloudflare Worker
     `economiza-ai-merchant` (conta Relyon AI, custom domain criado pelo wrangler).
     Deploy manual: `npm run deploy` (tsc + vite build + wrangler, OAuth já logado).
     Deliberadamente leve/descartável — sem CI, sem Expo, até provar demanda.
   - **Telas**: login/registro → claim (CNPJ + código no e-mail da empresa) → modal
     "grátis até {freeUntil}" (status PROMO) → painel (lojas + comparação k-anônima)
     → promoções (criar manual + upload CSV/XLSX + lista com selo verificado).
     Roteia por estado: 403 em `/merchant/subscription` = ainda não é MERCHANT → claim.
   - **API**: aponta pra `api-dev.economizaai.app` (default `VITE_API_BASE`); trocar
     pra prod no build quando os endpoints merchant forem released.
   - **Por que NÃO dentro do `economiza-ai-front`**: bundle do app de consumidor é
     pesado pro caso, tem dev humano ativo lá, e o deploy é acoplado ao `master` do
     app. Se o portal vingar, migrar depois é barato (telas finas, lógica no backend).
   - ⚠️ **PENDÊNCIA (1 passo manual)**: adicionar `https://merchant.economizaai.app`
     ao env `CORS_ORIGINS` do serviço **economizaai-api-dev** no Render (+ redeploy).
     Sem isso o preflight responde 403 e o portal não consegue logar.
