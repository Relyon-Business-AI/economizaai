# Economiza AI — Monetization Strategy

Monetization is treated as a **Day-1 architectural concern**, not a Phase-N
afterthought. The entities, anonymization boundaries, and feature gates listed
below should appear in the data model from the first migration that touches
their area, even if the actual paywall is unenforced for months.

---

## Revisão 2026-09-25 — pós-IA na categorização + "cobrar só com volume"

> Esta estratégia foi escrita **antes** de a IA entrar na categorização e assume cobrança só
> **depois de ter volume/PMF**. Duas mudanças de contexto reposicionam os muros abaixo — o resto
> segue válido.

**1. Categorização por IA entrou (aposentou o Naive Bayes).**
- **Categorização virou núcleo GRÁTIS** — todo usuário ganha categorias melhores. Isso reforça o
  **magic moment** (hook grátis) *e* a **qualidade do painel** (dado melhor categorizado = índice
  B2B mais valioso). Não é paywall; é base.
- **Novo COGS por scan:** a chamada de LLM (extração/categorização) tem custo por nota — soma ao
  fetch/captcha. Precisa ser **metrada pelo `PaidApiGuardService`** (mesmo kill-switch/orçamento
  diário), senão o custo de IA escala silencioso com o uso grátis.
- **Alavanca premium NOVA:** o pago não é *categorizar*, é **IA aprofundada** — insights em
  linguagem natural, análise de cesta, previsões melhores. Categoria básica grátis; "a IA trabalha
  pra você" no PRO.

**2. Só cobrar depois de ter volume — muros ficam DORMENTES no warm-up.**
- Ainda não há provedor de pagamento (⬜), então limitar o free agora só estrangula crescimento
  **sem receita nenhuma**. **Ação:** manter os limites free **largos/ilimitados via config**
  (`economizaai.subscription.free.*`) até um gatilho de volume/PMF. Os números do matrix abaixo são
  o **alvo pós-PMF**, não a config de hoje.
- **Os dois muros mais arriscados de ligar cedo:** (a) **volume de scan** — é o ativo que constrói
  o painel; estrangular = matar o moat; (b) **push/notificações** — é o loop de retenção; gatear =
  churn mais rápido = painel menor. Manter generosos até o PMF (free deveria ter **ao menos push
  dos mercados/itens fixados**).
- **Ordem certa:** grátis e colante primeiro → medir retenção e notas/semana → só então ligar
  muros, um a um, com **A/B**. Isso depende do **feature-flag service (⬜)** — é **pré-requisito**
  da própria metodologia "cada gate é um A/B", não um nice-to-have.

**2a. Provider web escolhido: Mercado Pago (decisão 2026-09-29).** Checkout de assinatura
mensal via preapproval (`POST /subscriptions/checkout` → `checkoutUrl`; webhook
`/webhooks/mercadopago` HMAC fail-closed vira o tier). Código pronto e INERTE até
`MP_ACCESS_TOKEN`/`MP_WEBHOOK_SECRET`. Apps seguem RevenueCat. Falta (owner): contas
MP/RevenueCat + produtos nas lojas; (dev): paywall no FE (tela + `react-native-purchases`).

**2b. Promo de lançamento em DATA FIXA (decisão 2026-09-29):** o Premium promocional deixou
de ser rolling ("3 meses do cadastro") e passou a valer **até 31/12/2026 pra base inteira**
(`SUBSCRIPTION_PROMO_UNTIL`, migration V85 estendeu os grants existentes). Racional: sem
checkout, expirações contínuas por usuário = downgrade sem botão de upgrade (pior choque
possível); data única = um evento de "pricing launch" orquestrável (anúncio 30d antes,
preço de fundador pra base atual, campanha). O Q4/2026 é a janela pra integrar o provedor
de pagamento e medir retenção antes da virada. **Desde então o grant deixou de acontecer no
cadastro:** conta nova ganha o PRO promocional ao confirmar a **primeira nota**
(`ReceiptService.doConfirm` → `grantSignupPromoIfEnabled`). **Regras fechadas em 2026-11-11:**
concessão **uma única vez** por usuário (apagar notas e confirmar de novo não repete); **nunca
sobrescreve** assinatura paga ativa, concessão sem data de fim nem período que já termina depois
da promo; quem tem só a promo recebe aviso (caixa + push, 10h) em **D-15, D-7, D-3 e D-1** com
convite para assinar; e uma assinatura paga que acaba com a promo ainda vigente **volta para a
promo** em vez de cair para o gratuito.

**3. Acesso antecipado como valor contínuo do PRO (novo perk):** PRO recebe features primeiro
(exclusividade temporária), entregue pela mesma infra de flags. Reduz churn sem capar o free.

