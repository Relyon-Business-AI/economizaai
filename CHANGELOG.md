# economizai — what's new

FE-facing diary of meaningful backend changes — new endpoints, response-shape
changes, behavior changes, gotchas worth knowing about. **Newest at the top.**
Skim from the top until you hit a date you've already read.

For the complete API contract see [API.md](./API.md) (walk-through) or
`/swagger-ui` on whichever environment you're hitting.

**Environments:**
- **Production:** `https://economizai-app-prod.onrender.com/api/v1`
  (Swagger: `/swagger-ui/index.html`, health: `/actuator/health`)
- **Dev:** `https://economiz-ai.onrender.com/api/v1` — features land here
  first. Also reachable at the old `https://economizai.economizai.workers.dev/api/v1`
  (proxies to Render), so the store build's URL keeps working.

---

## 2026-09-21 — nº de notas por usuário no admin + busca por mercado nas notas

- **`AdminUserSummaryResponse`** ganhou **`receiptCount`** (nº de notas do domicílio do
  usuário) — enriquecido em lote (1 query por página, sem N+1). `GET /admin/users`
  aceita `sort` (`createdAt` / `name` / `subscriptionTier`) via Pageable.
- **`GET /receipts`** (usuário): a lista de Notas do FE agora usa o `q` (busca por
  mercado / texto) que o backend já suportava. Sem mudança de contrato.

## 2026-09-21 — Minas Gerais (MG) NFC-e agora processa

Notas de **MG** passam a ser lidas de ponta a ponta. O portal do MG é JSF + Cloudflare
Turnstile: ganhamos um **adapter dedicado** (`MgNfcePortalAdapter`) que resolve o
Turnstile (captcha solver, ~R$0,03) e replica o POST do formulário JSF pra pegar o
DANFE — MG entra como estado **verified**. Se esse caminho falhar, cai no **Infosimples**
como rede de segurança (endpoint `.../sefaz/mg/nfce-resumida`, schema
`produtos_servicos`/`valores` — o path genérico retornava erro). Efeito FE: notas do MG
que antes ficavam vazias/"em processamento" agora **confirmam com itens e total**. Sem
mudança de contrato.

## 2026-09-21 — dashboard de operações (saúde da ingestão + estados + custos)

Novo endpoint admin **`GET /admin/ingestion-health?days=30`** → `IngestionHealthResponse`:
mix de status das notas, **taxa de sucesso** do parse, contagem de notas **presas**
(timeout do sweeper), **por estado** (uf → total/parsed/failed/taxa) e **top erros**
(agrupados pela chave de `parseErrorReason`). Tudo derivado da tabela `receipts`.

Isso alimenta a nova tela admin "Operações" (FE), que junta três fontes já existentes:
- `/admin/ingestion-health` — saúde do pipeline
- `/admin/state-coverage` — cobertura/eficácia por estado e por camada (QR_PORTAL / INFOSIMPLES / VERIFIED_ADAPTER)
- `/admin/costs` — gasto em APIs pagas por serviço e **por estado** (Infosimples/captcha)

## 2026-09-21 — excluir contas de review/robô das métricas (sem deletar)

Contas de review de loja (Google Firebase Test Lab `@cloudtestlabaccounts.com` +
personas de revisor) estavam contando no funil/assinaturas. Agora:
- Todas as métricas admin (`/analytics/*`) já **ignoram por padrão** o domínio
  `@cloudtestlabaccounts.com` (robôs do Google — surgem a cada build no Play) além de
  ADMIN + `@economizaai.app`.
- Flag por conta **`excludedFromMetrics`** (novo, em `AdminUserSummaryResponse` +
  `AdminUserDetailResponse`) — conta marcada some de **todas** as métricas, sem ser deletada.
- Novo endpoint admin **`PATCH /admin/users/{id}/metrics-exclusion`** `{ "excluded": true }`
  → `AdminUserDetailResponse`. Backfill das contas de review conhecidas em V73.

## 2026-09-21 — receita/ROAS, funil próprio de visitas e retenção por canal

Dashboard de **Aquisição** (`GET /admin/analytics/acquisition`) ganhou 3 blocos novos
na resposta (aditivo — campos antigos intactos):
- **`revenue`** — receita **real** (de eventos do RevenueCat, começa ~0 e cresce) +
  modelagem pelo preço PRO configurável (`PRO_MONTHLY_PRICE` def. 9,90 / `PRO_ASSUMED_LIFETIME_MONTHS` def. 12):
  `realizedRevenue`, `mrrProxy`, `ltvPerProUser`, `projectedLtv`, `roas`, `projectedRoas`,
  `ltvToCac`, `byChannel[]`, `note`.
- **`visits`** — funil PRÓPRIO topo-de-funil: `totalVisits`, `uniqueVisitors`, `signups`,
  `visitToSignupRate`, `byCampaign[]` (conversão visita→cadastro por campanha, independente do Meta).
- **`retention`** — por canal: `activationRate` (1ª nota), `retentionD7`/`retentionD30`.

Novo endpoint **público** (beacon anônimo, sem auth): **`POST /api/v1/visits`**
`{ anonId, platform, attribution:{ utmSource,utmMedium,utmCampaign,utmContent,utmTerm,clickId,referrer,landingPath } }`
→ **204**. O FE web dispara 1×/sessão na 1ª carga (localStorage `ea_anon_id`). Rate-limit 60/min por IP.
Sem PII (ip só como hash). RevenueCat webhook agora grava `RevenueEvent` (valor real) nos eventos de compra/renovação.

## 2026-09-21 — endpoints admin p/ o Centro de Categorização (ADMIN-only)

Base do módulo admin de categorização (FE em seguida). Todos **`ROLE_ADMIN`**:
- **`PATCH /admin/products/{id}/category`** `{ "category": "PET_SUPPLIES" }` — define a
  categoria **global** do produto e trava como decisão manual (`source=USER`; recategorize/LLM
  não sobrescrevem). Retorna `ProductResponse`.
- **`GET /admin/products?q=&category=&source=`** — lista do catálogo agora com filtros opcionais
  (nome/EAN, categoria [ex.: `OTHER` = fila de revisão], fonte). Paginado.
- **`GET /categorizer/dictionary/curated?q=&page=&size=`** + **`DELETE .../curated/{id}`** —
  listar/paginar/buscar e excluir entradas do dicionário curado (antes só bulk import / wipe-all).
- **`GET /categorizer/dictionary/learned?q=&page=&size=`** + **`DELETE .../learned/{id}`** —
  idem para o dicionário aprendido. Deletes fazem hot-reload.

---

## 2026-09-20 — mensagem "atualize o app" para estados bloqueados no app antigo

Quando um **app antigo** (mobile, sem `X-Device-Fetch`) escaneia uma nota de estado
bloqueado (ex.: PE), o `FAILED_PARSE` agora vem com a mensagem **"Notas deste estado só
funcionam na versão mais recente do app. Atualize o app e escaneie novamente."**
(`parseErrorMessage`), em vez de um erro genérico. Web/foto e o app novo (que já busca
on-device) seguem com a mensagem genérica — só o app mobile desatualizado recebe o aviso
de atualização.

---

## 2026-09-20 — hotfix: `NEEDS_DEVICE_FETCH` só para apps capazes (app antigo não quebra)

**Regressão corrigida.** O status novo `NEEDS_DEVICE_FETCH` fazia o app **da loja
(1.1.1)** quebrar a lista de notas inteira (ele não conhece o status → crash ao
renderizar o badge). Agora o backend só emite `NEEDS_DEVICE_FETCH` para clientes que
**provaram** saber resolvê-lo — os que mandam o header **`X-Device-Fetch`** no submit
(app novo). Apps antigos (sem o header) recebem `FAILED_PARSE` numa falha de estado
bloqueado, que eles renderizam normal. Notas de PE etc. seguem funcionando no app novo.
Além disso, um sweeper move para `FAILED_PARSE` qualquer nota presa em
`NEEDS_DEVICE_FETCH` há mais de ~15 min (destrava as que já ficaram penduradas).
**FE (defesa em profundidade):** a lista agora faz fallback de status desconhecido em vez
de quebrar — só chega em quem atualizar o app.

---

## 2026-09-20 — "Não é meu" + editar nota confirmada (camada pessoal por household)

**Agora dá pra ajustar os seus registros DEPOIS de confirmar, sem mexer na nota.**
Novo endpoint **`PATCH /receipts/{id}/items/{itemId}/personal`** (funciona em
QUALQUER status, inclusive `CONFIRMED`). Corpo — todos os campos opcionais:
```json
{ "excludedFromPersonal": true, "friendlyDescription": "Cerveja Stella",
  "paidUnitPrice": null, "paidTotalPrice": 50.00 }
```
- **`excludedFromPersonal`** = "não é meu" (compra compartilhada / item de outra
  pessoa): tira a linha dos SEUS gastos / consumo / savings / relatórios, **mas o
  preço continua alimentando o índice colaborativo** (foi um preço real). É
  DIFERENTE do `excluded`, que remove a linha de tudo (linha-lixo).
- **`friendlyDescription`** e **`paidTotalPrice`/`paidUnitPrice`** agora editáveis
  **após o confirm** (antes só em `PENDING_CONFIRMATION`). Preço pago só ajusta o
  seu total pessoal — o preço de lista (baseline do índice) fica intocado.

**Novo campo no item:** `ReceiptItemResponse.excludedFromPersonal` (boolean). O total
`householdTotalAmount` já desconta itens "não é meu". Os campos imutáveis da nota
(quantidade, preço de lista, EAN) seguem editáveis só em `PENDING` via
`PATCH /receipts/{id}/items/{itemId}`.

**Escopo:** "não é meu" filtra gastos/consumo/savings/relatórios/notificações de
promo. NÃO mexe em: índice colaborativo, canonicalização, matching, nem histórico de
preço (um preço real segue sendo referência, independente de quem pagou).

---

## 2026-09-20 — Fallback genérico de estado bloqueado (`NEEDS_DEVICE_FETCH`) + auto-confirmação

**Cadeia auto-resolvível pra estados que bloqueiam nosso IP.** Antes o fetch no
aparelho era hardcoded pra PE. Agora é genérico e self-healing:
- Estado na lista de exceção (device-first, hoje só PE): o app busca no aparelho e
  usa `POST /receipts/prefetched` (rápido, sem round-trip condenado).
- **Qualquer outro estado:** server-first. Se o servidor não conseguir (bloqueio de
  IP), a nota fica no **novo status `NEEDS_DEVICE_FETCH`** (trate na UI como
  PROCESSING). O app então busca no aparelho e reposta em
  **`POST /receipts/{id}/device-content`** `{ "rawContent": "<HTML/XML da SEFAZ>" }`.
  Se o aparelho também não conseguir → `FAILED_PARSE` (sem loop). **Estado bloqueado
  novo passa a funcionar sozinho, sem update de app.** (Novo campo `qrPayload` no
  `ReceiptResponse` — a URL do QR, pro app rebuscar.)
- **FE (nativo):** o `ReceiptsScreen` já trata `NEEDS_DEVICE_FETCH` no polling
  (`resolveDeviceFetch`); web fica no fluxo antigo (CORS).

**Auto-confirmação de notas paradas.** Notas em `PENDING_CONFIRMATION` por mais de
**6h** sem confirmação manual são **confirmadas automaticamente** (a maioria dos
usuários não sabia que precisava confirmar → notas paradas rendiam zero). O usuário
ainda pode editar/deletar depois; a contribuição pro índice segue respeitando o
`contributionOptIn`. Configurável via `ECONOMIZAI_RECEIPTS_AUTO_CONFIRM_*`.

## 2026-09-19 — Pernambuco (PE) via fetch no aparelho + novo endpoint `POST /receipts/prefetched`

**Contexto:** o portal de PE serve a nota pra IP residencial/móvel (celular), mas
**bloqueia o IP do nosso servidor** (datacenter) — responde `<erro>100</erro>` sem
os dados. O parser já foi corrigido (segue o redirect `http`→`https:444` e lê o XML
da NFe), mas sozinho não vence o bloqueio de IP.

**Solução (precisa do app):** pra esses estados, o **app busca a página da nota no
próprio aparelho** (IP do celular é aceito) e manda o conteúdo cru pro backend, que
parseia com o mesmo pipeline — sem raspar do nosso IP, sem custo.

**Novo endpoint:** `POST /api/v1/receipts/prefetched` — corpo
`{ "qrPayload": "<o que o QR retornou>", "rawContent": "<HTML/XML que o app baixou da URL do QR>" }`.
Mesmas validações e mesma resposta (`ReceiptResponse`, status `PROCESSING`) do
`POST /receipts`; a ingestão usa o `rawContent` em vez de buscar na SEFAZ. O
`rawContent` **precisa conter a chave** da nota (guarda de integridade).

**FE (nativo):** o `receiptService.submitReceipt` já faz isso sozinho — pra UF
bloqueada (hoje só PE, código `26`) e QR que seja URL `http(s)`, ele busca no
aparelho e usa `/receipts/prefetched`; se falhar/não se aplicar, cai no `/receipts`
normal. **Web fica no fluxo antigo** (CORS bloqueia o fetch on-device no navegador).

## 2026-09-14 — Atribuição de marketing no cadastro + dashboard de aquisição (admin)

**Cadastro aceita `attribution` (opcional).** `POST /auth/register`, `/auth/google`
e `/auth/apple` agora aceitam um objeto `attribution` opcional no corpo, todos os
campos strings opcionais:

```
"attribution": {
  "utmSource": "instagram", "utmMedium": "cpc", "utmCampaign": "black-friday",
  "utmContent": "video-a", "utmTerm": "mercado barato",
  "clickId": "<fbclid>", "referrer": "https://...", "landingPath": "/promo"
}
```

**Pedido pro FE:** encaminhe o que der da URL de landing — `utm_source`/`utm_medium`/
`utm_campaign`/`utm_content`/`utm_term` (query params), `fbclid` → `clickId`,
`document.referrer` → `referrer`, e o path da primeira página → `landingPath`.
Guarde na primeira visita (antes do cadastro) e mande junto no registro. Tudo
opcional — não mandar nada não quebra nada, e **não muda o shape do `AuthResponse`**.
O backend deriva o canal de aquisição (Instagram pago, Google pago, indicação,
orgânico, direto…) e grava imutável no usuário.

**Dois endpoints novos de analytics (ADMIN):**
- `GET /admin/analytics/acquisition?days=30` — funil (cadastros→verificados→ativados
  →PRO com taxas), timeline diária empilhada por canal, breakdown por canal e por
  campanha, e rollup de gasto de anúncios (Meta) com custo por cadastro.
- `GET /admin/analytics/subscriptions` — totais por tier (FREE/PRO), pagantes ativos
  e promos concedidas.

Ambos exigem JWT ADMIN. Meta Ads spend fica **inerte** até as env vars serem
setadas — enquanto isso `adSpend.configured` vem `false` e o custo por cadastro
vem `null`. Nada disso é consumido pelo app; é pro dashboard de marketing.

Também: `POST /admin/analytics/ad-spend/sync` → `{ "rowsSynced": n }` roda o sync
do Meta na hora (sem esperar o cron diário). E `subscriptions.payingActive` conta
só assinaturas de gateway real — promo/grant manual (`provider = "manual"`/null)
entra em `promoGranted`, pra não inflar "pagantes" com os PRO de promoção.

Ainda: os dois GETs de analytics aceitam `includeInternal` (default `false`) — que
exclui admins e contas de teste (`@economizaai.app`) pra os números refletirem
usuários reais. E o acquisition ganhou `byPlatform` (`WEB/ANDROID/IOS/UNKNOWN`),
que quebra os cadastros "Desconhecidos" por plataforma de registro.

E `adSpend` agora traz **orçamento/status da campanha** (via sync do Meta):
`budgetRemaining` (agregado) + por campanha `status`, `lifetimeBudget`,
`budgetRemaining`, `endsAt` e `ended` — pra ver quanto ainda tem de verba e se a
campanha já acabou. Null enquanto o Meta não estiver configurado/sincronizado.

---

## 2026-09-07 — Alerta de nova conta no e-mail do admin

Toda conta nova (senha ou social login) dispara um e-mail pro destinatário de
contato (`CONTACT_RECIPIENT`) com nome, e-mail, método, plataforma, idioma,
IP e país (via Cloudflare). Não muda nada no contrato da API nem no fluxo de
cadastro — o envio é assíncrono e nunca bloqueia/falha o registro. Desligar:
`SIGNUP_ALERT_ENABLED=false` (env var, sem deploy).

