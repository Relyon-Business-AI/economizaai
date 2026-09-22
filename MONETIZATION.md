# economizai — Monetization Strategy

Monetization is treated as a **Day-1 architectural concern**, not a Phase-N
afterthought. The entities, anonymization boundaries, and feature gates listed
below should appear in the data model from the first migration that touches
their area, even if the actual paywall is unenforced for months.

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
  Limits tunable via `economizai.subscription.free.{watched-markets,history-days,monthly-receipts}`.
- ✅ `PaywallException` → **HTTP 402** (`subscription.upgrade_required`).
- ✅ Receipt upload counter — `ReceiptService.submit`, counts all statuses this calendar month.
- ✅ Query-layer date-range cap — `clampFrom` applied in `ItemQueryService`,
  `InsightsService`, `InsightsQueryService`. PRO bypasses.
- ✅ Watched-markets gate — `WatchedMarketService.watch` checks count on NEW pins.
- ✅ Delivery gate — `NotificationService.notify` persists the inbox row for all,
  dispatches push/email only for PRO (`PUSH_AND_EMAIL_DELIVERY`).
- ✅ Basket optimization gate — `ShoppingListOptimizer.optimize` requires PRO.
- ✅ `Subscription` entity + `SubscriptionService.activatePro/cancel` (tier kept in sync).
- ✅ Admin set-tier — `PUT /api/v1/admin/users/{id}/subscription-tier`.
- ✅ Provider-agnostic webhook — `POST /api/v1/webhooks/subscription` (seam for a real provider).
- ⬜ Payment provider (Stripe Brasil / Mercado Pago / Pagar.me + Pix) — **not chosen yet**.
  Needs API keys + map the provider webhook onto `/api/v1/webhooks/subscription`
  and set `economizai.billing.webhook-secret`. See DEV_NOTES.
- ⬜ Self-serve subscription-management page + PUT `/users/me/subscription`.
- ⬜ Feature-flag service so we can A/B individual gates.
- ⬜ Top markets/categories `?limit` hard cap for FREE (history window is gated; the limit cap is not yet).
- ⬜ Manual preference override (AVOID/MUST_HAVE) — depends on Phase 2.6.
- ⬜ Household member-count gate (FREE ≤ 2).

#### Gating sequence (which paywalls to ship first)
1. ✅ **Watched markets cap** (3 free) — shipped.
2. ✅ **History window** (90 days free) — shipped.
3. ✅ **Push notifications** (PRO only) — delivery gate shipped (inbox stays free).
4. ✅ **Basket optimization** (PRO only) — shipped.
5. ⬜ **Manual preference override** (PRO only) — depends on Phase 2.6 completion.

Each gate is an A/B; measure conversion before leaning harder on the next.

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
| **CE** | **~R$0.24** | Infosimples paid API on **every** note (no native scraper yet) |
| Any-state fallback | +R$0.24 | primary scraper failed → Infosimples rescue |
| OFF product enrichment | R$0.00 | Open Food Facts is free/open data |

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

Every money-spending call (captcha solve, Infosimples query) is metered:
- **Per-user daily caps** — Infosimples 20/day, captcha 60/day (config
  `economizai.paid-api.*`; tier-independent, a pure cost/abuse guard).
- **Global daily kill-switch** — a spend ceiling across ALL users
  (`daily-global-budget-cents`, default R$50/day). Once today's ledger total hits
  it, every paid call fails fast until midnight UTC. This is the insurance against
  a viral spike — the "scaled too fast" fear.
- **Infosimples circuit breaker** — repeated failures open the circuit for a
  cooldown, so we stop paying while the provider is down.
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