O resto (freemium por profundidade, B2B como moat, affiliate, sponsored, family/business
usage-based) **segue válido** — a IA reforça o B2B e não remove nenhum modelo.

---

## Revenue Models (by priority)

### 1. Freemium — economizai Pro (R$9.90/month, R$89/year)

**Pricing rationale.** Anchor against streaming (R$25–R$45/mo) and Nubank Ultravioleta
(R$24/mo) — clearly less than one streaming service. Annual pricing offers ~25%
discount to reduce churn. The "I save R$X/mo on groceries" pitch needs the price
small enough that the comparison is unflattering — R$9.90 is "the difference is
one box of milk".

#### Built feature → tier matrix (as of 2026-05-01)

This maps what's *already shipped* to where it should live. Anything in the
"Future" column is on the roadmap but not built yet; columns are aspirational.

| Capability | FREE | PRO | Notes |
|---|---|---|---|
| Submit NFC-e receipt | ✅ (5/month) | ✅ unlimited | Counter middleware, easy gate. Heavy users hit 5 in a week. |
| Receipt history | last **90 days** | unlimited | Query-layer gate on `issuedAt`. Cheap to implement, painful to discover (user opens app, sees their old data is gone). |
| Spend dashboard `/insights/spend` | current month only | full range | Same gate as history. |
| Top markets / categories | top 3 | top 10+ | Hard cap on `?limit` for FREE. |
| Price history per product | current month | full | Same gate. |
| Reference price (community) | ✅ | ✅ | Collaborative read — keep open to drive panel value perception. |
| Best markets ranking | top 3 nearby | top 10 + watchlist + radius filter | Watchlist is FREE for first 3 markets, unlimited for PRO. |
| Watched markets | up to **3** pinned | unlimited | Concrete, easy gate, real upgrade pressure for power users. |
| Community promos (`/price-index/promos`) | last 7 days | full lookback + push | Same data, different freshness window. |
| Personal promo detection | ✅ on confirm | ✅ on confirm | Free — it's the magic-moment hook on every receipt. |
| Personal promo notifications | in-app only | push + email | The "alert me when my common stuff drops" loop. |
| Consumption predictions (`/consumption/predictions`) | view-only | view + push when "RUNNING_LOW" | Predictions are visible, the *push* is paid. |
| Suggested shopping list | view-only | view + basket optimization across markets | Optimization is the hard PRO sell — turns a list into "go to market A for these, B for those, save R$Y". |
| Auto-derived preferences (`/preferences`) | view-only | view + manual override (AVOID/MUST_HAVE) | Auto-derivation for free, manual control paid. AVOID specifically can't be auto-derived → natural gate. |
| Household sharing | up to **2 users** | up to 6 (Family plan, see §5) | Need a member-count gate at household join. |
| Notification preferences | per-type channel choice | + custom alert rules per product | "Alert me when leite Italac < R$6 anywhere within 5km" — power feature. |
| Data export (LGPD) | ✅ | ✅ | LGPD-mandated, never gated. |
| Account deletion | ✅ | ✅ | LGPD-mandated. |
| Ads on dashboards | shown | hidden | See §4. |
| Personal inflation index | — | ✅ | Compute basket-level IPCA equivalent. Real-time, beats government numbers. Differentiator. |
| Recipe-based shopping | — | ✅ | Input recipes, get optimized list per recipe. Future feature. |
| CSV/Parquet export of own data | — | ✅ | Power users + tax / accounting use case. |
| Categorização por IA | ✅ (núcleo) | ✅ | Base grátis desde que aposentou o Naive Bayes — melhora hook + qualidade do painel. Custo LLM/scan → metrar no guard. Não é paywall. |
| Insights de IA (linguagem natural, análise de cesta) | — | ✅ | A alavanca premium da IA: categorizar é grátis, "a IA analisa pra você" é PRO. Future. |
| Acesso antecipado a novas features | — | ✅ | PRO recebe primeiro (exclusividade temporária via feature flags). Valor contínuo → reduz churn. |

**The PRO pitch in one sentence:** "Pay R$9.90/mo to get unlimited history,
push alerts when your usual stuff is on sale, automatic shopping lists optimized
across markets, and basket-level inflation tracking."

**Why these gates work for grocery shoppers specifically:**
- A weekly grocery shopper hits the 5-receipt cap in week 1 → forced to choose.
- A new user signs up, scans a receipt, sees "you usually pay R$28 for this, you
  paid R$22 today — save R$6/mo on average if you keep coming here" → magic
  moment. That stays free; everything that compounds it is paid.
