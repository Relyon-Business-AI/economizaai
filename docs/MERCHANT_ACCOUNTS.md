# Merchant Accounts — contas de mercado (design & estado)

**Status (2026-10-06):** MVP da Fase 1 (perfil + mini-painel) implementado no backend,
**invisível para usuários comuns** — nenhum FE, endpoints gated por role. Este doc é a
fonte única: visão, decisões, o que existe e como continuar.

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
| **1. Perfil do Mercado** | Conta MERCHANT vinculada à rede (cnpj_root); mini-painel: minhas lojas + meus preços vs. a região (agregados k-anônimos) | Grátis (isca/lead-gen) | **MVP shipado (este doc, §4)** |
| 2. Conta de marketing | Publicar promos no feed da comunidade, selo "Patrocinado" + "verificado", flat mensal por loja (R$99–299, validar) | Mensalidade flat | Não construído |
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

1. **Fase 1.5 — claim self-serve**: fluxo "sou este mercado" (verificação por e-mail de
   domínio / código no CNPJ via BrasilAPI), fila de aprovação no admin.
2. **Fase 2 — promos do lojista**: tabela `merchant_promos` (EAN + preço + validade +
   loja(s)), CRUD no painel, exposição no feed `/price-index/promos` com flag
   `sponsored=true` + selo verificado quando observações confirmarem o preço.
   Gating de pagamento via o padrão de assinatura existente (Mercado Pago, INERTE).
3. **Fase 3 — relatório de efetividade**: conversões atribuídas (k-anônimas) por promo.
4. Painel: evolução temporal (minha mediana vs. região por semana), share de cupons na
   cidade, produtos onde estou mais caro (oportunidade de promo).