---

## 2026-09-07 — Web dashboard: login destravado (CORS) + pedido pro FE

`https://dashboard.economizaai.app` agora está no `CORS_ORIGINS` dos dois
ambientes — antes o backend rejeitava toda requisição do dashboard com
403 "Invalid CORS request" **antes** de autenticar, então o login web nunca
funcionou (e o worker às vezes reportava 503). Nada mudou no contrato da API.

**Pedido pro FE:** a tela de login engole falhas silenciosamente — nem o 403
de CORS nem um 401 de senha errada mostram qualquer feedback. Exibam o
`parseErrorMessage`/mensagem localizada do corpo da resposta (ou um toast
genérico em erro de rede).

O backend também passou a logar rejeições de CORS
(`cors.rejected origin=... method=... path=...`) — se um novo domínio de FE
subir e "nada acontecer", esse log entrega o motivo na hora.

---

## 2026-09-02 — Promo: todo usuário vira PRO ("até segunda ordem")

Toda conta existente foi promovida para `subscriptionTier: "PRO"` com validade
de **6 meses** a partir de hoje (migration `V68__grant_premium_promo.sql`).
Toda conta **nova** (registro por senha ou social login) recebe PRO
automático por **3 meses** a partir do próprio cadastro, enquanto a promo
estiver ligada (`economizai.subscription.promo.enabled`, ON por padrão — pode
ser desligada via env var `SUBSCRIPTION_PROMO_ENABLED=false` sem deploy de
código; duração ajustável via `SUBSCRIPTION_PROMO_MONTHS`). `GET
/subscriptions/status` reflete isso normalmente (`provider: "manual"`,
`currentPeriodEnd` = data do grant + duração da promo).

**Mudança de shape:** `POST /auth/register`, `/auth/google` e `/auth/apple`
agora retornam dois campos novos, sempre presentes: `signupPromoGranted`
(boolean) e `signupPromoValidUntil` (ISO datetime ou `null`). Vêm `true` +
preenchido **só** na chamada que efetivamente criou a conta e recebeu a
promo — nunca em `/login` nem `/refresh`. Use isso pra disparar o banner
"você ganhou N meses grátis" uma única vez, logo após o cadastro, sem
precisar inferir nada a partir de `subscriptionTier`.

## 2026-09-02 — Corrigido: `friendlyDescription` faltando na busca/detalhe de produto para itens só renomeados via nota

`GET /products` (busca), `GET /products/{id}`, `GET /products/by-ean/{ean}` e
`GET /products/recently-viewed` agora também caem de volta no nome amigável
que o domicílio deu ao produto na nota fiscal confirmada, quando não existe
um alias explícito (`household_product_aliases`). Antes, esses endpoints só
olhavam o alias explícito — um produto renomeado apenas ao adicionar um item
faltante na nota (`POST /receipts/{id}/items`) aparecia certo na lista de
compras/nota, mas em branco (`friendlyDescription: null`) na busca. Ordem de
resolução: alias explícito → nome mais recente de nota confirmada → `null`.
Nenhuma mudança de shape, só de quando o campo vem preenchido.

## 2026-08-28 — `friendlyDescription` em TODOS os endpoints que retornam produto

Todo endpoint que devolve um nome de produto agora traz **os dois campos**: o
nome de catálogo (como veio da nota, geralmente CAIXA ALTA) **e**
`friendlyDescription` (o apelido que o domicílio deu ao produto; `null` se
nunca renomeado). Regra de exibição no app: `friendlyDescription ?? nome de
catálogo`. Endpoints que ganharam o campo:

- `POST /shopping-list/optimize` — `marketPlans[*].items[*]` e `unpriced[*]`
- `GET /deals` — cada oferta (e o push do resumo de ofertas já usa o apelido)
- `GET /insights/products/{id}/price-history` — no nível do produto
- `GET /consumption/predictions` e `/consumption/suggested-list` — cada previsão
- `GET/POST /alerts` — `PriceAlertResponse`
- `GET/POST/PATCH /notification-rules` — `NotificationRuleResponse`
- `GET /products/{id}` (antes vinha `null`), `GET /products/by-ean/{ean}` e
  `GET /products/recently-viewed`

Já tinham (nada mudou): itens da nota (`ReceiptItemResponse`), `GET /items`
(`PurchasedItemResponse`), listas de compras (`ShoppingListResponse.items[*]`),
busca `GET /products?query=` e `GET /products/mine` (`friendlyName`).

**Importante:** o backend NÃO inventa nome bonito — `friendlyDescription` só
existe depois que alguém do domicílio renomeia o produto (na revisão da nota ou
onde houver edição de nome). Sem rename, exibam o nome de catálogo mesmo.

## 2026-08-27 — Nome amigável nas listas de compras e na busca de produtos

`ShoppingListResponse.items[*]` ganhou o campo **`friendlyDescription`**
(nullable — o apelido que o domicílio deu ao produto), e `displayName` agora
prefere: `friendlyDescription` → `productName` → `freeText`. `ProductResponse`
também carrega `friendlyDescription` nos resultados de `GET /products?query=`
(tela "Adicionar item"); no `GET /products/{id}` vem `null`.

## 2026-08-27 — Corrigido: exclusão de conta (LGPD) falhava com 500 em prod

`DELETE /users/me` retornava 500 em produção quando o domicílio tinha correções
de categoria (ou qualquer dado com vínculo de origem de merge). Corrigido no
nível do banco (FKs de origem agora `ON DELETE SET NULL`); nenhum contrato
mudou. Se o app tratava esse 500 com workaround, pode remover.

## 2026-08-26 — Busca considera o nome amigável do produto

O apelido que o usuário dá ao produto (`friendlyName`, salvo em
`household_product_aliases` quando ele renomeia um item) agora conta na busca:

- `GET /receipts?q=` — além de descrição bruta, `friendlyDescription` do item,
  nome do produto e nome do mercado, o `q` agora também casa com o **apelido do
  produto** do household.
- `GET /products?query=` — a busca global também retorna produtos que o
  household renomeou para algo que casa com a query (mesmo que o nome de
  catálogo não case).
- `GET /products/mine?query=` — filtra também pelo apelido, e o response ganhou
  o campo novo **`friendlyName`** (string, nullable — `null` quando o household
  nunca renomeou o produto). Use-o como nome de exibição preferido.

Escopo: o apelido é **por household** — renomes de outros households nunca
afetam a busca de vocês.

Também no seed de QA: `POST /admin/dev/seed-discounted-receipt` agora planta
**3 linhas promocionais** (antes 1) e aceita `?targetEmail=` para plantar a
nota na conta de outro usuário (admin-only, dev-only).

## 2026-08-16 — Desconto por item na revisão (preço pago manual)

A NFC-e só traz **um desconto no total da nota** (`discountTotal` na receipt), nunca
por item. Para quem quer registrar o desconto de um item específico, o endpoint de
edição de item na revisão agora aceita o **preço realmente pago**:

- `PATCH /receipts/{id}/items/{itemId}` aceita dois campos **opcionais**:
  - `paidTotalPrice` — total efetivamente pago nessa linha (quando em promoção). Não
    pode ser **maior** que `totalPrice` (senão `400` com `receipt.item.paid.price.exceeds.original`).
  - `paidUnitPrice` — preço unitário pago. Se omitido e houver `paidTotalPrice`, é
    derivado de `paidTotalPrice ÷ quantity`.
  - Enviar `paidTotalPrice: null` **limpa** um desconto manual anterior.
- O `totalPrice`/`unitPrice` original **continua como impresso** (preço de prateleira —
  ainda é a base do índice colaborativo). Só é editável enquanto a nota está `PENDING_CONFIRMATION`.
- **Response shape:** todo item (`ReceiptItemResponse` e `PurchasedItemResponse` do
  `GET /items`) agora traz `paidUnitPrice`, `paidTotalPrice` e o booleano derivado
  `promotional` (`true` quando `paidTotalPrice` está presente e abaixo de `totalPrice`).
  Use `promotional` para o selo de desconto por item; mostre `paidTotalPrice` riscando
  o `totalPrice` original.

## 2026-07-22 — Plataforma do cliente no login (web / android / ios)

Novo campo **opcional** `platform` (`WEB` | `ANDROID` | `IOS`) nos endpoints de auth
(`/register`, `/login`, `/google`, `/apple`). Mandem em toda chamada de login/registro
a plataforma de onde o usuário está entrando.
- Guardamos a **plataforma de registro** (a original, só na criação da conta), a
  **última plataforma** usada e a **última data de login por plataforma**.
- É best-effort: valor ausente ou desconhecido é ignorado (nunca vira 400).
- `UserResponse` (ex.: `GET /users/me`) agora traz `registrationPlatform`,
  `lastPlatform`, `lastWebLoginAt`, `lastAndroidLoginAt`, `lastIosLoginAt`.

## 2026-07-22 — Tudo localizado (pt/en): validação, e-mails e notificações

Fechamos as lacunas de i18n. Agora respeitam `Accept-Language` / o idioma do usuário:
- **Erros de validação por campo** (`@NotBlank`/`@Size`/`@Email`…) — antes vinham em
  inglês fixo no mapa `errors`; agora localizados (o `message` de topo já era).
- **E-mails de auth** (redefinição de senha, confirmação de e-mail) — antes só pt.
- **Notificações push** (queda de preço, reposição, orçamento, resumo semanal, ofertas)
  — antes só pt.

Novo: cada usuário tem um **idioma** capturado do `Accept-Language` no cadastro
(default `pt`) — decide a língua de e-mails e push (que saem fora do request). Mande
`Accept-Language: en` no register / login social pra criar conta em inglês.

---

## 2026-07-22 — Login por senha em conta social → 409 com o provedor

`POST /auth/login` com e-mail/senha numa conta que foi criada via Google/Apple
(sem senha) agora retorna **409 `auth.social_account`** em vez de um 401 genérico.
A resposta traz o provedor pra você destacar o botão certo:
`{ "status": 409, "message": "...", "errors": { "provider": "GOOGLE" } }`. Leia
`errors.provider` (`GOOGLE`/`APPLE`); `message` já vem localizada
("Esta conta usa login com Google. Toque em 'Continuar com Google'."). Senha
errada de verdade continua 401 `auth.invalid.credentials`.

---

## 2026-07-21 — Login social (Google + Apple) no ar; `emailVerified`/`emailVerifiedAt` na resposta

Google e Apple login estão **configurados e no ar** (audience/client-ids do backend
prontos: Google web+android client IDs, Apple bundle `com.economizaaiapp.economizaai`).
Contrato: `POST /auth/google {idToken}` e `POST /auth/apple {identityToken, name?}` →
`{ token, refreshToken, user }`, igual ao login por e-mail. Apple só manda o `name` no
**primeiro** login — o backend persiste; nos próximos manda só `{ identityToken }`.

O `user` (UserResponse) agora **de fato inclui** `emailVerified` (boolean) e
`emailVerifiedAt` (ISO ou null) — antes estavam documentados mas não retornavam. Dá pra
gate de features por `emailVerified === true`. Usuários social nascem com
`emailVerified=true`. Política de conta: casa por `sub` do provedor; se o e-mail bate com
conta LOCAL já verificada, **vincula**; se não verificada, rejeita (anti-takeover).

---

## 2026-07-22 — Relatórios de compras: XLSX com gráficos, PDF bonito e envio por e-mail

`GET /receipts/export` cresceu para um relatório completo:
- `format=xlsx`: planilha multi-abas — dashboard **Resumo** (KPIs: total gasto,
  notas, itens, ticket médio + 3 gráficos nativos do Excel: evolução mensal,
  pizza por categoria, barras dos mercados), aba **Compras** estilizada
  (cabeçalho verde, filtro, painel congelado) e abas de dados.
- `format=pdf`: relatório A4 com identidade visual — cards de KPI, gráficos
  renderizados e tabela de itens com cabeçalho repetido.
- `format=csv`: continua o arquivo plano amigável ao Excel BR.
- **`delivery=email`**: envia o arquivo como anexo para o e-mail da própria
  conta (202; 503 se SMTP indisponível). `delivery=download` (padrão) baixa
  direto. Nunca envia para e-mail de terceiros.
- Categorias agora têm rótulos localizados (`category.*`) nos relatórios.

---

## 2026-07-22 — GO virou estado verificado (scan gratuito)

Goiás saiu da cadeia experimental: adapter dedicado no portal
`nfeweb.sefaz.go.gov.br` (sem captcha — shell → cookie de sessão → DANFE
embutido em JS → parser padrão). Antes toda nota de GO era resgatada pela
Infosimples (paga); agora a camada gratuita resolve. Digitação manual de
chave também funciona para GO (a consulta não exige assinatura do QR).
Validado com as notas reais do primeiro usuário orgânico de GO.

---

## 2026-07-22 — Exportar histórico de compras (CSV e XLSX) — sugestão de usuário

Novo `GET /api/v1/receipts/export?from=&to=` (autenticado): baixa um CSV do
histórico CONFIRMADO do domicílio — uma linha por item (não-excluído), com
data, mercado, CNPJ, **chave de acesso**, item, quantidade, preço e categoria.
Formatos: `?format=csv` (padrão — separador `;`, decimais com vírgula,
UTF-8 com BOM, amigável ao Excel brasileiro) ou `?format=xlsx` (planilha
com células tipadas de data/número — abre certo em qualquer locale).
Cabeçalhos localizados. `Content-Disposition: attachment`. Entrega é
**download direto** (o app salva/compartilha o arquivo); envio por e-mail
pode vir depois como opção.
PRO no plano de monetização, mas liberado enquanto o enforcement de assinatura
estiver desligado (hoje). Janela de histórico FREE se aplica quando ligado.

Sobre a segunda sugestão do mesmo usuário (copiar o número da NFC-e na tela da
nota): o campo `chaveAcesso` já vem em todas as respostas de receipt — é só o
FE adicionar o botão de copiar. O CSV também inclui a chave.

---

## 2026-07-22 — Camadas de IA: enriquecimento de produtos + extração por foto

**Extração por foto (novo endpoint FE):** `POST /api/v1/receipts/items-photo`
(multipart `file`) — o usuário fotografa a nota impressa e a IA extrai os
itens. Cria uma nota `PENDING_CONFIRMATION` com `origin=PHOTO` que segue o
fluxo normal de revisão/confirmação. Casos de uso: nota em contingência (a
SEFAZ não tem o documento), QR ilegível, digitação manual inviável. Regras:
- Notas por foto NÃO alimentam o índice colaborativo (sem autenticidade
  SEFAZ) — só o histórico pessoal do domicílio.
- Contam no limite mensal FREE; cap diário de fotos por usuário (padrão 5).
- Erros novos: 400 `receipt.photo.not-receipt` (imagem não é nota), 400
  `receipt.photo.no-items`, 503 `receipt.photo.extraction.unavailable`.

**Enriquecimento por IA (transparente pro FE):** produtos que as camadas
gratuitas não resolveram (categoria/marca/embalagem) são enriquecidos em
lote por LLM; categorias/marcas passam a aparecer preenchidas com
`categorizationSource=LLM`. Correções humanas continuam soberanas.

Admin: `GET /api/v1/admin/llm/report` (KPIs, custo, fila de divergências) e
`POST /api/v1/admin/llm/disagreements/{id}/resolve?accept=`. Tudo com
liga/desliga por env (`LLM_ENRICHMENT_ENABLED`, `LLM_AUDITOR_ENABLED`,
`PHOTO_EXTRACTION_ENABLED`) e pronto para virar recurso premium.

---

## 2026-07-22 — Swagger ganhou o botão Authorize (JWT)

O Swagger UI agora tem o cadeadinho: clique em **Authorize**, cole o token do
`POST /auth/login` (sem o prefixo `Bearer`) e todas as chamadas protegidas
passam a enviar o header. Nada mudou na API em si.

---

## 2026-07-16 — Restaurantes/bares rejeitados; padarias, açougues e conveniências aceitos

Nova regra de estabelecimento no scan (varejo recorrente de alimentos vs.
refeição avulsa):

- **Suportados** (fluxo normal): mercados, farmácias e agora todo varejo de
  alimentos — padarias, açougues, bebidas, hortifrútis e **lojas de
  conveniência** (posto de gasolina com conveniência conta).