- The "alert me when X drops" feature is exactly the loop that gets users to
  reopen the app — and it's also the most friction-y to wire (FCM, email, rules
  engine), so paywalling it has good unit economics.

#### Implementation status
- ✅ `User.subscriptionTier` (FREE/PRO) — on entity **and now enforced**.
- ✅ Single `SubscriptionGateService` (`service/subscription`) — `allows`/`require`
  + typed limit helpers (`watchedMarketLimit`, `monthlyReceiptLimit`,
  `freeHistoryWindowDays`, `clampFrom`). No inline tier checks anywhere.
  Limits tunable via `economizaai.subscription.free.{watched-markets,history-days,monthly-receipts}`.
- ✅ `PaywallException` → **HTTP 402** (`subscription.upgrade_required`).
- ✅ Receipt upload counter — `ReceiptService.submit`, counts all statuses this calendar month.
- ✅ Query-layer date-range cap — `clampFrom` applied in `ItemQueryService`,
  `InsightsService`, `InsightsQueryService`. PRO bypasses.
- ✅ Watched-markets gate — `WatchedMarketService.watch` checks count on NEW pins.
- ✅ Delivery gate — `NotificationService.notify` persists the inbox row for all,
  dispatches push/email only for PRO (`PUSH_AND_EMAIL_DELIVERY`) — except transactional
  notifications (receipt ready/failed/recovered, household membership, organic system notices),
  which always go out (`SubscriptionGateService.allowsDelivery`).
- ✅ Basket optimization gate — `ShoppingListOptimizer.optimize` requires PRO.
- ✅ History export gate — `ReceiptExportService` requires `CSV_EXPORT`.
- ✅ Photo-receipt gate — `PhotoReceiptExtractionService` requires `PHOTO_EXTRACTION` (and counts toward the monthly receipt cap).
- ✅ Household member-count gate — `SubscriptionGateService.householdMemberCap`: FREE ≤ 2
  (`free.household-members`), any PRO member lifts the household to `household-members-max` (6, applies even with enforcement off).
- ⚠️ **Master switch:** all gates above are dormant while `economizaai.subscription.enforce=false`
  (`SUBSCRIPTION_ENFORCE`, default off) — with it off every user behaves as PRO.
- ✅ `Subscription` entity + `SubscriptionService.activatePro/cancel` (tier kept in sync).
- ✅ Admin set-tier — `PUT /api/v1/admin/users/{id}/subscription-tier`.
- ✅ Subscription lifecycle (2026-11): a provider cancellation / scheduled pause only marks the
  subscription non-renewing — PRO is kept until the paid period ends; the hourly expiry sweep
  downgrades (3-day grace for a paid, still-renewing subscription — `SUBSCRIPTION_PAID_GRACE_DAYS`;
  none for the promo). A paid subscription that ends while the launch promo is still running
  falls back to the promo. Mercado Pago monthly charges (`subscription_authorized_payment`)
  advance the period and record one revenue event per payment (deduped on the payment id), so
  every renewed month counts as revenue, not only the first. RevenueCat `EXPIRATION` ends the
  subscription (back to the promo when still running); `CANCELLATION` / `SUBSCRIPTION_PAUSED`
  only stop the renewal. `GET /users/me/subscription` exposes `cancelAtPeriodEnd` and
  `rewardPremiumUntil`. Deleting an account with an active paid subscription does not cancel it
  at the store / provider — the admin gets an e-mail so support can reach out.
- ✅ Launch promo granted ONCE per user (first confirmed receipt), never over a better
  entitlement; the app is told in the confirm response (`launchPromo.validUntil`). Promo holders
  without a paid subscription are warned at D-15 / D-7 / D-3 / D-1 (`LaunchPromoWarningService`).
- ✅ Per-feature gate: `economizaai.subscription.free.open-features` leaves chosen PRO features
  open to FREE (switch paywalls on one at a time); transactional notifications are never held
  back by the delivery gate; household-split receipt copies don't count toward the monthly cap.
- ✅ Referral program ("indique um amigo") — see "Referral program" below.
- ❌ Provider-agnostic webhook `POST /api/v1/webhooks/subscription` — REMOVED (2026-11, unused).
- 🟡 Payment provider — **chosen and wired, INERT until credentialed** (see "2a" above): web =
  Mercado Pago (`GET /subscriptions/plan`, `POST /subscriptions/checkout`,
  `POST /webhooks/mercadopago`; inert until `MP_ACCESS_TOKEN`/`MP_WEBHOOK_SECRET`); apps =
  RevenueCat (`POST /webhooks/revenuecat`, fail-closed without the auth header; FE
  `src/services/iap.ts` + `PremiumScreen`).
- 🟡 Self-serve subscription: read side shipped (`GET /users/me/subscription`); there is no
  `PUT /users/me/subscription` / in-app cancel endpoint.
- ⬜ Feature-flag service so we can A/B individual gates.
- ⬜ Top markets/categories `?limit` hard cap for FREE (history window is gated; the limit cap is not yet).
- ⬜ Manual preference override (AVOID/MUST_HAVE) — depends on Phase 2.6.

#### Referral program (owner decision 2026-11-11)

- Each user has a personal code / link (`GET /referrals/me`). A NEW account is attached to the
  inviter at signup (`referralCode`) or via `POST /referrals/redeem` while it has no confirmed
  receipt. One referrer per account, ever.
- A referral **qualifies** when the invited person confirms a receipt that contributed to the
  collaborative index (photo notes, unverified XML, a chave another household already scanned and
  opted-out users don't count) while living in a different household than the inviter.
- The inviter's **first** qualified referral earns **1 month of Premium — once**. From then on,
  **1 month per invited person who subscribes** to paid Premium. The invited person earns nothing.
- Months **accumulate**: each one queues after the inviter's current entitlement (during the
  launch promo, a month earned now runs 01/01 → 31/01; a second one 01/02 → 28/02). Reward time
  lives in `premium_grants` (its own entitlement) and never replaces or shortens a subscription.
- **`premium_grants`** (V170): one row per earned month — `kind` `REFERRAL_FIRST` (the one-time
  first-referral reward) or `REFERRAL_SUBSCRIPTION` (an invited friend subscribed), with its own
  start/end. The user is PRO while a subscription is ACTIVE **or** a grant window is running; the
  hourly expiry sweep flips the tier when a window starts or ends. An invited friend yields at
  most one month to the inviter. "Subscribed" means money changed hands: a RevenueCat purchase /
  renewal with a price above zero, or an approved Mercado Pago charge — a free trial or the
  launch promo does not count.
- Abuse guards: two accounts cannot refer each other; inviter and invited must live in different
  households when the reward is decided; redeeming a code is rate-limited (10/hour).

#### Gating sequence (which paywalls to ship first)
1. ✅ **Watched markets cap** (3 free) — shipped.
2. ✅ **History window** (90 days free) — shipped.
3. ✅ **Push notifications** (PRO only) — delivery gate shipped (inbox stays free).
4. ✅ **Basket optimization** (PRO only) — shipped.
5. ⬜ **Manual preference override** (PRO only) — depends on Phase 2.6 completion.

Each gate is an A/B; measure conversion before leaning harder on the next.

> **Postura de warm-up (revisão 2026-09-25):** os gates estão CONSTRUÍDOS mas devem ficar
> **dormentes** (limites free largos via `economizaai.subscription.free.*`) até haver volume/PMF —
> cobrar antes só estrangula crescimento sem receita (não há provedor de pagamento ainda). Ligar
> um a um, com A/B, depois do PMF — o que exige o **feature-flag service (⬜)**.

---

### 2. B2B Anonymized Price Intelligence (the moat — R$5K–R$100K/year)

This is the bigger lever long-term. We're sitting on real-world FMCG transaction
data — what Nielsen/IBOPE/NielsenIQ sell to brands and retailers for serious
money. The collaborative anonymized index is the product.

**Data products:**
- **Brand share of shelf** — for a given category in a given region, how
  spending is distributed across brands (week-over-week trends).
- **Promo effectiveness** — when retailer X dropped product Y's price by Z%,
  how did volume shift across competing markets in the same region?
- **Regional price dispersion** — same product, same week, price spread across
  markets and neighborhoods. Real arbitrage signal.
- **Category basket trends** — what's growing, what's declining, by region.
- **Cross-retailer comparison panels** — anonymized but representative
  baskets compared across retailer brands.

**Buyers:**
- FMCG manufacturers (Unilever, Nestlé, Ambev, Coca-Cola, BRF) — pricing intel and competitive benchmarking.
- Retail chains (regional markets, Carrefour, Assaí, Atacadão) — price gap analysis vs. competitors.
- Market research firms (NielsenIQ, Kantar, Euromonitor) — supplementary panels.
- Universities and economists — academic licensing, possibly free-tier.
- News media — periodic "cesta básica" reporting (low-revenue, high-marketing).

**Revenue model:**
- Annual data subscription: R$5K (regional starter) → R$100K+ (national, multi-category).
- Custom one-off reports: R$2K–R$20K.
- API access for live dashboards: tiered.