- **Rejeitados**: restaurantes, bares e lanchonetes (CNAE 56xx). CNPJ já
  conhecido → `POST /receipts` responde **400** com
  `receipt.merchant.unsupported` (mensagem localizada). CNPJ novo → a nota entra
  em PROCESSING e falha com `parseErrorReason=receipt.merchant.unsupported:` —
  sem itens e sem rawHtml (nada da nota é armazenado).
- **Zona cinzenta** (outros CNAEs, ex. padaria informal): a nota é processada
  normalmente para o usuário, mas os preços ficam FORA do índice colaborativo
  até revisão do admin (que é notificado por e-mail).

Novos motivos de falha que o FE pode receber ao pollar `GET /receipts/{id}`:
- `receipt.merchant.unsupported` — estabelecimento não suportado.
- `receipt.contingency.pending` — nota emitida em contingência ainda não
  disponível na SEFAZ (tentar de novo mais tarde). Antes aparecia como o
  enganoso `no-items-found`.
- `receipt.sefaz.rejected_qr:<código>` — SEFAZ recusou o QR (ex. 227).

Admin: `GET /api/v1/admin/merchants/grey` (fila de revisão, ordenada por volume
de scans) e `PUT /api/v1/admin/merchants/{cnpj}/support` com
`{"override":"SUPPORTED"|"BLOCKED"|null}` — SUPPORTED retroalimenta o índice com
as notas já confirmadas do estabelecimento.

---

## 2026-07-16 — Todas as 27 UFs aceitas no scan (cadeia experimental)

`POST /receipts` agora aceita nota de **qualquer estado**. Estados verificados
(RS, PR, SP, SC, MS, e CE com Infosimples) seguem iguais; os demais passam por
uma cadeia de fallback experimental (portal do próprio QR → Infosimples). Se
tudo falhar, a nota vira `FAILED_PARSE` com
`parseErrorReason=receipt.state.experimental_failed:<UF>` e `parseErrorMessage`
localizada ("ainda não conseguimos processar notas desse estado…") — renderize
essa mensagem; o time é notificado automaticamente com as evidências.
`receipt.state.unsupported` (400 no submit) agora só ocorre se a cadeia estiver
desligada no servidor. A digitação manual de chave passa a ser rejeitada no
submit (`receipt.manual-chave.unsupported`) para QUALQUER estado que dependa da
assinatura do QR sem fallback pago ativo — antes só RS.

Admin: novo `GET /api/v1/admin/state-coverage` (mapa por UF: modo + telemetria
por camada, alimentado pelos scans reais).

---

## 2026-07-16 — Cap diário de SMS/WhatsApp (Twilio)

Envios via Twilio (notificações SMS/WhatsApp e o OTP de verificação de telefone)
agora contam no controle de custos: cap por usuário/dia (default 10). Ao exceder,
`PATCH /users/me/phone` retorna **429** com mensagem localizada ("limite diário de
envios de SMS"); notificações SMS/WhatsApp apenas registram falha de entrega
(`twilio_quota_exceeded`) sem erro pro usuário. Sem Twilio configurado (dev), nada muda.

---

## 2026-07-16 — `phone` opcional no contato

`POST /api/v1/contact` agora aceita um campo opcional `phone` (string livre,
máx. 30 caracteres), igual ao beta-signup. Quando enviado, o telefone é
incluído no e-mail de contato. Requests antigos sem `phone` continuam
funcionando igual.

---

## 2026-07-14 — `DELETE /api/v1/admin/users/{id}` (admin)

Novo endpoint admin para excluir contas (limpeza de contas de teste). Reusa o
cascade do `DELETE /users/me` (apaga a conta + household se ficar vazio).
Retorna 204; recusa contas ADMIN com 400. Útil para a telinha interna de
usuários.

---

## 2026-07-13 — `phone` opcional no beta-signup

`POST /api/v1/beta-signup` agora aceita um campo opcional `phone` (string livre,
máx. 30 caracteres) além de `name` e `email`. Quando enviado, o telefone é
incluído no e-mail de lead. Requests antigos sem `phone` continuam funcionando
igual.

---

## 2026-07-12 — Captação de beta testers (`POST /api/v1/beta-signup`)

Endpoint **público** (sem auth) pra captar interessados em ser beta tester —
separado do `/contact`. Body `{ name, email }` (ambos obrigatórios; `email`
válido; sem `message`). Retorna **202 Accepted**. Manda um email pra nossa caixa
com assunto próprio (`[economizai beta tester] <nome>`), então é filtrável do
contato normal. Rate-limited a **5/hora por IP** (bucket próprio, separado do
`/contact`). Nada muda no `/contact` — o assunto de cada um é fixo no backend
(não há campo `subject` no body de nenhum dos dois).

## 2026-07-12 — Backend migrado pra Render (URL nova + a antiga segue viva)

O backend de DEV saiu da máquina self-hosted e agora roda no **Render**
(estável, sem o túnel que caía). **Novo API base:** `https://economiz-ai.onrender.com/api/v1`.
A **URL antiga** (`economizai.economizai.workers.dev`) **continua funcionando** —
agora ela encaminha pro Render — então o app já enviado às lojas com a URL antiga
não quebra. Builds novos da FE devem apontar pro `onrender.com`. Dados (contas,
notas, catálogo) foram migrados; **fotos de perfil ainda não** (usuários com foto
veem o avatar de iniciais até re-subir).

---

## 2026-07-11 — Formulário de contato/feedback (`POST /api/v1/contact`)

Endpoint **público** (sem auth) pra dúvida/crítica/sugestão/elogio. Body
`{ name, email, message }` (todos obrigatórios; `email` válido; `message` ≤ 5000).
Envia a mensagem por e-mail pro suporte com o e-mail do usuário como Reply-To.
**Rate-limit: 5/hora por IP** (429 com `Retry-After` acima disso). Retorna `202`
sem corpo. Sem SMTP configurado, a mensagem é logada (não se perde).

---

## 2026-07-10 — Sinal de "disponível em breve" para telas gated (`GET /home/availability`)

Novo endpoint pro FE mostrar **blur/cadeado** em seções que dependem de volume, em vez
de uma tela vazia confusa no usuário novo. Retorna `features[]`, cada uma com
`{ feature, available, reason, have, need }`:
- `reason` distingue **`NEEDS_MORE_RECEIPTS`** (pessoal — escaneie mais notas) de
  **`NEEDS_COMMUNITY`** (colaborativo — aguardando mais domicílios).
- `have`/`need` = progresso pro FE renderizar "faltam X" (ex.: "2/3 domicílios").
- `available = have >= need` (passou do cold-start; item específico ainda pode estar ralo).

Cobre `CONSUMPTION_PREDICTIONS, SUGGESTED_LIST, PERSONAL_PROMOS, PREFERENCES,
COMMUNITY_DEALS, COMMUNITY_PROMOS, BEST_MARKETS, REFERENCE_PRICE`. Os endpoints
existentes **não mudaram** — este é aditivo.

---

## 2026-07-09 — Unidades normalizadas (UND9→UN, KG9→KG) + códigos internos nunca viram EAN

Verificado com uma nota real do **Sam's Club/WMS (SP)**, 28 itens:

- **`unit` agora vem normalizada** em todas as vias de ingestão (scrapers e
  Infosimples). ERPs imprimem a unidade com sufixos/grafias próprias
  (`UND9`, `KG9`, `UND8`, `UND`, `UNID`) — agora chegam como `UN`/`KG` etc.
  Se o FE tinha tratamento para essas variações, pode remover.
- **Códigos internos alfanuméricos** no slot "(Código: …)" (ex.: `AR062620`
  do WMS) nunca são gravados como `ean` — mercados como o WMS não imprimem
  GTIN na NFC-e, então `ean: null` nesses itens é o dado real, não um bug do parser.

---

## 2026-07-09 — Controle de custo: limite diário de consultas pagas

Para proteger o custo com serviços pagos (captcha, Infosimples), toda NFC-e passou a ter
limites por usuário/dia. Impacto no FE: uma NFC-e pode falhar (`FAILED_PARSE`) com dois
novos `parseErrorReason`:

- `receipt.paid_api.quota_exceeded` — usuário atingiu o limite diário de consultas
  automáticas (renderizar a `parseErrorMessage` e sugerir tentar amanhã).
- `receipt.paid_api.circuit_open` — o serviço de consulta está temporariamente
  indisponível (sugerir tentar em alguns minutos).
- `receipt.paid_api.budget_exhausted` — atingimos o teto global de processamento do
  dia (kill-switch de custo); tratar como "tente novamente amanhã".

Todos já vêm com `parseErrorMessage` localizada — o FE nunca deve renderizar a chave crua.

Admin: novo `GET /api/v1/admin/costs?days=30` (ADMIN) devolve o gasto total com serviços
pagos (captcha + Infosimples), com quebra por serviço e por estado, e o gasto de hoje vs.
o orçamento diário.

---

## 2026-07-09 — Export LGPD completo (`GET /users/me/export`)

O export de dados pessoais agora inclui **tudo** que é atribuível ao usuário — o
`UserDataExportResponse` ganhou novos campos: `notificationPreferences`, `productAliases`,
`brandPreferences`, `consumptionSnoozes`, `recentlyViewedProducts`, `notificationEvents`,
`dealSurfaceStates`, `dataShareConsents`; `shoppingLists` agora traz os **itens**; e
`accountExtras` ganhou telefone, foto (metadados), preferências de digest, aceites legais e
provedor de auth. **Ação do FE:** o payload do export cresceu (campos novos, aditivo — nada
removido).

---

## 2026-07-08 — Nova categoria de produto: **PET_SUPPLIES**

Adicionada a categoria `PET_SUPPLIES` (ração/petiscos e itens de pet). **Ação do FE:**
renderizar o novo valor na lista de categorias (rótulo sugerido: "Pet"). Itens de pet
food passam a cair aí em vez de GROCERIES/MEAT_DAIRY. Valores atuais: GROCERIES,
BEVERAGES, PRODUCE, MEAT_DAIRY, BAKERY, CLEANING, PERSONAL_CARE, HEALTH,
**PET_SUPPLIES**, OTHER.

---

## 2026-07-08 — Foto do QR e OCR da chave: dois novos jeitos de entrar com a nota

Dois endpoints novos para quando o scan ao vivo não é uma opção (versão web,
foto salva na galeria, QR danificado):

1. **`POST /api/v1/receipts/photo`** (multipart, campo `file`, JPG/PNG ≤ 5MB) —
   envia uma **foto do QR code**; o backend decodifica (ZXing) e cai no mesmo
   fluxo do submit normal → `201` com recibo `PROCESSING`, mesmo polling de
   sempre. Erro novo: `400 receipt.photo.qr.unreadable` (foto sem QR legível).
2. **`POST /api/v1/receipts/chave/photo`** (multipart, idem) — **OCR da chave
   de acesso impressa** (44 dígitos). Retorna `200 { chaveAcesso, uf }` para o
   usuário **confirmar** na tela — aí o FE manda pelo `POST /receipts` normal.
   Não auto-submete (OCR pode errar dígito; só devolvemos chave com dígito
   verificador mod-11 + UF válidos). Erros: `400 receipt.photo.chave.unreadable`,
   `503 receipt.ocr.unavailable` (host sem Tesseract).

Ambos contam no mesmo rate limit (30/h) e no cap mensal do plano FREE. A regra
por estado continua valendo no submit: chave "nua" de **RS** é rejeitada
(precisa do QR assinado). Detalhes e UX sugerida no [API.md](./API.md).

---

## 2026-07-08 — Novo estado suportado: **CE** (Ceará), via Infosimples

Notas de **CE** agora processam — por enquanto **via fallback Infosimples** (não temos
scraper próprio do portal do CE ainda). O usuário pode **escanear o QR** ou **digitar a
chave** de 44 dígitos do cupom; nos dois casos a nota é buscada pelo Infosimples.
Verificado com uma nota real (A P FRIOS, 26 itens, R$ 196,17). Requer o Infosimples
habilitado no ambiente; cada consulta tem custo. UFs cobertas: **RS, PR, SP, MS, SC, CE**.

---

## 2026-07-06 — Digitação manual da chave (QR danificado) funciona por estado

Quando o QR está amassado/rasurado, o usuário pode digitar os **44 dígitos da
chave de acesso** (shape 5 do submit — já aceito). O backend agora recupera a
nota corretamente conforme o estado:

- **MS, SC** — recuperam pela chave nativamente (consulta por chave + captcha). ✅
- **PR, SP** — recuperam via fallback pago (Infosimples), pois o portal desses
  estados só entrega a nota com o QR assinado. ✅
- **RS** — **não é possível** por chave (a SEFAZ-RS exige login gov.br). O submit
  agora **rejeita na hora** uma chave RS pura com **400** e mensagem localizada
  (`receipt.manual-chave.unsupported`) orientando a escanear o QR — antes isso
  virava um `FAILED_PARSE` lento após polling.

Nada muda pra QR escaneado (URL completa) em nenhum estado. Campos de erro
continuam trazendo `parseErrorReason` + `parseErrorMessage`.

---

## 2026-07-06 — Novo estado suportado: **SP** (São Paulo)

Notas de consumidor (NFC-e) de **SP** agora são processadas. O portal da SEFAZ-SP
(`www.nfce.fazenda.sp.gov.br`) entrega a mesma DANFE responsiva de RS/PR num GET
simples, sem captcha — verificado com uma nota real (Mercadão Atacadista, 20 itens,
R$ 158,12). UFs cobertas agora: **RS, PR, SP, MS, SC**. Nada muda no contrato do
FE; basta escanear o QR da nota de SP normalmente.

---

## 2026-07-04 — Correção: notas de MS voltaram a funcionar

O portal da SEFAZ-MS renumerou os IDs internos do formulário (JSF/Mojarra), o
que fazia **toda** nota de MS falhar com `FAILED_PARSE` (`no-items-found`) — a
chave era enviada vazia na consulta. O backend agora localiza os campos
dinamicamente (imune a essa renumeração). Nada muda no contrato do FE; notas de
MS que davam erro passam a processar normalmente. RS/PR/SC não eram afetados.

---

## 2026-07-03 (noite) — Snapshot de categoria + flag `categorySuggested`

Duas mudanças no modelo de categorias, seguindo o princípio "conhecimento novo
só afeta entradas novas":

1. **Novo campo `categorySuggested`** (boolean) em cada item de recibo. `true` =
   o valor em `category` é um **palpite nosso** (preview em nota pendente,
   item ainda não vinculado) — renderizar como sugestão (ex: chip tracejado
   "Mercearia?"). `false` = categoria confirmada/escolhida (chip sólido). Tocar
   pra corrigir/aceitar via `PUT .../items/{id}/category` transforma em escolha
   do usuário, que ganha de tudo pra sempre.
2. **Histórico congelado**: ao confirmar a nota, a categoria de cada item é
   fotografada. Recategorizações futuras (consenso da comunidade, melhorias de
   dicionário) passam a valer só para compras NOVAS — o histórico confirmado
   (e os dashboards de gasto por categoria) nunca muda sozinho. Exceção única:
   correção explícita do próprio usuário, que se aplica a tudo.

Nada quebra no FE: `category` continua no mesmo lugar; `categorySuggested` é
campo novo aditivo.

---

## 2026-07-03 (mais tarde) — Review screen agora mostra categorias SUGERIDAS

Itens de nota `PENDING_CONFIRMATION` voltavam com `category: null` em tudo (a
categorização só rodava na confirmação). Agora o campo `category` de itens
ainda não vinculados traz a **sugestão do dicionário** — o mesmo valor que a
confirmação vai aplicar. O shape do contrato não muda; o campo só deixa de ser
null quando temos sugestão. Edição do usuário via
`PUT .../items/{id}/category` continua ganhando da sugestão.

---

## 2026-07-03 (later) — Barcode scan lookup + position-aware best-markets

Two additions for the "scan a barcode in the store" flow:

1. **`GET /products/by-ean/{ean}`** — resolves a scanned barcode. Returns
   `{ known, product, catalogPreview }`: `known: true` gives the full product
   (price queries possible); `known: false` gives an EAN-catalog preview
   (`{ean, name, brand, category}` — no price data yet); 404 = unknown barcode.
2. **`best-markets` accepts `lat`/`lng`** — distance was always measured from
   the user's HOME; now the FE can pass the current GPS position for in-store
   comparisons: `GET /price-index/products/{id}/best-markets?lat=&lng=&radiusKm=`.

Suggested FE flow: scan → `by-ean` → if known, `best-markets` with current
position → list of cheapest nearby markets (k-anon protected, so sparse data
returns fewer rows rather than unreliable ones).

---

## 2026-07-03 — SC (Santa Catarina) NFC-e scanning is live

Fourth supported state: **RS, PR, SC, MS**. SC receipts arrive as a
`SecurityVerify.aspx?rq=...` URL (Cloudflare Turnstile challenge) — submit the
**full QR URL** as `qrPayload`, exactly as scanned. Same async flow as always:
`PROCESSING` → poll → `PENDING_CONFIRMATION`. Expect up to ~1 min (Turnstile
solve). The unsupported-state 400 message now lists SC too.

---

## 2026-07-02 (even later) — ⚠️ BREAKING: email verification is now a 6-digit CODE

`POST /auth/verify-email` body changed from `{ "token" }` to
`{ "email", "code" }`. The verification email no longer carries a link — it
carries a **6-digit code** the user types in the app, exactly like password
reset. Codes: single-use, 24 h TTL, locked after 5 wrong attempts (400),
resend invalidates the previous code, idempotent for already-verified
accounts. There is no web redirect anywhere in the auth flows anymore.

---

## 2026-07-02 (later) — ⚠️ BREAKING-ish: fail-fast 400s + new `parseErrorMessage` field

Three FE-visible changes from today's hardening pass:

1. **Unsupported-state submit now fails FAST with 400.** `POST /receipts` for a
   chave from a state we don't support (everything except RS, PR, MS today)
   returns an immediate **400 `receipt.state.unsupported`** (localized message)
   instead of accepting the receipt and failing it asynchronously. If you were
   handling that case by polling until FAILED_PARSE, handle the 400 at submit.
2. **New field `parseErrorMessage`** on `ReceiptResponse` (right after
   `parseErrorReason`): a **localized, user-showable** message for FAILED_PARSE
   receipts, resolved from `Accept-Language`. Render THIS to the user;
   `parseErrorReason` stays the machine key for debugging. `null` unless
   `status == FAILED_PARSE`.
3. **Polling always terminates now.** Stuck-in-PROCESSING receipts are
   auto-failed by a server sweeper after 10 min (reason
   `receipt.processing.timeout`), so a poll loop never spins forever.

Reminder of the 2026-06-30 contract (this is what crashed the first tester's
app): `POST /receipts` returns `status: "PROCESSING"` with **empty items and
null totals** — do NOT render the review screen from the POST response; poll
`GET /receipts/{id}` until the status leaves PROCESSING.

---

## 2026-07-02 — SEFAZ-MS receipts now work end-to-end + `parseErrorReason` field

### MS (Mato Grosso do Sul) NFC-e scanning is live
The captcha-gated MS portal flow was broken in several ways (session cookie
lost across redirects, HTTP→HTTPS redirect not followed, rejected captcha
tokens silently parsed as "no items"). All fixed and verified with a real
receipt: submit → `PROCESSING` → `PENDING_CONFIRMATION` with all items.
Expect the parse to take **up to ~3 minutes** (captcha solving) — keep polling.

### New field: `parseErrorReason` on `ReceiptResponse`
All receipt responses now carry `parseErrorReason` (string, null unless
`status == FAILED_PARSE`). It's a machine key (e.g.
`receipt.parse.failed:no-items-found`) meant for debugging/support — don't
show it raw to users.