**Implementation needed:**
- Anonymization pipeline with **k-anonymity** invariants (already on Phase 4 roadmap — make it production-grade).
- Aggregation tables (PricePoint by week × market × product × region).
- B2B partner portal (separate auth realm, signed contracts).
- Export API (CSV/Parquet) with audit log.
- LGPD compliance documentation pack — non-negotiable.
- Sales materials and a "free sample" report generator.

**Why this is strategic:** The personal product builds the panel for free
(every user wants their dashboard). The B2B side monetizes the panel without
extracting more from users. This is the "Robinhood with payment for order flow"
shape — the consumer side is free or cheap, the data is the actual product.
Just be transparent about it from Day 1 (anonymization, opt-out, user-visible
explainer).

---

### 3. Affiliate Links to Retailer Apps & Promos (R$0.10–R$5 per click/install)

When the system says "buy product X at market Y", and Y has a cashback app or
loyalty program (Méliuz, Picpay, market-native apps), surface a deep link.

**Revenue:**
- Affiliate commission per app install (R$3–R$10).
- Per-click commission on cashback platforms (R$0.10–R$1).
- Per-conversion commission on partnered retailers.

**Implementation needed:**
- Affiliate link table per (market, product?, retailer-app).
- Deep-link generator with affiliate IDs.
- Click-tracking middleware.
- Disclosure: "Patrocinado" badge.

> **Foundation shipped (2026-09-22, INERT):** the e-commerce price-comparison subsystem
> ("vale a pena online?") — pluggable providers (Mercado Livre), admin-curated offers, the
> `/receipt-items/{id}/offer` endpoint, and an opt-in discount-hunter leaderboard. This is
> the concrete first step of the affiliate/commerce model above. Full design, current state,
> and next steps (incl. the "máquina de economizar" route optimizer) in
> **[`docs/ECOMMERCE_COMPARISON.md`](docs/ECOMMERCE_COMPARISON.md)**. No FE yet by choice.

---

### 4. Sponsored Placements (with disclosure)

A market or brand can pay to be highlighted in shopping-list suggestions or
price comparisons.

**Ad formats:**
- "Recommended for this list" sponsored card.
- Highlighted product card in price comparison ("Patrocinado pelo fabricante").
- Banner in dashboard (free users only).

**Constraint:** Must never compromise the integrity of the price index. A
sponsored placement can surface a market in the UI but must never alter the
ranking the user sees as "cheapest" — that ranking stays purely data-driven.
This trust is the entire product.

**Revenue:** CPM or CPC, R$1–R$5 per click for free users.

**Implementation needed:**
- Sponsorship slot components and explicit data-rank vs. promoted-rank separation.
- Disclosure UI ("Patrocinado") — never optional.
- Pro users skip all sponsored placements.

#### 4b. Merchant Marketing Accounts — self-serve (decisão 2026-10-06)

The self-serve evolution of §4: the market itself holds an account (new role
`MERCHANT`) and publishes its own promotions through a merchant portal, paying a
**flat monthly fee per store** (ballpark R$99–R$299/month — validate). No
CPC/auction ad-tech early; flat and simple.

**The pitch to the market is NOT reach — it's verification + attribution,**
which only we have:
- **Verified promos**: the market announces "leite R$4,99" and real scanned
  NFC-e receipts confirm it — "preço verificado" badge. No other channel in
  Brazil can do this.
- **Closed-loop attribution**: promo shown → user buys → scanned receipt proves
  the conversion. "Your promo generated X verified purchases" justifies a
  subscription in a way banners can't.
- Same infra feeds the B2B "promo effectiveness" product (§2) — one build, two
  customers.

**Sequencing (chicken-and-egg: markets only pay with user density):**
1. **SHIPPED (free)**: claimable **Merchant Profile** — self-serve claim (code to
   the company e-mail from the Receita, admin-queue fallback) + mini-panel
   (k-anonymous price comparison vs. region). Lead-gen bait.
2. **SHIPPED DARK (this model)**: marketing account — promo publishing (manual +
   CSV/XLSX + JSON batch), "verificado"-by-receipts badge, per-chain subscription.
   **Launch promo: free until 2026-12-31** (`MERCHANT_FREE_UNTIL`), mirroring the
   consumer pricing-launch mechanic. The consumer-facing sponsored feed
   (`/price-index/sponsored-promos`) ships INERT behind
   `MERCHANT_PROMOS_FEED_ENABLED` (default false); payment integration INERT
   (manual ACTIVE until Mercado Pago is wired — DEV_NOTES).
3. **Upsell (not built)**: promo-effectiveness report → bridge to the expensive B2B tier.

**Guardrails (non-negotiable):**
- Paid promos NEVER alter the "cheapest" ranking (same constraint as §4).
- Announced promo ≠ observed price: separate tables; merchant claims never
  enter the collaborative index as observations.
- Merchant panel aggregates obey the same k-anonymity as public aggregates.

> Full design + MVP state: **[`docs/MERCHANT_ACCOUNTS.md`](docs/MERCHANT_ACCOUNTS.md)**.

---

### 5. Premium Family / Business Plans (later)

- **Family plan (R$19.90/month, up to 5 users)** — shared household, split
  budgets, individual dashboards.

#### Business plan — volume tier (allowance + overage)

**Who:** micro/small businesses that buy at retail/atacado and get NFC-e in the
owner's CPF — bars, food trucks, snack bars, padarias, buffets, cafés, daycares,
small offices, small markets. They want to track COGS/expenses by category,
compare prices across Atacadão/Assaí/markets, and feed accounting. Secondary:
bookkeepers handling several small clients; corporate expense/reimbursement.

**Why a normal PRO plan doesn't fit:** a business scans **dozens–hundreds of NF/
month**, and *our cost scales with volume* (captcha ~R$0.03–0.09/scan, CE/fallback
~R$0.24). A flat low price loses money on the heavy user. So the Business tier is
**usage-based**, not flat — the one place where metering by **volume is honest**
(volume = both our cost and their value; state is never the axis — see Cost
Structure below).

**Shape (v1, numbers to validate):**
- **Base R$79/month → 300 NF/month included.**
- **Overage R$0.40 per NF** above the allowance.
- Included: up to 5 users, expense categories / cost centers, monthly COGS report,
  accounting export (CSV/Parquet), benchmark vs. the public index.

**Unit-economics guardrail:** set overage (R$0.40) **above the worst-case marginal
cost** (R$0.24, CE) so every NF past the allowance is profitable *in any state*.
The included 300 are priced on blended expected cost + the value features. Watch
the thin-margin edge case — a 100%-CE business at the allowance costs us ~R$72 of
the R$79; mitigate by (a) sizing the allowance on blended cost, (b) capping CE
share of the allowance until a native CE scraper exists, or (c) accepting it
because CE-only bulk businesses are rare and the data is worth it. Native scrapers
(see Cost Structure) collapse this risk — every state added drives that state's
marginal cost toward ~R$0.

**What actually justifies the price** is the *features* (accounting export, cost
centers, COGS report, multi-user), not raw scans — lead the pitch with those.

**Engineering notes (when we build it):**
- Paid-API caps are **global today**; the Business tier needs **tier-aware caps**
  (a business must not hit the consumer 20/60-per-day guard). Wire through
  `SubscriptionGateService` alongside the existing tier limits.
- Need a **monthly NF-scan meter per account** (reuse the `paid_api_call` ledger or
  the receipt counter) and an **overage-billing hook** onto the provider webhook.
- Bulk businesses are **gold for the B2B index** (more data) — doubly valuable,
  *only if* priced not to lose money.

---

## Cost Structure & Spend Controls (the COGS side)

Revenue is only half of unit economics — the other half is what each scanned
receipt *costs us* in paid external calls. Treated as a Day-1 concern too.

### What a receipt costs (marginal, per scan)

| Path | Cost/receipt | Why |
|---|---|---|
| RS / PR / SP / MS / SC scrape | ~R$0.03–0.09 | captcha solve only (1–3 solves) |
| **CE** | ~R$0.00 | Native scraper since 2026-10 (`CearaNfcePortalAdapter`, qrcodev3 JSON API, no captcha) — Infosimples (~R$0.24) only rescues genuine fetch failures, like any other state |
| Any-state fallback | +R$0.24 | primary scraper failed → Infosimples rescue |
| **Categorização/extração por IA (LLM, por nota)** | **~R$0.03–0.08** | Agora padrão (aposentou o Naive Bayes); prompt cacheado. **Meter no `PaidApiGuardService`** — escala com o volume grátis. |
| OFF product enrichment | R$0.00 | Open Food Facts is free/open data |

> **Update 2026-11:** the CE analysis below (and the CE figures in the Business-plan
> section) was written when CE was Infosimples-only. The native CE scraper has since
> shipped (`CearaNfcePortalAdapter`), so the "structurally expensive CE" case no longer
> holds; the reasoning still applies to any state that falls back to Infosimples.

The danger case: a heavy CE user scanning 40 notes/month costs ~R$9.60 — nearly
the whole R$9.90 subscription. **The problem is not the price, it's that CE is
structurally expensive.**

### Pricing verdict: DO NOT price by state

Technically possible (we own the paywall), but rejected:
- Users don't think in states; "R$12 in CE, R$9.90 in SP" reads as arbitrary/unfair.
- CE is expensive because *we* lack a native scraper, not because the CE user gets
  more value. Charging them more punishes the customer for our infra gap.