### Infosimples fallback (backend-only, no contract change)
When the primary SEFAZ scraper exhausts retries, the backend can fall back to
a paid API covering all UFs (off by default, toggled server-side). No FE
change needed — the receipt just resolves normally.

---

## 2026-06-30 — Receipt submit is now ASYNC (status PROCESSING + polling)

### ⚠️ Contract change: `POST /api/v1/receipts`

`POST /receipts` no longer blocks until SEFAZ is fetched + parsed. It now returns
**immediately** with the receipt in a new status **`PROCESSING`** and an empty
`items` list. The slow work (SEFAZ fetch + captcha solve, which can take tens of
seconds) runs in the background.

**Why:** the captcha solve could push the request past the FE's HTTP timeout. The
async flow removes the timeout entirely.

**New status value:** `ReceiptStatus.PROCESSING` (added ahead of
`PENDING_CONFIRMATION` in the enum).

**FE flow now:**
1. `POST /receipts` → `201` with `{ id, status: "PROCESSING", items: [] }`.
2. Poll `GET /receipts/{id}` until `status` leaves `PROCESSING`:
   - `PENDING_CONFIRMATION` → go to the review screen (items now populated).
   - `FAILED_PARSE` → show the error (`parseErrorReason` carries the reason key).
3. Suggested polling: every ~2.5s, give up after ~90s and show "demorou demais,
   tente de novo" (the background job also caps the captcha solve internally).

Validation errors (monthly cap → `402`, duplicate confirmed chave → `409`) are
still returned **synchronously** on the `POST`, before PROCESSING is created.

## 2026-06-30 — Shopping list sole-check + household product search

### GET /api/v1/shopping-lists/sole

New endpoint: returns the household's single shopping list (full response) if exactly
one exists, or **404** when there are 0 or 2+ lists. Lets the FE skip the list-picker
screen entirely on the happy path without an extra round-trip.

### GET /api/v1/products/mine?query=

`GET /api/v1/products/mine` now accepts an optional `query` param
(case-insensitive substring match on name or brand). Replaces the need to filter
the global `/products` endpoint — this one is scoped to products the household has
actually bought.

---

## 2026-06-30 — Chave de acesso manual + suporte a CAPTCHA (MS)

### Input manual da chave de acesso

`POST /api/v1/receipts` agora aceita dois formatos no campo `qrPayload`:

- **QR code URL** — como antes (ex: `https://dfe-portal.svrs.rs.gov.br/...`)
- **Chave de acesso bare** — os 44 dígitos numéricos impressos na nota, com ou sem espaços

```json
{ "qrPayload": "50260677863223012709650190004048511190344086" }
// ou com espaços (stripped automaticamente pelo backend):
{ "qrPayload": "5026 0677 8632 2301 2709 6501 9000 4048 5111 9034 4086" }
```

Útil quando o QR code não consegue ser lido (nota amassada, mal impressa, etc.). O código de 44 dígitos está sempre impresso abaixo do QR na seção "CHAVE DE ACESSO".

### Suporte a CAPTCHA (MS e futuros estados)

O backend agora resolve automaticamente o reCAPTCHA v2 do portal SEFAZ-MS via CapSolver antes de buscar o DANFE. Transparente para o FE — o fluxo de submit/polling não muda. Estados que ainda não usam CAPTCHA não são afetados.

Novo código de erro possível ao submeter uma nota de estado com CAPTCHA não configurado (improvável em prod, mas útil saber):
- `503` com `messageKey: receipt.captcha.unavailable` — solver não habilitado para esse estado
- `502` com `messageKey: receipt.captcha.failed` — solver falhou após retries (saldo CapSolver esgotado, por ex.)

---

## 2026-06-29 — EAN catalog: step A2 no cascade de canonicalização

Nova camada entre o lookup interno de EAN (A1) e o dicionário de keywords (A3):

```
POST /api/v1/categorizer/ean-catalog/import
  body: [{"ean":"7894900010015","genericName":"Coca-Cola","brand":"Coca-Cola",
          "category":"BEVERAGES","source":"OPEN_FOOD_FACTS"}, ...]
  → { imported: N, skipped: M }
  Bulk-upsert de EANs na tabela ean_catalog. source aceita:
  OPEN_FOOD_FACTS | CURATED_IMPORT | USER_CONFIRMED
```

- Quando um novo EAN aparece num NFC-e, o sistema agora verifica a tabela
  `ean_catalog` antes de cair no dicionário de keywords — lookup O(1) por EAN.
- Se encontrado, a categoria e o nome genérico do catálogo enriquecem o produto
  criado (categoria do catálogo tem precedência sobre o dicionário de keywords
  para itens com EAN real).
- Tabela seed-ável via Open Food Facts: baixar o dump Brasil e importar via
  o endpoint acima. Nenhuma dependência externa em runtime.
- Requer `Role.ADMIN`.

---

## 2026-06-28 — Categorizer: CONSENSUS source, admin reset/import endpoints, expanded dictionary

### New `CONSENSUS` categorization source

Products graduated by the consensus promotion job now carry `source=CONSENSUS` (was `USER`). This lets the two origins be distinguished in queries and resets. `CategorizationSource` enum values: `NONE`, `DICTIONARY`, `LEARNED_DICTIONARY`, `ML`, `MERCHANT`, `USER`, `CONSENSUS`.

### Four new admin endpoints

```
GET /api/v1/categorizer/consensus
  → [{ id, ean, normalizedName, genericName, brand, category }, ...]
  Lists all products the consensus job graduated (source=CONSENSUS).
  Review and approve/reject before deciding to revert.

DELETE /api/v1/categorizer/learned
  → { removedEntries: N }
  Wipes every auto-promoted learned dict entry and resets the in-memory
  dictionary to the curated CSV seed.  Use when the learned dict drifts.

DELETE /api/v1/categorizer/consensus
  → { revertedProducts: N }
  Reverts all CONSENSUS-graduated products to source=NONE / null category
  so they re-enter the cascade on the next request.  Does NOT clear the
  learned dict — run DELETE /learned too for a full restore.

POST /api/v1/categorizer/dictionary/import
  body: [{ "token": "milho", "genericName": "Milho", "category": "GROCERIES", "sampleCount": 999 }, ...]
  → { imported: N, skipped: M }
  Bulk-upserts token→category mappings directly into the learned dict and
  swaps the in-memory reference immediately.  sampleCount defaults to 999.
```

All four require `Role.ADMIN`.

### Product dictionary expanded (396 → ~700 entries)

Added coverage for OTHER, CLEANING, PERSONAL_CARE, HEALTH, BEVERAGES, BAKERY, MEAT_DAIRY, and PRODUCE categories. Curated CSV seed lives at `src/main/resources/seed/product-dictionary.csv`.

---

## 2026-06-28 — Household data merge on join/leave (behind a flag, dark)

Joining/leaving a household can now optionally **bring your data with you** and
**restore it when you leave**. **Shipped OFF** (`economizai.households.merge-enabled`
=false) — no behavior change until enabled, but the contract is here so the FE can
build against it.

- **`POST /households/join`** body gains two optional fields:
  - `bringData` (bool) — merge your data into the household you're joining.
  - `mergeCategories` (string[]) — which categories to bring; omit/empty = all.
    Values: `RECEIPTS, SHOPPING_LISTS, MANUAL_PURCHASES, CONSUMPTION_SNOOZES,
    CATEGORY_OVERRIDES, CUSTOM_CATEGORIES, PRODUCT_ALIASES, MARKET_ALIASES,
    BRAND_PREFERENCES`. On a conflict (same item already in the target), the
    target's copy is kept; yours is parked and restored if you later leave.
  - Both optional — omitting them = today's behavior (membership only).
- **`POST /households/leave`** — when merge is enabled, you return to your original
  data (restored automatically). No body change yet.