- Kills word-of-mouth and a national brand.

The honest axis, if we ever meter cost to users, is **volume** (receipts/month) —
which already *is* the PRO pitch ("unlimited scans"). Volume correlates with both
cost and value. **State never does.**

### The real fix is structural, not tariff

Build native scrapers for the expensive states so marginal cost → ~R$0 (captcha
only). **CE is the priority** — until then treat its Infosimples spend as a capped
**customer-acquisition cost** (each scan also warms the B2B price index), not a
repassable COGS.

### Spend controls (implemented — see `service/paidapi/PaidApiGuardService`)

Every money-spending call (captcha solve, Infosimples query, AI call, SMS/WhatsApp) is metered:
- **Per-user daily caps** (owner decision 2026-11-11; config `economizaai.paid-api.*`;
  tier-independent, a pure cost/abuse guard) and a **daily ceiling PER SERVICE**:

  | Service | Per-user cap / day | Daily ceiling |
  |---|---|---|
  | Infosimples | 10 | R$ 120 |
  | Captcha solve | 30 | R$ 30 |
  | Receipt by photo (vision, per photo) | 10 | R$ 30 |
  | HTML parse rescue (AI) | 5 | R$ 15 |
  | AI categorization | 60 | R$ 50 |
  | SMS / WhatsApp (Twilio) | 3 | R$ 15 |

  When a service's ledger total for the day reaches its ceiling, **only that service** stops
  until midnight UTC — the other services and the free scraping keep working. This replaced
  the single global R$ 50/day kill-switch, which still exists as an optional overall ceiling
  (`daily-global-budget-cents`, **off by default**). It is the insurance against a viral
  spike — the "scaled too fast" fear.
- **Admin alerts** — WhatsApp to the admin at 80% and 100% of each service ceiling, and of the
  prepaid balance of each provider (Infosimples, CapSolver, Twilio, Anthropic); retried until
  delivered, with e-mail as the fallback channel.
- **Infosimples circuit breaker** — repeated failures open the circuit for a
  cooldown, per state (one state's portal outage doesn't pause the others), so we stop
  paying while the provider is down.
- **Ledger** — every attempt (success/failure) is written to `paid_api_call` for
  invoice reconciliation, and surfaced at **`GET /api/v1/admin/costs`** (spend
  total + breakdown by service and by state, plus today's spend vs the budget).

All enforcement toggles via `PAID_API_GUARD_ENABLED`; logging is always on. During
the free warm-up we deliberately bank the cost — but bounded by the caps above, so
coverage grows without unbounded spend. (Follow-ups tracked in DEV_NOTES: captcha
metered per-scrape not per-solve; cap enforced async not fail-fast at submit.)

---

## Implementation Roadmap

### Phase 1 — Build the Panel (current)

- **Goal:** 100 active users in Porto Alegre, ingesting receipts weekly.
- **Focus:** Free product, low friction, fast MVP.
- **Monetization:** None. Pure growth. Subscription tier field exists but is unenforced.
- **Key metric:** Weekly receipts uploaded per user (the panel quality metric).

### Phase 2 — Validate Willingness to Pay (1K MAU)

- **Goal:** 5–10% of users accept a Pro plan.
- **Features to add:**
  - Subscription gating (history range, upload count, predictions).
  - Stripe/Pagar.me + Pix integration.
  - Subscription management page.
  - Ad slots on dashboard for free users.
- **Revenue target:** R$1K–R$5K MRR.
- **Validate before building (Business tier):** do NOT build the volume/Business
  plan yet — first prove demand. Signal to watch: users who hammer the 5-scan free
  cap, or scan dozens/month; then talk to 3–4 small-business owners. Only if the
  signal is real does the Business tier (allowance + overage, see §5) move to
  Phase 4. Keep it a hypothesis until then.

### Phase 3 — Open the B2B Channel (5K MAU, ≥3 cities)

- **Goal:** First paying B2B customer.
- **Features to add:**
  - Aggregation pipeline production-grade (k-anonymity tested).
  - B2B portal + sample report generator.
  - LGPD documentation pack.
  - Sales-friendly category / region / time-window export API.
- **Revenue target:** R$10K–R$30K MRR (Pro + 1–2 B2B subs).

### Phase 4 — Scale Both Sides (20K+ MAU)

- **Goal:** Multi-state coverage, recurring B2B revenue.
- **Features:**
  - SEFAZ adapters for all major states.
  - Multi-region B2B reporting.
  - Family plan + **Business volume tier** (allowance + overage — §5), *only if*
    Phase-2 validation showed real bulk-scan demand. Needs tier-aware paid-API
    caps + a monthly scan meter + overage billing.
  - Affiliate-link program at scale.
- **Revenue target:** R$80K+ MRR.

---

## Competitive Moat

### Why Apple/Google/Nubank won't kill this

1. **Focus asymmetry** — receipts as pricing data is our entire product, their 0.01% feature.
2. **Brazilian-specific rail** — NFC-e + per-state SEFAZ adapters. Foreign products do not have this primitive; they all assume manual entry or bank-statement parsing.
3. **Data depth as a moat** — once we have a year of cross-region, cross-retailer transaction data, even an incumbent walking in has to start from zero.
4. **Two-sided product** — consumer panel funds the data product, data product subsidizes consumer features. Hard to replicate without one of the sides already in motion.
5. **Speed** — we ship pricing features weekly, FMCG/retail incumbents ship them yearly.

### Real risks

1. **Cold start** — need ~100 weekly active reporters per region for the index to be useful. Niche-down hard at first (Porto Alegre supermarkets).
2. **SEFAZ availability** — public NFC-e pages can be flaky. Cache, retry, and tolerate.
3. **Retailer pushback** — a chain might dislike being publicly compared. Stay clearly within the public-data legal scope.
4. **LGPD missteps** — one re-identification incident kills trust permanently. Get this right before the B2B channel opens.
5. **Direct competitor with better distribution** — a bank app or fintech adding receipt parsing. Defense: be deeper and more open than they ever will be.

### Defense strategy

- Own Porto Alegre's supermarket data first. Then RS state. Then expand.
- Be transparent about the anonymization model — publish the invariants.
- Build community loyalty via the personal product — make Pro genuinely save money.
- Consider open-sourcing parts of the parsing layer to lock in standards.
- If acquired by a NielsenIQ-equivalent or a Brazilian fintech — that's a realistic exit.

---

## Key Metrics to Track

| Metric | Phase 1 Target | Phase 2 Target | Phase 3 Target | Phase 4 Target |
|---|---:|---:|---:|---:|
| Monthly active users (MAU) | 100 | 1,000 | 5,000 | 20,000 |
| Receipts ingested / week | 200 | 3,000 | 20,000 | 100,000 |
| Distinct markets covered | 30 | 300 | 1,500 | 5,000 |
| Pro subscribers | — | 50 | 500 | 2,500 |
| B2B clients | — | — | 1–2 | 10+ |
| MRR | R$0 | R$2K | R$25K | R$80K+ |

The receipts/week metric is the one to obsess over — it's the panel quality
signal that everything else (Pro retention, B2B sellability) ultimately
depends on.

## Unit economics — custo por nota ingerida (estudo 2026-07-22)

Risco de bloqueio de IP: baixo hoje (volume pequeno, 1 consulta/chave, dedup
cross-household); a resposta de escala é proxy residencial BR (~R$1-2 por
1.000 consultas — 100x mais barato que Infosimples) e não mais API paga.

| Estratégia | Custo/nota |
|---|---|
| Scrape gratuito (atual) | R$0 |
| Scrape via proxy BR (escala) | ~R$0,002 |
| Captcha solve (MS/SC) | R$0,03-0,09 |
| LLM parseia HTML já baixado (Haiku-class, prompt cacheado) | R$0,03-0,08 |
| LLM vision lê foto da nota (sem SEFAZ) | ~R$0,05 — degrada EAN/matching; só p/ contingência |
| Infosimples | R$0,24 |

Cadeia recomendada (cada camada resgata a anterior): scrape grátis → LLM
extrai do HTML quando o parser falha (auto-cura mudanças de layout; substitui
a maioria dos resgates Infosimples) → Infosimples só quando o FETCH falha →
solver p/ captcha. Custo misto projetado: ~R$0,01-0,02/nota (vs R$0,24 se
tudo fosse Infosimples). LLM NÃO substitui o fetch — o risco de IP/captcha
mora no HTTP, não no parse.

> **✅ Shipado 2026-10-12:** a camada "LLM extrai do HTML quando o parser falha"
> está LIVE (`HtmlReceiptRescueService` na stack Anthropic, ledger
> `LLM_HTML_RESCUE` ~R$0,03-0,13, cap 5/usuário/dia; validada com nota real de
> 10 UFs contra o modelo real). **E o CE ganhou scraper nativo no mesmo dia**
> (`CearaNfcePortalAdapter` — api JSON qrcodev3 escondida atrás do shell Angular,
> chave pura, sem captcha): **nenhum estado é mais Infosimples-only**; o R$0,24
> virou fallback puro em todo lugar. Falta da cadeia: só o proxy residencial
> (quando a escala exigir).