- **Consent (for taking a partner's data on split):** new endpoints
  - `GET /households/consents/pending` — requests awaiting MY approval.
  - `POST /households/consents/{id}/approve` — let the requester copy my data.
  - `POST /households/consents/{id}/deny` — refuse.
  - All return `ConsentResponse { id, requesterId/Name, grantorId/Name, scope,
    status, expiresAt }`; 400 on not-mine / already-resolved / expired.

---

## 2026-06-23 — Password reset is now a 3-step CODE flow (was a link) ⚠️ FE change

Password reset no longer emails a link — it emails a **6-digit code** the user
types into the app. Better for mobile (no deep-link/URL handling). **Three steps:**

```
1. POST /api/v1/auth/forgot-password    { "email" }                         -> 204 (always; emails the code)
2. POST /api/v1/auth/verify-reset-code  { "email", "code" }                 -> 204 valid · 400 invalid/expired/used
3. POST /api/v1/auth/reset-password     { "email", "code", "newPassword" }  -> 204 done · 400 invalid/expired/used
```

- **Step 2 is optional but recommended** — validate the code (without consuming it)
  to gate the new-password screen, so the user learns the code is wrong *before*
  typing a password.
- The code is **single-use**, **expires in 60 min**, and requesting a new one
  **invalidates the previous** code.
- **Breaking:** `reset-password` body changed from `{ token, newPassword }` to
  `{ email, code, newPassword }`. The old link-style `?token=` flow is gone.
- `forgot-password` still always returns 204 (no email enumeration).

(Full infra + links: [INFRASTRUCTURE.md](./INFRASTRUCTURE.md).)

---

## 2026-06-12 — receipts: captcha-gated states (MS) return a clear "coming soon" instead of failing

Mato Grosso do Sul's portal hides the receipt behind a reCAPTCHA, so we can't
read it until a captcha-solver provider is configured (a backend/ops decision,
not shipped yet). Until then, submitting an MS NFC-e returns **HTTP 503** with
message key `receipt.captcha.unavailable` ("NFC-e from this state requires a
captcha step we haven't enabled yet — coming soon"). The FE should treat 503 on
receipt submit as a soft "try again later / state not ready" state, distinct
from 400 (bad QR) and 502 (SEFAZ down). The whole solving path is built and
dormant; the day the key is set, MS just works with no API change.

---

## 2026-06-12 — receipts: Paraná (PR) NFC-e now supported 🎉

Second state live (after RS). PR's portal (`www.fazenda.pr.gov.br`) renders the
same DANFE layout the parser already understands — verified end-to-end with a
real Curitiba receipt. Scanning a PR QR code now works exactly like RS. Two
related fixes that also help RS:

- **QR URLs with raw `|` separators were being rejected** since the SSRF guard
  shipped (earlier today) — real QR payloads carry pipes in the query string.
  Fixed; full-URL payloads work again for all states.
- **Merchant-internal item codes are no longer stored as EANs**: some chains
  (e.g. drugstores) print a 6-7 digit internal code where others print the
  GTIN/EAN. Codes under 8 digits now leave `ean` null and product matching
  falls back to the description, preventing bogus cross-merchant product
  merges. Items from such receipts arrive with `ean: null` — already a normal
  case for the FE.

---

## 2026-06-12 — deals: DISMISSED/MUTED telemetry now drives a relevance filter (SHADOW mode)

The `DISMISSED` / `MUTED` events the app already reports via
`POST /notifications/events` now feed a per-user suppression filter for
`GET /deals` and the daily digest: mute hides the product everywhere (180d),
dismissal hides it for 14d — scoped to the **(product, market) pair when the
DISMISSED event carries `marketCnpj`**, product-wide when it doesn't (so send
`marketCnpj` on dismissals for the precise behavior).

**No visible change yet**: the filter launches in SHADOW mode (computed and
measured, never applied). It flips ON server-side once the validation report
shows it wouldn't hide deals users actually want — after that, dismissed/muted
deals genuinely disappear from the screen and digest for that user.

---

## 2026-06-12 — users: new `GET /users/me/subscription` (self-serve billing status)

Returns `{ tier, status, provider, currentPeriodEnd }` so the app can render
"PRO until {date}" and route "manage subscription" to the right store.
`status`/`provider`/`currentPeriodEnd` are `null` for FREE users who never
subscribed. Reminder of the billing model: purchases happen FE-side (App
Store / Play via RevenueCat) and the backend reflects them via webhook — there
is no purchase endpoint in this API.

---

## 2026-06-12 — receipts: QR codes carrying a non-SEFAZ URL are now rejected

Security hardening (SSRF guard). If the scanned QR payload is a full URL, the
backend now only fetches it when the host is an official SEFAZ domain
(`*.svrs.rs.gov.br` / `*.sefaz.rs.gov.br` for now). Anything else returns the
existing `400` with message key `receipt.qr.invalid` — the same error the FE
already handles for malformed QR codes. Payloads that are just the chave (the
common case) are unaffected.

---

## 2026-06-10 — insights: new `GET /insights/markets/top-discounts` (where you got the most discount)

New endpoint ranking the household's markets by the **discount received** there (biggest first), built on the receipt-level discount we now track. Markets that gave no discount are excluded.

```
GET /api/v1/insights/markets/top-discounts?from=&to=&limit=5
→ [{ cnpj, marketName, marketFriendlyName, grossTotal, discount, discountRate, receiptCount }, ...]
```
`discount` is R$ received; `grossTotal` is gross spend; `discountRate` = `discount / grossTotal` as a 0–1 fraction (format as %). Same date/limit params as `/insights/markets/top`. Personal (your household's receipts), so it works without community volume.

---

## 2026-06-10 — insights/dashboard: gross spend + a `discount` figure per slice

Follow-up to the discount change below. Spend totals stay **gross** (sum of item prices, internally consistent across every breakdown) and we now report the receipt-level discount **alongside**, aggregated for whatever slice you're showing. Net spend = `total − discount`, computed by the FE.

- **`GET /insights/spend`** (`SpendInsightsResponse`): new top-level `totalDiscount`; new `discount` on each `byMonth` / `byWeek` / `byMarket` bucket. `byCategory` buckets have **no** per-bucket discount (a receipt-level discount can't be split across categories) — use `totalDiscount` there.
- **`GET /insights/query`** (`InsightsQueryResponse`): new `summary.totalDiscount`; new `discount` on each bucket for **receipt-level** groupings (`DAY`/`WEEK`/`MONTH`/`YEAR`/`MARKET`/`CHAIN`). For `CATEGORY`/`PRODUCT` groupings `bucket.discount` is **`null`** — use `summary.totalDiscount`.
- **Dashboard** (`SpendSnapshot`): new `discount` for the current month.

No rateio: we never split a discount across items/categories. `discount` is only attached where each receipt falls wholly in the slice.

---

## 2026-06-10 — receipts: discounts are no longer distributed across items; new `discountTotal` field

**Behavior change (price accuracy):** we **stopped distributing** a receipt-level discount across item prices. Until now, when a receipt's items summed to more than what was paid, we spread the gap proportionally over every line — which silently distorted per-item prices (a promo on one item bled into all the others). Now **item prices are kept exactly as printed** on the NFC-e (`unitPrice` / `totalPrice` are gross), and the receipt's discount is tracked separately.

**New field:** `ReceiptResponse` and `ReceiptSummaryResponse` now carry `discountTotal` — the "Descontos R$" value as printed on the receipt, or `null` when the receipt declared no discount. Future use: surfacing things like "markets with the biggest discounts."

**FE impact — read this:** because items are now gross, `householdTotalAmount` (and any spend total derived from summing items) is now **gross of the discount**, so it can be **higher than `totalAmount`** (the amount actually paid). The relationship is: `sum(item totals) ≈ totalAmount + discountTotal`. If you show "what I spent," use `totalAmount` (paid) and render `discountTotal` as a separate "desconto" line; don't sum item prices and expect it to equal what was paid.

---

## 2026-06-09 — notifications: `destination` for deep-linking + discovery moved to the digest

**New field:** every `NotificationResponse` now carries `destination` — a routing hint derived from `type` so the FE knows which screen to open on tap (the id it needs is already in `payload`). Values: `DEALS` (`PROMO_PERSONAL`/`PROMO_COMMUNITY`/`CHEAPER_MARKET`/`DEALS_DIGEST`/`DIGEST`), `REPLENISHMENT` (`STOCKOUT`), `PRODUCT` (`PRICE_DROP`), `BUDGET` (`BUDGET`), `INBOX` (`SYSTEM` + fallback). See API.md §10b for the table.

**Behavior change:** discovery notifications (`PROMO_*` / `CHEAPER_MARKET`) are no longer pushed in real time on receipt confirm — they're now delivered **only via the daily deals digest**. Real-time pushes are now just the user's explicit alerts (`PRICE_DROP`, `BUDGET`). No FE action required, but expect discovery to arrive batched once/day rather than instantly.

---

## 2026-06-09 — notifications: `PRICE_ABOVE` rule type removed

We dropped the `PRICE_ABOVE` (price-ceiling) rule type. **FE: stop offering it** in the rule-creation UI — posting `type: "PRICE_ABOVE"` now fails validation (400) like any unknown type. Any existing `PRICE_ABOVE` rows are deleted server-side. The cheaper-market / deals features already cover the "don't overpay" use case. `PRICE_DROP`, `STOCKOUT`, `BUDGET` and the system defaults are unchanged.

---

## 2026-06-09 — Savings attribution (Phase D): "você economizou R$ X com as dicas"

New: the backend now closes the deals loop by attributing **realized R$ savings** to the deals we surface — the product's north-star metric.

- **New endpoint** `GET /api/v1/users/me/savings` (JWT, scoped to your household) → `{ "totalSavings": 42.50, "conversions": 7, "last30DaysSavings": 15.00 }`. Empty history → all zeros, 401 if unauthenticated. Use it for a "você economizou R$ X com nossas dicas" card.
- **`CONVERTED` is now tracked server-side, automatically.** When you confirm a receipt, the backend checks each purchased item against deals we surfaced to your household in the last **14 days**; a match becomes a conversion and records the realized savings `(previousLastPaid − paidUnitPrice) × quantity` (only when positive). No client action is required — the FE never posts `CONVERTED` (it's a server-only event type, as before).
- Attribution is **best-effort + correlational** (a recently-surfaced deal that the user then bought), never causal, and it never blocks or slows down a confirm.

## 2026-06-09 — Daily deals digest (Phase C): preferences + `DEALS_DIGEST` push

New: a scheduled daily rollup push that points to the deals screen, sent at most **once per day**, and only when there's something **new** worth telling the user (a brand-new deal, a meaningfully bigger discount, or a deal that lapsed past the lookback window). Standing, unchanged deals never re-notify.

- **Digest preferences** (JWT, scoped to you):
  - `GET /api/v1/users/me/digest-preferences` → `{ "frequency": "DAILY|WEEKLY|OFF", "sendHour": 0-23 | null }`.
  - `PUT /api/v1/users/me/digest-preferences` with the same shape. `frequency` is required; `sendHour` is optional (an override — `null` lets the backend infer your typical shopping hour). `sendHour` outside `0-23` → **400**. `frequency=OFF` is the master switch (no digest ever).
- **New inbox notification type `DEALS_DIGEST`.** It shows up in `GET /api/v1/notifications` like any other. Body reads e.g. `"Café 22% mais barato — e mais 3 ofertas pra você"` (singular/plural handled; no "e mais" when there's only one). Its `payload` carries a deep-link hint (`deeplink: "economizai://deals"`, `screen: "deals"`), `newsworthyCount`, and the best deal's `bestProductId` / `bestMarketCnpj` / `bestDiscountFraction`.
- **FE action:** tapping the digest should open the **deals screen** and fire `PUSH_OPENED` via `POST /api/v1/notifications/events` (key it with the notification id). Then it's the normal deals flow (`SCREEN_OPENED`, `DEAL_VIEWED`, `DEAL_TAPPED`).
- Send time defaults to ~16:00 (BR after-work) when we don't yet know your habits; otherwise it's inferred from your household's confirmed-receipt shopping hour. Timezone is **America/Sao_Paulo** for v1. WEEKLY currently goes out Thursdays.

## 2026-06-09 — "Ofertas pra você": new `GET /api/v1/deals` screen endpoint

New endpoint: `GET /api/v1/deals` (JWT, scoped to your household). The active, ranked list of discounts relevant to you right now — for each product your household buys, the best **currently-observed** community price at a relevant market that beats what you last paid by a meaningful margin.

- Params (all optional): `includeNearby` (default `false` = watched markets only; `true` also pulls in markets within `radiusKm` of home), `radiusKm`, `limit` (default 20; `limit <= 0` → `[]`).
- Each row carries `productId`, `productName`, `category`, `marketCnpj`, `marketName` (your friendly name), `currentPrice`, `lastPaidPrice`, `savingsAmount`, `savingsPct`, `discountFraction`, `distinctHouseholds`, `distanceKm` (nullable), `isWatched`, `observedAt`.
- Ranked by savings desc; trivial drops are filtered out (progressive bar: ~20% on cheap items, ~5% on pricey ones). K-anon protected — community price shows only when ≥ 3 households contributed. Returns `[]` when the index is off or nothing qualifies.

FE reminder: this endpoint is read-only and fires **no** telemetry. Fire `SCREEN_OPENED` when the screen opens, and `DEAL_VIEWED` / `DEAL_TAPPED` as the user scrolls/taps, via `POST /api/v1/notifications/events` — key them by the row's `productId` + `marketCnpj`. See API.md §7b.

---

## 2026-06-09 — Notification telemetry: report client-side engagement events

New endpoint: `POST /api/v1/notifications/events` (JWT, scoped to you) — body `{ "type": "...", "notificationId"?, "productId"?, "marketCnpj"? }`, returns `202`.

The app should fire one of these whenever the user engages with a notification or a surfaced deal:
- `PUSH_OPENED` — opened a push
- `SCREEN_OPENED` — opened the deals/notifications screen
- `DEAL_VIEWED` — a deal scrolled into view
- `DEAL_TAPPED` — tapped a deal
- `ADDED_TO_LIST` — added the deal's product to a shopping list
- `DISMISSED` — dismissed/swiped a deal or notification
- `MUTED` — muted a product/market/type

This is **telemetry only** for now — nothing changes in what you receive. We're capturing the engagement signal so a future ranking engine can make notifications smarter and more relevant (surface the deals you act on, suppress the ones you ignore). The more accurately the app fires these, the better that ends up. Server-only types (`SENT`/`DELIVERED`/`CONVERTED`) and unknown types are rejected with `400`.

---

## 2026-06-09 — Phone number + verification; SMS/WhatsApp notifications now deliver

New endpoints to set and verify a user phone:
- `PATCH /api/v1/users/me/phone` — body `{ "phoneNumber": "+5551999999999" }` (E.164). Stores the number (as unverified), generates a 6-digit OTP, and sends it via SMS. Returns `204`. Malformed/non-E.164 number → `400`.
- `POST /api/v1/users/me/phone/verify` — body `{ "code": "123456" }`. Correct + unexpired code marks the phone verified and returns `204`; wrong/expired/missing → `400`.

The `User` now has `phoneNumber` (E.164, nullable) and `phoneVerified` (boolean) on the backend. The **SMS** and **WHATSAPP** notification channels are now real: when the server has Twilio configured **and** the user has a verified phone, notifications routed to those channels actually deliver (via Twilio's Messages API). If Twilio isn't configured or the phone isn't verified, dispatch degrades gracefully (the in-app inbox row is still written, marked not-delivered with a `twilio_not_configured` / `phone_not_verified` reason) — no error to the user. ALEXA remains a stub.

---

## 2026-06-09 — Default notification rules backfilled for existing users

Default notification rules (PROMO_COMMUNITY, CHEAPER_MARKET, DIGEST, PROMO_PERSONAL) are now materialized for **all existing users** on startup, not just new signups / first time the screen is opened. `GET /api/v1/notification-rules` already seeds them lazily, so the response shape is unchanged — but accounts that never opened the screen will now have the toggles present immediately. Idempotent; triggers were already firing regardless (an absent default counts as enabled).

---

## 2026-06-08 — LGPD data export is now complete (response grew)

`GET /api/v1/users/me/export` now returns **all** of the user's personal data, not just account + household + receipts. New sections on `UserDataExportResponse`: `accountExtras` (push token, emailVerified/At, contributionOptIn), `notificationRules`, `watchedMarketCnpjs`, `subscription`, `marketAliases`, `customCategories`, `categoryOverrides`, `manualPurchases`, `shoppingLists`, and `notifications` (inbox). Purely additive — existing fields unchanged. (Account **deletion** — `DELETE /users/me` — was already complete: cascades all personal data, deletes the household when its last member leaves, and leaves anonymized price data intact per LGPD.)

---

## 2026-06-08 — RevenueCat billing webhook + subscription expiry (apps are PRO-ready)

The mobile PRO flow is now backend-complete. The apps integrate **RevenueCat** (one SDK over Apple StoreKit + Google Play Billing); when a purchase/renewal/expiration happens, RevenueCat calls **`POST /api/v1/webhooks/revenuecat`** and we sync the user's tier automatically.

- **FE/RevenueCat config (the only thing needed):** set RevenueCat's `app_user_id` to **our user's UUID** (preferred) or email so the webhook resolves the right account, and set the webhook **Authorization header** value (matched against `REVENUECAT_WEBHOOK_AUTH`; empty = endpoint disabled/fail-closed).
- Purchases/renewals → PRO until the period end; expirations → FREE; a bare cancellation keeps access until the period actually lapses.
- New **safety-net sweep** downgrades a PRO whose paid period elapsed without a renewal (failed payment/provider hiccup), so no one stays PRO for free.
- Nothing changes for the FE's PRO checks — keep reading `subscriptionTier` from the user. Web (Mercado Pago/PIX) is the remaining piece, pending your provider choice.

---

## 2026-06-08 — validation hardening from a full API audit (behavior fixes)

Four small behavior/validation fixes surfaced by an end-to-end endpoint audit:
- **`GET /api/v1/receipts` now accepts a `status` filter** (`PENDING_CONFIRMATION|CONFIRMED|REJECTED|FAILED_PARSE`, optional). An **invalid value now returns 400** (it was previously ignored). Absent = all statuses, as before.
- **Shopping-list items enforce "exactly one"** of `productId`/`freeText`: sending **both** now returns **400** (previously wrongly accepted); neither still 400; exactly one still 201.
- **`POST/DELETE /api/v1/markets/watched/{cnpj}`** now returns **400** for a malformed CNPJ (was 404) and accepts formatted CNPJs (strips `.`/`/`/`-`) — consistent with the rename endpoints.
- **Rate-limit `429` body** now matches the standard error shape (ISO-8601 string `timestamp`, UTF-8) instead of an array timestamp; `Retry-After` header unchanged.

---

## 2026-06-07 — market rename now validates CNPJ (400 instead of 500)

`PUT/DELETE /api/v1/markets/{cnpj}/name` used the `{cnpj}` path value as-is.
A malformed CNPJ (e.g. a wrong-length value) crashed with a **500** when it
overflowed the DB column. Now the CNPJ is normalized (formatting like
`12.345.678/0001-99` is accepted and stripped) and a value that isn't exactly
14 digits returns **400 `market.cnpj.invalid`**. **FE action:** none, unless you
were sending formatted/invalid CNPJs — those now get a clean 400.

## 2026-06-07 — PRO tier mechanism (gates DORMANT by default), admin set-tier, billing webhook

The FREE/PRO subscription tier (`User.subscriptionTier`) has a complete gating mechanism behind a single `SubscriptionGateService` — but **enforcement is OFF by default** (`SUBSCRIPTION_ENFORCE=false`). **Nothing is gated today: every feature is allowed for all users.** There are **no 402s** and no history/limit clamps until monetization launches; flip `SUBSCRIPTION_ENFORCE=true` to activate the caps below. When enforced, a FREE user hitting a PRO gate gets **HTTP 402 `subscription.upgrade_required`**.

**Gates when enforcement is ON (FREE limits; PRO = unlimited):**
- **Watched markets** — FREE pins up to **3** markets. Pinning a 4th → 402. Unpin / re-pin of an existing one is always allowed.
- **Receipt uploads** — FREE may submit **5 receipts per calendar month** (counts ALL statuses to prevent reject/resubmit gaming). The 6th `POST /receipts` → 402.
- **History window** — FREE analytics/history is clamped to the last **90 days**. Affects `GET /insights/spend`, `/insights/query`, `/insights/categories|markets/top`, `/insights/products/{id}/price-history`, and `GET /items`: a `from` older than 90d (or omitted) is silently floored. PRO unaffected. (No error — results are just windowed.)
- **Notification delivery** — FREE always gets the in-app inbox row, but push/email **dispatch is skipped** (audit row `delivered=false`, `failureReason=free_tier_inbox_only`). PRO gets push/email.
- **Basket optimization** — `POST /shopping-list/optimize` is PRO-only → 402 for FREE.

**New endpoints:**
- `PUT /api/v1/admin/users/{id}/subscription-tier` (ADMIN) — body `{ "tier": "PRO" | "FREE" }`; returns the admin user detail. PRO activates a manual subscription, FREE cancels it.
- `POST /api/v1/webhooks/subscription` (public; provider-agnostic) — body `{ "userEmail", "action": "ACTIVATE"|"CANCEL", "provider", "providerRef", "currentPeriodEnd" }`. Verified by the `X-Webhook-Secret` header against `economizai.billing.webhook-secret` (**fails closed**: when the secret is unset the webhook rejects everything; wrong secret → 401; constant-time compare; unknown user → 200 no-op). This is the seam a real payment provider (Stripe / Mercado Pago) maps its webhook onto.

**Heads-up for FE:** today nothing is gated (enforcement off) — no 402s, full history for everyone. Build the **402 → "show upgrade prompt"** handling now (distinct from 403) so the FE is ready when enforcement flips on; at that point FREE dashboards will window to 90 days.

## 2026-06-07 — Category lens: one product = one category, household vs global view

`GET /items`, `GET /insights/categories/top`, and `GET /insights/query` now take a **`categoryView`** param (`HOUSEHOLD` default, or `GLOBAL`). Each product belongs to **exactly one** category per household — **no double-counting**.

- **HOUSEHOLD (new default):** the effective category is the household's override (custom name or corrected enum) when set, else the global `Product.category`. `?category=GROCERIES` now returns/buckets products whose *effective* category is GROCERIES and **excludes** any product moved to a custom category or a different enum. Category insight buckets are re-aggregated by effective category (custom-category spend becomes its own bucket).
- **GLOBAL:** old behavior — filter/group purely by `Product.category`. Pass `categoryView=GLOBAL` to reproduce pre-lens numbers.

**Response additions (additive, nullable):**
- `PurchasedItemResponse` gains **`globalCategory`** (always the global enum, or null) alongside the existing effective `category`.
- `CategoryBucket` gains **`label`** (always present, display-safe); `category` is now **null** for custom-category buckets in the household view.

**Heads-up:** the default for these three endpoints changed from global to household. If a chart depended on the old global grouping/filtering, add `&categoryView=GLOBAL`.

---

## 2026-06-07 — Sign in with Google & Apple (new endpoints)

Social login is in. The app does the **native** Google/Apple sign-in, then posts the provider token:

```
POST /api/v1/auth/google  { "idToken": "..." }
POST /api/v1/auth/apple   { "identityToken": "...", "name": "Maria Silva" }
```

Both return the **same** `{ token, refreshToken, user }` as password login, so the FE flow after sign-in is identical. The backend verifies the token against the provider's keys; first-time users get a solo household + `emailVerified=true` (no verification email), and an existing local account with the same email is linked. `name` is only needed on the first Apple sign-in. Bad token → `401 auth.oauth.invalid`. Requires the backend to be configured with the app's Google/Apple client IDs (see infra) — without them the audience check is skipped (dev only).

---

## 2026-06-07 — household product list, "where is it cheapest", and custom market names (new endpoints)

Three additions for the products/markets screens:

- **`GET /api/v1/products/mine`** — the products **your household actually buys** (not the global catalog), newest purchase first, each with `timesBought`, `lastBoughtAt`, `lastUnitPrice`, and last market. Use this for the "my products" screen; keep `GET /products` for global autocomplete.
- **`GET /api/v1/products/{id}/markets?includeNearby=&radiusKm=`** — "onde está mais barato": one row per market (your watched markets always; nearby only if `includeNearby=true`), cheapest first, with `price`, `distanceKm`, `watched`/`visited`. **Privacy:** `priceType` is `OWN_LAST` (your exact last paid price at a market you've shopped at) or `COMMUNITY_MEDIAN` (k-anon median, only when ≥3 households contributed — sub-K markets are omitted, never shown with a single price). Full rationale in API.md §5.
- **`PUT/DELETE /api/v1/markets/{cnpj}/name`** — give a market a **household-only custom name**. The original `name`/`marketName` is **never overwritten**: every market-bearing response now also carries a **`friendlyName`** field that defaults to the original and is replaced by your rename when set. **Display `friendlyName`.** Applies for your household everywhere a market appears (markets list, product-markets, receipts, items, insights, price history, notifications); the global name and other households are untouched.

---

## 2026-06-07 — profile pictures now persist across redeploys (bugfix)

Uploaded profile pictures were disappearing after a while — the cause was that
the server stored them in a container-local `/tmp` dir that gets wiped on every
deploy, while the DB kept pointing at the now-missing file (so the API silently
served the initials-avatar fallback). Pics are now kept on a persistent volume
on the server, so an uploaded picture survives deploys. No API/payload changes —
`PUT/GET/DELETE /users/me/profile-picture` are unchanged. **FE action:** none.

---

## 2026-06-07 — notifications: user-created rules + toggleable defaults (new endpoints)

Big notification upgrade. There's now **one place to create and toggle every notification**: `GET/POST/PATCH/DELETE /api/v1/notification-rules` (full contract in [API.md §10f](./API.md)). `GET` returns the user's rules **and** the system defaults as toggleable entries (`isDefault: true`, `active`), so a single settings screen can drive everything.

New **user-creatable** rule types: `STOCKOUT` (replenishment — we predict a regularly-bought product is about to run out from your buying cadence and warn you N days ahead), `BUDGET` (monthly household spend cap). `PRICE_DROP` ("avise-me quando") is unchanged. New **defaults** you can turn on/off: `CHEAPER_MARKET`, `PROMO_COMMUNITY`, `DIGEST` (weekly summary), plus the existing `PROMO_PERSONAL`.

`CHEAPER_MARKET` = "someone bought a product I buy, at one of **my watched markets**, cheaper than I last paid." By default it only watches your favourite markets; set `radiusKm` on the rule to also include markets near home. The required drop scales with price (≈20% on a ~R$1 item → ≈5% on a ~R$200 item).

**Channels:** push (live) and email (live once SMTP creds are set) work; `ALEXA`, `SMS`, `WHATSAPP` are scaffolded (selectable, but dispatch is a no-op stub for now). A per-rule `channel` overrides your per-type preference.

**Migration note:** `/api/v1/alerts` still works exactly as before — it's now a thin alias over `PRICE_DROP` rules. The `NotificationResponse.type` set grew (see §10b); `PRICE_DROP` payload key renamed `alertId` → `ruleId`.

---

## 2026-06-07 — pharmacy detection now CNAE-verified (backend accuracy)

The pharmacy-merchant signal behind `HEALTH` categorization is now **verified from the CNPJ's CNAE** (economic activity) via an external registry — `4771*` = pharmacy, `4711*/4712*` = supermarket — instead of only guessing from the merchant name. It runs async/best-effort (never blocks import; falls back to the name guess if the lookup fails), and when a merchant is confirmed a pharmacy, its previously-`OTHER` items are backfilled to `HEALTH`. No API contract change — purely better category accuracy on items the FE already reads.

---

## 2026-06-07 — new `HEALTH` category

Added a 9th global category **`HEALTH`** (PT label "Saúde") for drugstore/health items — vitamins, meds, supplements, first-aid. The category enum is now: `GROCERIES, BEVERAGES, PRODUCE, MEAT_DAIRY, BAKERY, CLEANING, PERSONAL_CARE, HEALTH, OTHER`. Add a `HEALTH` chip/label to your category map.

Two-layer classification, item-first: (1) the **product dictionary** tags meds/supplements/dosage-forms as `HEALTH` wherever bought (so a vitamin at a supermarket is caught too); (2) for items the dictionary can't place, a **pharmacy-merchant fallback** — if the receipt's merchant is a drugstore, the unknown item defaults to `HEALTH` instead of `OTHER`. Items the dictionary already knows keep their category (candy/cleaning bought at a drugstore stay correct). No contract change beyond the new enum value.

---

## 2026-06-07 — `productId` on receipt items

`ReceiptResponse.items[*]` now includes **`productId`** (`uuid`, or `null` for an unmatched item). This lets the **review screen drive category migration directly**: collect the `productId`s of the items the user checks and `POST /categories/migrate` — no need to cross-reference `/items` first. Additive field, nothing else changed.

---

## 2026-06-07 — SEFAZ fetch auto-retry + training endpoints locked to ADMIN

- **NFC-e import now auto-retries** transient SEFAZ failures (the SVRS portal is flaky — 5xx/timeouts/empty body). Up to 5 attempts total: immediate retry, then 5s/5s/5s. 4xx (bad chave) is **not** retried. Net effect for the FE: fewer spurious `502 receipt.sefaz.fetch.failed` — the user no longer has to keep re-submitting. Trade-off: a submit can take up to ~15s+ when the portal is down, so keep your loading state patient before showing an error.
- **Training/catalog-mutating categorizer endpoints are now ADMIN-only** (`POST /categorizer/retrain`, `/auto-promote`, `/promote-consensus`) — a normal user gets `403`. Read/debug GETs (`classify`, `ml/predict`, `benchmark`, `quality/history`, `status`) are unchanged.

---

## 2026-06-07 — custom categories + product migration (new screen)

Households can create their own categories (e.g. "Frutas") and migrate products into them — household-scoped, the global product/catalog is untouched.

- `GET /api/v1/categories` → all categories the household can use: the 9 global enums (`{id:null, name, custom:false}`) + the household's custom ones (`{id, name, custom:true}`).
- `POST /api/v1/categories` `{ "name": "Frutas" }` → 201 create (idempotent on name).
- `DELETE /api/v1/categories/{id}` → remove a custom category (its product overrides revert).
- `POST /api/v1/categories/migrate` `{ "productIds": [...], "targetCategory": "GROCERIES" | null, "targetCustomCategoryId": "<uuid>" | null }` → moves the selected products into the target (exactly one target). Household-scoped.
- The migration UI flow: list a category's items with `GET /items?category=GROCERIES` (existing), let the user check items, then `POST /categories/migrate`. View a custom category's items with `GET /items?customCategoryId=<uuid>`.
- **Heads-up:** an item's `category` field can now be a **custom-category name** (not just an enum) when the household has migrated it. Treat it as a display string.

---

## 2026-06-07 — brand registry expansion + brand backfill

Filled in lots of brands. Expanded the brand registry with ~45 brands found across the real catalog (Spaten, Andorinha/D'Aguirre, Coqueiro, McCain, Piracanjuba abbrev, Q-Boa, Limpol, Três Corações, …). New admin op `POST /api/v1/admin/products/refresh-brands` re-runs brand extraction over the catalog and **fills products missing a brand** (never overwrites an existing one) — needed because brand, like category, is set only at product creation. No FE change (the FE just sees more products with `brand` populated).

---

## 2026-06-07 — user corrections graduate to the learned dictionary (consensus)

The "evidence → truth" step. A single household's correction stays personal (as before), but when **enough distinct households correct the same product to the same category** (default ≥2), it graduates:
- the **global product** gets that category (so everyone sees it), and
- tokens that recur across consensus products (≥2, no disagreement) enter the **learned dictionary** — so similar future products inherit the category automatically.

This makes user feedback actually *teach* the deterministic system (cascade source #2), with no reliance on ML. Runs daily; manual trigger `POST /api/v1/categorizer/promote-consensus` (ops). No FE change.

---

## 2026-06-07 — user category correction (household-scoped "evidence, not truth")

Users can fix a wrong category on a receipt item. The fix is **per-household**: it changes what *your* household sees, and **never** mutates the global product (other households are unaffected). Each correction is also recorded as evidence/vote for a future cross-household consensus pass.

- **`PUT /api/v1/receipts/{id}/items/{itemId}/category`** body `{ "category": "MEAT_DAIRY" }` → 200, returns the updated `ReceiptResponse` with the corrected category applied. `400` if the item isn't linked to a product yet.
- The override is then applied wherever that household views the product's category: **`GET /receipts/{id}`** and **`GET /items`**. (Aggregates/insights still use the global category for now.)
- Categories are the fixed enum (`GROCERIES, BEVERAGES, PRODUCE, MEAT_DAIRY, BAKERY, CLEANING, PERSONAL_CARE, OTHER`). Custom user categories are not supported yet.

---

## 2026-06-06 — dev tooling: full-chain trace + ML-only inspector

Developer endpoints for improving the algorithm (not FE-facing):
- `GET /categorizer/classify` (full chain) now also returns `mlApplied` — whether the ML guess is actually used live (currently false).
- `GET /categorizer/ml/predict?description=...` — the **ML model alone** (category + genericName + confidence), ignoring dictionary + the apply gate. For inspecting/teaching the model in isolation.

---

## 2026-06-06 — ML benched + brand/quantity quality tracking

- **AI categorization is gated OFF** in the live cascade (it was confidently wrong at current data volume). New items are categorized by the **dictionary only** (deterministic) or left uncategorized — no more confidently-wrong AI labels. The model is still trained and measured so we can switch it back on when it's good enough (env `ML_CATEGORY_APPLY_ENABLED`).
- **Quality tracking now covers brand + quantity too**, not just category. `GET /categorizer/benchmark` returns per-field accuracy (category / brand / quantity) plus a **shadow** ML accuracy (the model measured even while benched). `/categorizer/quality/history` snapshots now record `brandAccuracyPct`, `quantityAccuracyPct`, `mlAccuracyPct`.

No FE contract change.

---

## 2026-06-06 — categorization quality history (trend over time)

The quality metric is now **persisted**, so we can see if categorization is improving or regressing over time. A snapshot is written on every benchmark run and every backfill (`V31` table `categorization_quality_snapshots`).

- `GET /api/v1/categorizer/quality/history?limit=50` → snapshots newest-first: `{ recordedAt, trigger (BENCHMARK|BACKFILL), accuracyPct, benchmarkCorrect/Total, catalogProducts, catalogCategorized, catalogCoveragePct, mlReady }`.
- Each snapshot pairs **golden-set accuracy** (cascade correctness) with **catalog coverage** (% of real products that have a category) — so both "are our rules right" and "how much of the catalog is categorized" are tracked.

---

## 2026-06-06 — categorization quality: tracked metric + dictionary expansion

Product categories get more accurate, and we can now **measure** it.

- **Quality metric:** `GET /api/v1/categorizer/benchmark` runs the categorizer over a curated golden set and returns `accuracyPct` (+ the failing cases). Run it after each enhancement to see if we're improving. Dictionary-only accuracy is now **100%** on the golden set (was the goal of this pass).
- **Dictionary expanded** with the NFC-e abbreviations and compound terms that were mis-categorizing (`milho`, `batata frita`, `lays`/`salgadinho`, `bisc`, `ling`, `vh`, `abs`, `lav louca`, …). Compound phrases now win over bare tokens (so `batata frita` → GROCERIES, not PRODUCE via bare `batata`). This improves **new** scans going forward.
- **Backfill is gated for safety:** admin `POST /admin/products/recategorize` now applies **dictionary suggestions only by default** (the ML layer is currently unreliable — it was confidently mis-labeling, e.g. plates/glue → BAKERY). `?includeMl=true` to override. Existing products aren't changed until we run it.

(No FE contract change — categories just get better. The ML model quality is a separate follow-up.)

---

## 2026-06-06 — categorization dry-run / debug endpoint

New: `GET /api/v1/categorizer/classify?description=Milho&description=Lays` returns, for each term, **how the cascade would categorize it** — without persisting anything. Use it to debug wrong categories.

Each result has the final decision (`category`, `genericName`, `brand`, `packSize`, `source`) **plus a per-layer breakdown**: `dictionary` (what the curated/learned dictionary matched) and `mlCategory`/`mlGenericName` (the ML guess + `confidence` + `meetsThreshold`). The `source` field (`DICTIONARY` / `LEARNED_DICTIONARY` / `ML` / `NONE`) tells you which layer decided — so a wrong category is immediately traceable to a bad dictionary entry vs an over-confident ML guess.

---

## 2026-06-06 — `/receipts` category filter is now multi-value

`GET /receipts?category=` (and admin `GET /admin/receipts?category=`) now accepts **multiple** categories — `?category=GROCERIES&category=CLEANING` returns receipts matching either. **Backward-compatible:** a single `?category=X` works exactly as before. This aligns it with `/items` and `/insights/query`, which already took category lists — so the FE can use one category-filter component across all three.

---

## 2026-06-06 — server-side caching + ETags on /dashboard and /insights

Performance for the two heaviest home-screen calls. **No contract change** — same endpoints, same response shapes. What changed is how fast/cheap they are:

- **Server cache:** `GET /dashboard` (2 min) and `GET /insights/spend` (5 min) are cached per household. **Invalidated immediately** when you confirm/reject/delete/reparse a receipt or add/edit an item — so post-action data is never stale despite the TTL. Keep your front-end TTLs; they now compound with the server's.
- **ETags / `304 Not Modified`:** both endpoints return an `ETag` header. Send it back as `If-None-Match` on the next call — if nothing changed you get **`304` with no body** (saves bandwidth + parsing on silent background refreshes). Standard HTTP; most fetch libers handle it, but for `fetch()` note a 304 won't carry a body, so keep your last good payload cached client-side and reuse it on 304.
- **Unread badge stays live:** the dashboard's `unreadNotificationCount` is *not* cached — it's always current, so reading notifications updates the badge without waiting for cache expiry.
- Caveat: the dashboard's `communityPromosNearby` reflects network-wide activity, so it can be up to 2 min stale (bounded by TTL). Everything driven by your own actions is instant.

---

## 2026-06-05 — dev server moved to self-hosted LAN box

The old Render URL (`https://economiz-ai.onrender.com`) is **dead** (free DB reaped).
The dev backend now runs on a self-hosted Windows machine on the LAN.

- **API base:** `http://192.168.68.108:8080/api/v1`
- **Health:** `http://192.168.68.108:8080/actuator/health` → `{"status":"UP"}`
- **Swagger:** `http://192.168.68.108:8080/swagger-ui/index.html`
- **You must be on the same Wi-Fi.** Off-LAN access isn't available yet (ask for a tunnel).
- **Browser-based FE (incl. Expo Web):** your dev-server origin must be CORS-allowed —
  send your laptop's LAN IP to get it whitelisted. Native Expo Go on a phone needs nothing.

---

## 2026-06-06 — items endpoint (filter purchased items, e.g. by category)

New top-level resource: `GET /api/v1/items` returns your **purchased line items** flattened across all receipts, filterable and paginated. This is what to call for "tap a category → show every item I bought in it" (and "all purchases of product X", "items at market Y", etc.) — one endpoint, filters via query params.

It's the **item-level companion** to the two endpoints you already have:
- `/receipts` → receipt-level rows
- `/insights/query` → aggregates/rollups
- `/items` → individual line items ← new

**Filters (all optional, same vocabulary as `/insights/query`):** `from`, `to`, `marketCnpj[]`, `marketCnpjRoot[]`, `category[]`, `productId[]`, `ean[]`, `minReceiptTotal`, `maxReceiptTotal`, plus `page`/`size`. Multi-value filters OR within a dimension, AND across dimensions.

**Returns** `Page<PurchasedItemResponse>` — each row has item facts (`itemId`, `productId`, `category`, `displayDescription`, `quantity`, `unitPrice`, `totalPrice`, `nfcePromoFlag`, …) **plus the receipt context inline** (`receiptId`, `marketName`, `marketCnpj`, `purchasedAt`), so no second fetch is needed to render a list.

**Scope:** CONFIRMED receipts only, excluded items dropped (real purchases). Default sort `purchasedAt` desc. Empty = empty page, not 404.

Category values for the FE filter chips: `GROCERIES, BEVERAGES, PRODUCE, MEAT_DAIRY, BAKERY, CLEANING, PERSONAL_CARE, OTHER`. No data-model change — items are still children of receipts; this is just a new read view. Full contract in API.md §4b.

## 2026-06-06 — price-drop alerts ("avise-me quando")

New feature: a user can ask to be notified when a product's price drops. The rule fires when **any household** in the network confirms a receipt that contributes an observation at or below the threshold — one person's receipt benefits another. This is the community retention loop.

**New endpoints — `/api/v1/alerts`:**
- `POST /alerts` → `201` with the created `PriceAlertResponse`. Body: `{ productId (UUID, required), thresholdPrice (BigDecimal, required), radiusKm (Double, optional), active (Boolean, optional — defaults true) }`. One rule per (user, product): re-posting the same product **updates** the existing rule (no duplicates, no 409).
- `GET /alerts` → `200` list of the caller's `PriceAlertResponse` (newest first).
- `DELETE /alerts/{id}` → `204`. `404 pricealert.not.found` if it isn't the caller's.

**`PriceAlertResponse` shape:** `{ id, productId, productName, thresholdPrice, radiusKm, active, lastFiredAt, createdAt }`.

**When a rule fires** it delivers a notification of the **new type `PRICE_DROP`** through the existing pipeline — it lands in `GET /notifications` (inbox) and is pushed if the user has a push token. Notification `payload` carries `{ alertId, productId, observedPrice, thresholdPrice, marketCnpj, marketName }`.

**Behavior worth knowing:**
- `radiusKm` is measured from the user's home (`homeLatitude/Longitude`). If set but either the home or the market has no coordinates, the rule **does not** fire (the geo constraint is honored, not ignored).
- A rule won't fire for the contributor's **own household** (you don't get pinged about your own receipt).
- **Cooldown:** a given rule fires at most once per 24h, so a flurry of cheap receipts won't spam the user.
- Only fires on receipts that actually contribute to the index (contributor opted in + master switch on). Opt-out receipts trigger nothing.

## 2026-05-08 — approximate-tax (IBPT) extraction on every NFC-e

Receipts now carry the IBPT-source approximate-tax disclosure that Brazilian merchants are required to print under Lei 12.741/2012. Surfaced so users can see the tax burden embedded in their groceries (Federal + Estadual taxes — ICMS, IPI, PIS, COFINS, IOF, …).

**`GET /receipts/{id}` — three new fields (all nullable)**:
- `approxTaxFederal` — federal portion (e.g. `15.13`)
- `approxTaxEstadual` — estadual portion (e.g. `13.73`)
- `approxTaxTotal` — sum, derived; `null` when both source fields are null

**`GET /receipts` and dashboard `recentReceipts` — one new field**:
- `approxTaxTotal` (BigDecimal, nullable) on each `ReceiptSummaryResponse`

**Important caveats** (please surface in the UX, not just the API):
- These are **estimates from the IBPT national table**, NOT taxes the consumer paid separately or what the merchant actually remitted. Label any number you show as `imposto aproximado` / `estimativa IBPT`.
- The line is legally mandatory but in practice not always present — small operators / Simples Nacional sometimes leave it blank or declare `R$ 0,00`. When the receipt's HTML doesn't carry the IBPT line, all three fields are `null`. **Aggregations should filter out `null`-tax receipts** before computing percentages, otherwise the average is diluted by missing data.
- Existing receipts in prod won't backfill — only newly ingested receipts after this deploy will have values populated. Old confirmed receipts stay null until reparsed.

---

## 2026-05-06 — multi-state recon documented (no behavior change)

Probed all 27 UFs' NFC-e portals empirically and saved the analysis under `docs/MULTI_STATE_RECON.md`. Bottom line: **end-to-end ingestion is verified for 1 UF (RS) today**; any other state still returns `UnsupportedStateException`. The doc maps each portal into tiers (simple GET / JSF stateful / XML / captcha / SPA / fetch issues) with effort estimates and a recommended implementation order.

The blocker is data, not code: writing adapters against synthetic chaves produces broken parsers because the response HTML for an invalid chave doesn't show the success-path layout. Real, recent chaves per UF unblock the work.

DEV_NOTES updated with the same status.

---

## 2026-05-05 — multi-state SEFAZ ingestion (no FE change)

`SefazAdapter.supportedState()` is now `supportedStates() : Set<UnidadeFederativa>` so a single adapter can claim multiple UFs. The existing RS adapter is renamed `SvrsSharedPortalAdapter` (the underlying portal hosts NFC-e for several states beyond RS) and now reads its UF list from config:

```
SEFAZ_SVRS_STATES=RS,SC,RJ,...
```

Default stays `RS`. To opt-in additional states without code: submit a real chave from that UF, verify the parser still extracts items, then add the UF to the env var. States with their own NFC-e portal (SP, MG, BA, PE, PR, GO, MT, MS, DF) still need a dedicated adapter — the SVRS URL won't serve their cupons.

Submitting a chave from a UF without any registered adapter still returns the same `UnsupportedStateException` as before.

---

## 2026-05-05 — admin: merge duplicate products

Catalog cleanup tool for cases the auto-dedup paths (alias / fuzzy / metadata) don't catch — the curator picks a survivor and absorbs another product into it.

### `GET /api/v1/admin/products/duplicates` (ROLE_ADMIN)
Returns groups of products that share an exact `(genericName, brand, packSize, packUnit)` profile. Each group is `{ genericName, brand, packSize, packUnit, category, products: [ProductResponse] }`. Within a group the oldest product comes first (sensible default survivor).

### `POST /api/v1/admin/products/{survivorId}/merge` (ROLE_ADMIN)
Body: `{ "absorbedId": "<uuid>", "dryRun": false }`. Migrates everything from `absorbed` into `survivor`:

- aliases, receipt items, price observations, manual purchases, shopping-list items → repointed.
- household aliases + consumption snoozes → conflict-aware (drops absorbed's row where survivor already has one for the household; UNIQUE (household_id, product_id) would otherwise fail).
- absorbed product deleted at the end.

Set `dryRun: true` to get the migration counts without applying. Returns `ProductMergeResultResponse` with per-table counts. **No undo** — the dry run is the only safety net.

---

## 2026-05-05 — admin: brand curation tools + bigger brand registry

### Brand registry expanded
`seed/brand-registry.csv` grew from 137 → 331 entries — covers the most common Brazilian grocery brands across rice/grains, pasta, dairy, chocolate, cookies, cleaning, margarine/oil, bakery, beverages, beer, wine, coffee, pet food, personal care, sweeteners, and ready meals. Existing products with `brand=null` are not auto-backfilled; new submissions match against the larger list automatically.

### `GET /api/v1/admin/products/missing-brand` (ROLE_ADMIN)
Paginated list of products without a brand. Response shape `MissingBrandProductResponse`: `{ id, ean, normalizedName, genericName, category, packSize, packUnit, sampleDescriptions: [string] }` — `sampleDescriptions` carries up to 5 raw descriptions from the product's aliases, giving the curator enough context to assign a brand without round-tripping.

### `PATCH /api/v1/admin/products/{id}/brand` (ROLE_ADMIN)
Lightweight PATCH — body `{ "brand": "Tio João" }`. Sets only the brand field. Returns the updated `ProductResponse`. Designed to be called from the missing-brand listing.

These two endpoints unblock manual catalog curation when brand extraction misses (see also: the metadata-dedup gate from this release, which becomes more effective as more products have brands).

---

## 2026-05-05 — metadata-based dedup for unknown EANs (no FE change)

When a previously-unseen EAN comes in, canonicalization now checks whether an existing product already matches by `(genericName, brand, packSize, packUnit)` before creating a new one. Catches the common case where small markets emit internal pseudo-EANs for the same physical product (mercadinhos, padarias, açougues do bairro) that would otherwise inflate the catalog.

Behavior:

- **Trigger**: item has an EAN, but the EAN isn't in the DB yet.
- **Match condition**: extracted `genericName`, `brand`, `packSize`, `packUnit` all non-null AND all four match an existing product. Any null → skip dedup, behave as before (create new product).
- **On match**: links the item to the existing product and persists the new description as an alias. The pseudo-EAN is intentionally **not** propagated onto the existing product — keeps `Product.ean` as a single canonical code.
- **Logged as**: `item.matched_by_metadata ean=<x> product=<id> brand=<y>`.

No FE-visible request/response change.

---

## 2026-05-05 — fuzzy alias matching (no FE change, better dedup)

Items without an EAN that previously fell through to `UNMATCHED` because their description was *almost* but not exactly equal to a known alias now get matched via Jaro-Winkler similarity. Concrete: `ARROZ TIO J 5KG` and `ARROZ TIO JOAO 5KG` are now recognized as the same product across markets.

Behavior:

- **Trigger**: only when the item has no EAN AND no exact alias hit.
- **Candidate pool**: aliases of products with the *same* extracted `(genericName, packSize, packUnit)` profile. Skipped if any of these is null — without that filter the search is too loose and false-positive-prone.
- **Threshold**: Jaro-Winkler ≥ 0.85.
- **On match**: links the item to the existing product and persists the new variant as an alias, so subsequent identical descriptions hit the cheap exact-alias path.
- **Logged as**: `item.matched_by_fuzzy product=<id> score=<n>`.

No request/response shape change. The `category`, `displayDescription`, etc. exposed on `ReceiptItemResponse` will populate more often as a result.

---

## 2026-05-05 — `DELETE /receipts/{id}` documented behavior (no FE change)

The endpoint already existed (still does, same path). Clarifying the LGPD invariant after a question came up:

- **Cascades**: receipt items + the audit-trail rows linking the household to its contributed observations.
- **Preserves**: anonymized `PriceObservation` rows themselves — once contributed, they stay in the community price index. This is enforced by the schema (FK `ON DELETE CASCADE` on audits, no FK from observations back to receipts).
- **Frees** the chave for re-import within the same household.
- 404 if the receipt belongs to another household.

No request/response shape change — just a contract guarantee written down so the FE knows what to expect.

---

## 2026-05-05 — push notifications go live via Expo Push Service

### `PushDispatcher` now talks to Expo
The dispatcher posts to `https://exp.host/--/api/v2/push/send`, the same path the Expo SDK uses internally. The FE (React Native + Expo) generates an `ExponentPushToken[...]` and registers it via `PUT /api/v1/users/me/push-token`; the backend POSTs that token plus the payload to Expo, which routes to FCM (Android) or APNs (iOS).

- **Why Expo and not firebase-admin direct**: Expo tokens are NOT raw FCM tokens — sending them through `FirebaseMessaging.send()` returns InvalidRegistration. The Expo HTTP API also avoids the firebase-admin SDK + service-account JSON setup entirely.
- **Auth (optional)**: setting `EXPO_ACCESS_TOKEN` env var raises rate limits and powers the Expo dashboard. Without it, sends still work for moderate volumes.
- **Token format**: tokens MUST start with `ExponentPushToken[` or `ExpoPushToken[` — anything else is rejected with `not an Expo push token` before any HTTP call.

### `POST /api/v1/admin/notifications/test` (ROLE_ADMIN)
On-demand test push — useful for verifying FE wiring without waiting for a natural trigger (promo, stockout, etc).

**Body:** `{ "email": "user@example.com", "title": "...", "body": "...", "type": "SYSTEM|PROMO_PERSONAL|PROMO_COMMUNITY|STOCKOUT" }`. `title`, `body`, `type` are optional and default to a canned system message.

Returns `202 Accepted`. Inspect the result via the inbox endpoint (`GET /api/v1/notifications`) on the target account, or look at the device.

---

## 2026-05-04 — receipt-level discounts now reflected in item prices

NFC-e item line totals don't always sum to the printed "Valor a pagar" — there can be a per-line or whole-bill discount that the parser was previously ignoring. From now on, when items don't sum to the receipt total (within R$ 0,05), the gap is **distributed proportionally across items** before persistence. So `unitPrice` and `totalPrice` on `ReceiptItemResponse` now reflect what the household actually paid, not the gross sticker prices. Knock-on effects:

- Per-product price history (`/insights/products/{id}/price-history`) is honest.
- The collaborative price index (`PriceObservation`) gets accurate per-unit numbers.
- Personal-promo detection compares apples to apples.

Existing receipts aren't backfilled. New submissions get the fix.

---

## 2026-05-04 — `category` exposed on receipt items

`ReceiptResponse.items[*]` now includes `category: string | null` — the `ProductCategory` of the linked Product (`GROCERIES` · `BEVERAGES` · `PRODUCE` · `MEAT_DAIRY` · `BAKERY` · `CLEANING` · `PERSONAL_CARE` · `OTHER`), or `null` when the item hasn't been canonicalized yet. Lets the FE render a category chip per item on `GET /receipts/{id}` without a per-item `GET /products/{id}` round-trip. Same pattern as `nfcePromoFlag` and `displayDescription`.

---

## 2026-05-04 — flexible insights query endpoint

### `GET /api/v1/insights/query` — one endpoint, any spend slice
Replaces the need to fan out across `/insights/spend` + `/insights/markets/top` + `/insights/categories/top` for cross-filtered views. Combine any subset of filters with a single `groupBy` dimension.

**Filters (all optional, list-typed where it makes sense):**
- `from`, `to` — date range (inclusive)
- `marketCnpj` — full CNPJs (repeat for multi-value: `?marketCnpj=A&marketCnpj=B`)
- `marketCnpjRoot` — chain-level (8-digit CNPJ root)
- `category` — `ProductCategory` values, list-typed
- `productId` — UUIDs, list-typed
- `ean` — EANs, list-typed
- `minReceiptTotal`, `maxReceiptTotal` — receipt-total range (BigDecimal)

**`groupBy`** (single dimension): `NONE` (default) | `DAY` | `WEEK` | `MONTH` | `YEAR` | `MARKET` | `CHAIN` | `CATEGORY` | `PRODUCT`. Temporal groupings sort ascending; non-temporal sort descending by total. `limit` caps bucket count (default 100, max 500).

**Response:** `{ filters, summary, groupBy, buckets }` — `summary` always present (total + receiptCount + itemCount + averageTicket); `buckets` populated when groupBy ≠ NONE. See API.md §4 for full shape and FE-friendly examples.

Backwards-compatible: existing `/insights/spend`, `/markets/top`, `/categories/top`, `/products/{id}/price-history` endpoints unchanged.

---

## 2026-05-03 (Tier 2 batch — admin endpoints, rate limiting, promo flag, unit normalization)

### Admin endpoints (ROLE_ADMIN only)
- **New: `GET /api/v1/admin/users?q=&page=&size=`** — paginated list of users with optional name/email substring search.
- **New: `GET /api/v1/admin/users/{id}`** — bundles user fields + household member count + receipt counts by status + 30-day spend total.
- **New: `GET /api/v1/admin/receipts?from=&to=&marketCnpj=&category=&q=&householdId=&page=&size=`** — cross-household receipt search. Same content-search semantics as the household-scoped `GET /receipts`. Includes `FAILED_PARSE` rows (useful for parser triage).
- **New: `GET /api/v1/admin/receipts/{id}`** — full receipt detail without the per-household ownership check.
- All four require a JWT for a user with `Role.ADMIN`. Regular users hit 403.

### Rate limiting (transparent — no API contract change)
- POST `/api/v1/auth/*` is now capped at **5 requests / minute / IP** (key = `X-Forwarded-For` first hop, falling back to `RemoteAddr`).
- POST `/api/v1/receipts` is now capped at **30 requests / hour / authenticated user**.
- Over-quota responses are `429 Too Many Requests` with a `Retry-After: <seconds>` header and a translated message body. Successful requests carry `X-RateLimit-Remaining`.

### Receipt items — NFC-e promo / discount flag
- `ReceiptItemResponse` now carries **`nfcePromoFlag: boolean`** — true when the SEFAZ HTML signaled the line was on promo (discount cell present, or description contains stems like "OFERTA", "PROMO", "DESCONTO", "COMBO", "LEVE 3").
- Backend behavior: promo-flagged items are **excluded from baseline calcs** in community-promo detection — comparing recent promos against historic promos was silencing real signals. Promo rows still count toward "current price".

### Price index — unit normalization
- New computed field on every `PriceObservation` written from now on: **`normalizedUnitPrice`** + **`normalizedUnit`** (one of `KG` / `L` / `UN`). Computed via the new `UnitConverter`, which translates Brazilian unit strings (`g`/`kg`/`mg`, `ml`/`L`/`lt`, `UN`/`CX`/`PCT`/`FD`) to a canonical base unit + multiplier.
- Community-promo detection now prefers `normalizedUnitPrice` when **all** observations in a (product, market) group have it. Mixed groups fall back to the raw unit price (current behavior). This fixes the false "huge price hike" we'd see when a market switched from 1L bottles to 2L bottles.
- Existing observations stay null until rewritten. No FE-visible field — purely an internal honesty fix for the panel queries.

---

## 2026-05-03 (Tier 1 batch — refresh tokens, search, reparse, manual brand, profile-pic polish)

### Auth — refresh tokens + logout (BREAKING for the auth response shape)
- **`AuthResponse` now carries `refreshToken`** alongside `token` on every register/login/refresh call. The FE must store both.
- **New: `POST /api/v1/auth/refresh`** `{ refreshToken }` → `{ token, refreshToken, user }`. Single-use rotation: every call returns a new pair, the old refresh token is consumed. Replay → `400 auth.token.invalid`.
- **New: `POST /api/v1/auth/logout`** `{ refreshToken }` → 204 (idempotent). Revokes the refresh token. Access token still valid until its 24h TTL expires — drop it client-side.
- Refresh token TTL: **30 days** (configurable via `JWT_REFRESH_EXPIRATION` env). Access token TTL unchanged at 24h.

### Receipts — content search
- **`GET /api/v1/receipts?q=leite condensado`** now does case-insensitive substring match against item raw description, friendly description, the linked product's normalized name, AND the receipt's market name. Combine freely with the existing date/category/marketCnpj filters.

### Preferences — manual brand override
- **New: `PUT /api/v1/preferences/brand/{genericName}`** `{ brand, strength }` → 204. Override the auto-derived dominant brand with your own choice. Strength is `PREFERRED` or `MUST_HAVE`.
- **New: `DELETE /api/v1/preferences/brand/{genericName}`** → 204. Clears the override.
- Override **wins over derived** in `GET /preferences`. The row keeps the historical `brandDistribution`, `sampleSize`, and pack-size fields (so the user still sees the signal) but `topBrand` + `brandStrength` come from the override.
- A manual override can also surface a generic the household hasn't bought yet (sampleSize=0).

### Profile picture — resize on upload + initials fallback
- **GET `/api/v1/users/me/profile-picture` never 404s anymore.** When no picture is uploaded, the server returns a deterministic 256x256 PNG with the user's initials on a color hashed from their email. Inspect the `X-Profile-Picture-Fallback: true|false` header to distinguish a generated avatar from an uploaded photo.
- **On upload**: JPEG/PNG are server-side downscaled to a 512px max dimension before storage. WebP is stored as-is. No FE work needed.

### Admin — reparse endpoint
- **New: `POST /api/v1/admin/receipts/{id}/reparse`** (ROLE_ADMIN only) — re-runs parsing on the stored raw HTML and resets the receipt to `PENDING_CONFIRMATION`. Used when a parser fix lands and we want to reprocess old receipts without forcing users to re-scan. Owner re-confirms to commit.

---

## 2026-05-02 (gap-closing batch)

### `GET /api/v1/dashboard` — bundled app-open snapshot
- One round-trip returns: current-month spend snapshot (total + count + ticket médio), last 5 confirmed receipts, top 5 suggested-list items, top 5 community promos in your area (watched markets bypass radius), unread notification count.
- Each section silently degrades to empty/zero — no errors. Use this on the home screen instead of fan-out calls.

### Notifications inbox endpoints
- **New: `GET /api/v1/notifications`** — paginated, newest first.
- **New: `GET /api/v1/notifications/unread-count`** — `{ "unread": N }` for the bell badge.
- **New: `POST /api/v1/notifications/{id}/read`** — mark single as read.
- **New: `POST /api/v1/notifications/mark-all-read`** — `{ "marked": N }`.
- `NotificationResponse` includes `payload` (the same JSON we passed at dispatch time) so cards can deep-link to the related receipt/product.

### Add missing items to a receipt before confirming
- **New: `POST /api/v1/receipts/{id}/items`** — for cases when SVRS missed a line. Only works on `PENDING_CONFIRMATION` receipts. Auto-assigns next position. Same body shape as the PATCH (minus the immutable rawDescription edits).

### `/actuator/health` is now public
- Spring Boot Actuator wired in; only `/actuator/health` is exposed publicly. Returns `200 {"status":"UP"}`. The keep-alive cron now hits this instead of `/legal/terms`. Use it for any uptime monitoring you wire externally.

### Password reset + email verification
- **New: `POST /api/v1/auth/forgot-password`** `{ email }` → 204. Always 204 even when email isn't registered (no enumeration leak).
- **New: `POST /api/v1/auth/reset-password`** `{ token, newPassword }` → 204. Token from the link, single-use, expires 60 min.
- **New: `POST /api/v1/auth/verify-email`** `{ token }` → 204. Token sent automatically on register, expires 24h.
- **New: `POST /api/v1/users/me/email-verification/resend`** — re-sends a fresh verification token if the old one expired.
- `User` now has `emailVerified` / `emailVerifiedAt` fields (visible via `GET /users/me` once you re-pull).
- **Dev shortcut:** until SMTP is wired in Render, the link is **logged with `[DEV-MODE]` prefix** instead of emailed. Grep server logs for the token. Documented in `DEV_NOTES.md`.

### Persistent shopping lists
- **New: `GET /api/v1/shopping-lists`** — household's lists (newest first).
- **New: `POST /api/v1/shopping-lists`** `{ name, items?: [{productId? | freeText?, quantity?}] }` — create.
- **New: `GET /api/v1/shopping-lists/{id}`** — detail with items.
- **New: `PATCH /api/v1/shopping-lists/{id}`** `{ name }` — rename.
- **New: `DELETE /api/v1/shopping-lists/{id}`** — delete (cascades items).
- **New: `POST /api/v1/shopping-lists/{id}/items`** — add item.
- **New: `POST /api/v1/shopping-lists/{id}/items/{itemId}/toggle`** — toggle checked.
- **New: `DELETE /api/v1/shopping-lists/{id}/items/{itemId}`** — remove item.
- Items can reference a canonical Product (auto-suggestions, optimizer-friendly) OR be free text (e.g. "papel higiênico" before we have a canonical Product for it).
- The existing stateless `POST /api/v1/shopping-list/optimize` (singular) stays for ad-hoc one-shot optimization.

---

## 2026-05-02

### Profile pictures
- **New: `POST /api/v1/users/me/profile-picture`** — multipart, field name `file`. JPG/PNG/WEBP, max 5 MB. Returns `{ "status": "ok" }`.
- **New: `GET /api/v1/users/me/profile-picture`** — returns raw image bytes (Content-Type matches the upload). 404 if not set.
- **New: `DELETE /api/v1/users/me/profile-picture`** — clears it.
- Storage is local-disk in dev (ephemeral on Render free tier — see `DEV_NOTES.md` for the prod plan). Contract won't change when we swap backends.

### `friendlyDescription` — rename items for display, with household memory
- **PATCH `/receipts/{id}/items/{itemId}`** now accepts `friendlyDescription` (max 500 chars). Set to override the noisy NFC-e text for display. `rawDescription` stays untouched (audit trail).
- **`PATCH …/items/{itemId}` no longer mutates `rawDescription`** even if you send it (kept in the request shape for backwards compat, marked deprecated in Swagger). To rename, use `friendlyDescription`.
- **`ReceiptItemResponse`** now has 3 description fields:
  - `rawDescription` — original NFC-e text, immutable
  - `friendlyDescription` — user override, null when not set
  - `displayDescription` — pre-resolved (`friendlyDescription` if set, else `rawDescription`). Use this for rendering.
- **Household memory**: when the user names an item linked to a Product, the name is remembered household-wide. Future receipts of the same Product (matched by EAN or alias) inherit `friendlyDescription` automatically — user types it once per product per household.

### Per-item exclusion on receipt confirm
- **POST `/receipts/{id}/confirm`** now accepts an optional body: `{ "excludedItemIds": ["uuid", ...] }`. Items in the list get marked excluded *before* downstream processing.
- **PATCH `/receipts/{id}/items/{itemId}`** accepts an `excluded: boolean` field too (toggle while reviewing).
- Excluded items stay on the receipt for audit but **don't count toward** spend, category insights, weekly insights, consumption predictions, price-history, or the collaborative price index.
- `ReceiptResponse` now exposes both `totalAmount` (original NF, immutable) and `householdTotalAmount` (sum of non-excluded items). Use `householdTotalAmount` for "what we actually spent".
- `ReceiptItemResponse.excluded` is the per-item flag.

### Per-household chave uniqueness + delete-receipt
- The `chave de acesso` was globally unique — only one user/household could ever import a given QR. Now **per-household**: two different households can both import the same fiscal event (couple split a bill, or QA testing).
- **New: `DELETE /api/v1/receipts/{id}`** — hard delete (any status, scoped to your household). Frees the chave for re-import.
- Error message updated: 409 now says "already in your household history" (was "already imported").

### Receipts list hides FAILED_PARSE rows
- When the SEFAZ HTML can't be parsed, we still persist the receipt with `status=FAILED_PARSE` + `rawHtml` for ops review. **Those rows are now hidden from `GET /receipts`** so they don't pollute the user's history list. The user still gets the 400 error on submit.

### `friendlyDescription` deprecates editable `rawDescription` on PATCH
- Already covered above — calling out separately because it's a small backwards-incompatible change. Sending `rawDescription` to PATCH is now a silent no-op (was: overwrote the field).

---

## 2026-05-02 (earlier — FE alignment batch)

### Insights enhancements
- **`GET /insights/spend`** response now includes `byWeek` array (besides existing `byMonth`, `byMarket`, `byCategory`).
- **`GET /insights/products/{id}/price-history`** points now carry `marketCnpj` per point (besides `marketName`) — needed to differentiate two stores of the same chain (e.g. Zaffari Hipica vs Zaffari Centro).
- All insights aggregations switched from `Receipt.totalAmount` to `SUM(item.totalPrice WHERE NOT excluded)` so they reflect what the household actually paid for, not the bill total.

### Households: kick member + invite expiration
- **New: `POST /api/v1/households/me/invite-code/regenerate`** — rotates the invite code, extends 48h TTL.
- **New: `DELETE /api/v1/households/me/members/{memberId}`** — kicks a member; they land in a fresh solo household.
- Invite codes now expire 48 h after generation (existing rows = NULL = never expires, backwards compat).
- `HouseholdResponse` exposes `inviteCodeExpiresAt`.

### Consumption (Phase 3) — snooze + manual purchase + upcoming + qty-aware
- Lowered min-purchases-for-prediction from 3 → 2.
- Quantity-aware ETA: if the last purchase was markedly larger than usual, the next-purchase ETA scales proportionally.
- **New: `POST /api/v1/consumption/snooze/{productId}`** with `{ "days": N }` — "Não preciso agora".
- **New: `DELETE /api/v1/consumption/snooze/{productId}`** — clear snooze.
- **New: `POST /api/v1/consumption/manual-purchase`** with `{ "productId", "quantity", "purchasedAt"? }` — "Já comprei sem nota". Counts toward intervals.
- **`GET /api/v1/consumption/suggested-list`** now accepts `?includeUpcoming=true&upcomingLimit=N` — empty state can show "Você está bem abastecido — próximos a vencer:".

### Shopping list optimizer
- **New: `POST /api/v1/shopping-list/optimize`** with `{ "items": [{productId, quantity}] }` → returns `{ marketPlans, estimatedTotal, unpriced }`. Greedy picks cheapest known market per item, groups by market.
- Each plan item carries `priceSource: LOCAL_HISTORY | COMMUNITY_INDEX`.
- Items with no price data land in `unpriced` (FE shows "preço indisponível" badge).

### Collaborative panel: city/state + k-anon hybrid disclosure
- Markets now record `city` + `state` (auto-filled from Nominatim geocoding).
- `PriceObservation` snapshots city/state at write time so retroactive geocode changes don't rewrite history.
- **`GET /price-index/.../reference`** now returns `kAnonBlocked: boolean`. When `true`, `medianPrice` is null but `sampleCount` and `distinctHouseholds` are still visible — FE can show the "poucas amostras" warning without us leaking sub-K-anon prices.

### Phase 2.6 — auto-derived household preferences
- **New: `GET /api/v1/preferences`** returns per-generic pack-size + brand preferences derived from the household's purchase history. Volume-gated (silent until 5+ purchases of a generic). Brand strength: `PREFERRED` (60–85% share) or `MUST_HAVE` (≥85%).

### Phase 5c — watched markets
- **New: `GET /api/v1/markets`** — picker catalogue (visited + watched + nearby).
- **New: `GET /api/v1/markets/watched`** — "Meus mercados".
- **New: `POST/DELETE /api/v1/markets/watched/{cnpj}`** — pin/unpin.
- Watched markets bypass the radius filter in `/price-index/best-markets` and `/price-index/promos`. Each row carries `watching: boolean`.

### Cross-cutting privacy + perf fixes
- All log lines now mask PII: emails (`a***@example.com`), chaves (`****6780`), push tokens (`****abcd`).
- Fixed an N+1 query in personal-promo detection.

---

## 2026-05-01 — earlier in the build-out

- **Phase 3 consumption intelligence (initial)** — predictions + suggested-list endpoints (later expanded above).
- **Phase 4 collaborative price index** — anonymized contributions, k-anon-protected reference price + best-markets + community promos endpoints.
- **Phase 5 geolocation + notifications** — `PATCH /users/me/location`, market geocoding via Nominatim, FCM push stub, SMTP email dispatcher, per-user notification preferences.
- **LGPD baseline** — opt-out flag, data export endpoint, account-deletion endpoint.

---

## How to use this file

- When you start a session, scroll from the top until you hit dates you've already read.
- Each entry is meant to be self-contained: what changed, what's new on the wire, what FE behavior should change.
- Major DTO/contract changes get explicit before/after notes.
- Bug fixes are listed only when the FE was likely tripping on the bug. Internal refactors aren't logged.
