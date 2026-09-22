# economizai — Development Log

## Project Vision

economizai (GitHub: **economiz.AI**) is a collaborative price-intelligence app
built around Brazilian **NFC-e** (Nota Fiscal de Consumidor Eletrônica). Users
scan the QR code on their grocery receipts, the system parses every line item,
and that history powers two complementary products:

1. **Personal money tracker** — every product the user has ever bought, where,
   when, for how much. Spend dashboards, price-evolution charts, suggested
   shopping lists, stock-out predictions, and "where should I buy this next?"
   recommendations.
2. **Collaborative price index** — every uploaded receipt also contributes
   anonymized price/market/date data to a shared base. All users benefit from
   reference prices, deal detection, and "cheapest market right now" insights
   that nobody buying alone could ever generate.

**Core value proposition:** "Scan your receipts. Stop overpaying. The whole
community wins."

### Key Differentiators

- **Zero data-entry friction.** NFC-e is a Brazilian legal artifact — every
  retail purchase already has a structured receipt. We ride that rail instead
  of asking users to type prices.
- **Network effect by construction.** Every user who joins to track their own
  spending automatically improves the price index for everyone else.
- **Brazil-native.** Per-state SEFAZ adapters, CNPJ-keyed market identity, R$
  pricing, Portuguese-first UI. International expansion is explicitly out of
  scope for V1 — depth over breadth.
- **B2B data is a Day-1 architectural concern**, not a bolt-on. Anonymization
  boundaries and aggregation pipelines are designed in from the start so the
  Nielsen/IBOPE-style data product is a query away, not a rewrite away.

---

## Architecture

### Domain Model (Planned)

| Aggregate | Purpose |
|---|---|
| **User** | Account, auth, household membership, subscription tier (FREE/PRO), reputation. |
| **Household** | Optional grouping of users that share a purchase history (a couple, a family). |
| **Receipt** (NFC-e) | One scanned invoice. Stores access key (chave de acesso), CNPJ, market, date, total, source URL, raw HTML/JSON snapshot. |
| **ReceiptItem** | One line on a receipt: product, qty, unit, unit price, total, raw description. |
| **Product** (canonical) | Normalized product registry — same item across stores resolves here. Fields: canonical name, brand, EAN/GTIN, category, unit. |
| **ProductAlias** | Free-text descriptions seen on receipts mapped to a canonical Product (NFC-e descriptions are messy and store-specific). |
| **Market** | A retail location — CNPJ + name + address + geocoded point. Multiple receipts roll up here. |
| **PriceObservation** | Anonymized fact: (product, market, date, unit price). The atom of the collaborative price index. Built by the ingestion pipeline from every confirmed receipt. |
| **PricePoint** (aggregated) | Rolling aggregates by (product, market, day/week/month). Denormalized for fast dashboards. |
| **ShoppingList** | A user's planned purchases. Optimizable across nearby markets. |
| **StockEstimate** | Per-(user, product) prediction of when they'll run out, derived from purchase cadence. |
| **Subscription** | Billing record. Tier, period, provider ref. |
| **AnonymizedExport** | Generated B2B data product (aggregated, k-anonymous) — log of what was sold to whom. |

### Services (Planned)

1. **Auth Service** — registration, login, JWT, password reset, email verification, OAuth (Google).
2. **Household Service** — invite, join, leave; switch active household; share/private toggles.
3. **Receipt Ingestion Service** — accept QR string → resolve SEFAZ URL by state → fetch HTML → parse → produce Receipt + ReceiptItems → fan-out to canonicalization and price-index pipelines. **The core feature.**
4. **Product Canonicalization Service** — fuzzy-match raw item descriptions to canonical Products via EAN, alias table, and similarity. Suggests new aliases for review when no match found.
5. **Price Index Service** — write PriceObservations, maintain PricePoint aggregates, expose "best market for this product right now / nearby" queries.
6. **History & Insights Service** — user-facing dashboards: spend per category/month, price evolution per product, top markets, top categories.
7. **Consumption Intelligence Service** — purchase-cadence model per (user, product); shopping-list generation; stock-out alerts; cross-market basket optimization.
8. **Collaboration Service** — anonymized contribution toggle, k-anonymity guarantees, abuse/outlier filtering before facts hit the shared index.
9. **Subscription & Billing Service** — tier management, payment gateway integration, feature gating.
10. **Analytics & Export Service** — B2B aggregated exports, anonymization auditing, partner-facing API.

### NFC-e Ingestion Strategy

- The QR code on every NFC-e encodes a **public SEFAZ URL** that varies by state (RS, SP, MG, etc.). The HTML at that URL contains the full structured invoice (CNPJ, market, items, prices, date, taxes).
- Ingestion pipeline:
  1. Client posts the QR string.
  2. Backend identifies state from the access key (chave de acesso, 44 digits).
  3. State-specific adapter resolves the URL and fetches the HTML.
  4. Parser extracts receipt + items into our domain model.
  5. Async job runs canonicalization and writes PriceObservations.
- Each state adapter is an interface implementation — start with RS (where the user lives), expand by demand.
- Fallback path: manual entry / OCR of printed receipt for damaged QRs (post-MVP).
- Keep the **raw HTML snapshot** per receipt — parsing rules will evolve and we'll want to reprocess.

### Anonymization & Collaboration

- A PriceObservation never carries user_id. It's keyed by (canonical_product_id, market_cnpj, observed_at, unit_price). Source receipt id is kept in a private join table for audit only.
- Aggregation enforces **k-anonymity** at query time: any (product, market, time-window) bucket exposed publicly must have observations from ≥ K distinct users (start K=3).
- Outlier filter: drop observations more than N standard deviations from the rolling median for that (product, market) before they hit the index.
- Users can opt-out of contribution. Their data still powers their own dashboards but never lands in PriceObservation.

---

## Community Strategy — the deep moat

The personal product is the hook. The collaborative panel is the **moat**. Every
architectural and product decision should be evaluated against: *does this make
the panel more valuable as it grows, and harder to replicate by anyone without it?*

### Why community is where the value compounds

A single user with a personal price tracker has a useful tool. A million users
contributing to a shared anonymized panel becomes a **public-good infrastructure
play** that competitors can't catch up to without years of cold-start. The
asymmetry isn't 1M× value — it's roughly N log N because of network effects:

- **More households** → finer geographic coverage → better "near me" answers.
- **More markets** → cross-market arbitrage detection works.
- **More categories** → category-level inflation tracking becomes meaningful.
- **More time** → trend detection (price velocity, seasonality) becomes possible.
- **More chains** → competitive comparison panels for B2B.

The personal product is what each user gets *individually*. The community
product is what they all get *because everyone else is also using it*. This
second loop is where retention becomes structural rather than feature-based.

### What we have today (Phase 4 + 5 shipped)

- ✅ `PriceObservation` table with no user_id (LGPD-anonymized contribution atom)
- ✅ Private `PriceObservationAudit` for k-anon counting and right-to-delete
- ✅ K-anonymity guard at query time (default K=3 distinct households)
- ✅ Reference price + best-markets + community-promo endpoints, all gated
- ✅ Personal-promo detection on every confirm (uses each user's own history)
- ✅ Watched markets (user-curated CNPJs that bypass radius)
- ✅ Volume-gated everywhere — endpoints return empty until data is real, never
  show low-confidence "guesses" that erode trust

### Community-value features still to build (priority order)

**Tier 1 — high-leverage, build with current data shape:**

1. **Personal inflation index.** For each household, compute a basket-level
   IPCA-equivalent over their purchase history. Real-time, beats the
   government's monthly IPCA which lags ~6 weeks. This is a flagship PRO feature
   *and* a viral B2C hook ("você sentiu que tudo subiu? aqui está o número
   exato pra sua casa este mês"). Built from data we already have.
2. **Cross-market basket optimization.** Given a user's suggested shopping list,
   solve: "go to market A for these 4 items, market B for these 3, save R$Y vs.
   buying everything at one place." The math is simple; the value is huge.
   Requires Phase 3 (done) + price index (done) + watched markets (done).
3. **Price-drop alerts ("avise-me quando").** ✅ **SHIPPED (2026-06-06).**
   User says "alert me when leite Italac drops below R$6 within 5km of home."
   Alerts fire when a confirmed receipt anywhere in the network triggers the
   rule. Pure community value: one user's confirmed receipt benefits another.
   **The retention loop.** `PriceAlert` entity + `/api/v1/alerts` CRUD;
   evaluated on the price-index write path (`PriceIndexService.recordContributions`
   → `PriceAlertService.evaluate`), delivers a `PRICE_DROP` notification.
   24h per-rule cooldown, skips the contributor's own household, honors
   optional home-radius. Stockout/community-promo triggers (enum values) still
   unwired.
4. **Inflation heat map per neighborhood.** Visualize price change % per
   `cep` prefix or city zone. K-anon protected (won't render zones with <3
   households). Both consumer-facing ("see your zone vs. neighbors") and B2B-sellable.

**Tier 2 — needs richer data or new infra:**

5. **Stockout signals.** A market that consistently doesn't carry product X (no
   observations, while neighbors do) is signal for both the user ("don't bother
   stopping here") and the brand ("our distribution gap is here"). Requires
   distinguishing "didn't buy" from "wasn't there" — non-trivial.
6. **Price velocity citywide.** "Milk up 8% in Porto Alegre this month" — derive
   from rolling medians per (product, city, week). Unlock category-level views
   (rice up 12%, beans down 3%). Foundation for the news-media partnership
   strategy in MONETIZATION.md §2.
7. **Substitution graph.** "When product X is 20% above its median, users buy
   product Y instead at this market." Powerful B2B intel; consumer-side becomes
   "cheaper alternative we noticed others reaching for."
8. **Trust + reputation layer.** Weight contributions by household tenure and
   confirmed-receipt-count. Heavy contributors' receipts count more, so a
   single bad-actor account can't pollute the median. *Avoid* publicly ranking
   users — anonymization is the brand promise.
9. **Forecasting.** With ≥6 months of cross-market data per product+region,
   simple seasonality models give "expect rice prices to dip in March" — useful
   for both consumers ("buy in bulk now / wait") and B2B (procurement timing).

**Tier 3 — exit-aware features (build only if growth justifies):**

10. **Open API for academic researchers.** Free tier of the B2B data product,
    permissive license for inflation research. Trades short-term revenue for
    legitimacy and citations that justify regulatory comfort.
11. **Real-time IBGE companion data.** Cross-reference our index against
    official IBGE inflation, surface discrepancies. Gives credibility for
    eventual government / central-bank conversations.

### Anonymization at scale — what works at 100, breaks at 100k

**Today (K=3 distinct households per bucket):**
- Adequate for now (small panel, ops is the only adversary).
- Trivially defeated by a determined attacker once the panel grows.

**Before we hit 10k MAU we need:**

1. **Differential privacy** for any aggregate exposed publicly or to B2B. Add
   calibrated Laplacian noise to medians and counts so individual contributions
   are statistically deniable. The K=3 threshold becomes a *floor*, not the
   only protection.
2. **Bucket coarsening** — for sparse (product, market, week) cells, fall back
   to (product, chain, month) aggregates. Don't let the API surface a row that
   uniquely identifies one household.
3. **Audit pipeline** for re-identification risk. Run periodic pen-tests:
   "given the public outputs, can I infer that household X bought product Y
   at market Z on date D?" If yes, tighten the gate.
4. **Right-to-be-forgotten cascades.** When a user deletes their account, their
   `PriceObservationAudit` rows are deleted. The corresponding `PriceObservation`
   rows can stay (they're anonymized) but the contribution count for k-anon
   recalculation must drop them. Test this end-to-end before we have anyone to
   delete.
5. **Geographic obfuscation.** `cep` is too precise — 8 digits identifies one
   block. Truncate to 5 digits (neighborhood) for any public output. Keep full
   `cep` in audit only.

### Contribution quality — the abuse model

As soon as the panel becomes valuable, someone has incentive to poison it. Threats:

- **Fake receipts** — generated NFC-e with a real chave but fake prices. Blocked
  by re-fetching SEFAZ HTML and comparing against submitted parsed values.
  Already done implicitly (we fetch + parse from SEFAZ ourselves; the user
  only submits the QR string).
- **Real-but-distorted purchases** — buying 1 item at an unusual price to bias
  a competitor's market down. Mitigated by the outlier filter (>N stddev from
  rolling median) — the column exists but the logic is deferred until we have
  enough volume to validate the math.
- **Mass automated submissions** — bot accounts submitting many receipts.
  Mitigations needed: rate limiting per user/IP, CAPTCHA on suspicious volume,
  reputation weighting (new accounts contribute less).
- **Markets gaming their own listings** — a chain employee submitting fake
  promo receipts. Mitigated by k-anonymity (one bad household = no impact),
  cross-receipt validation (chave de acesso uniqueness), and reputation.

We don't need to build all of this Day 1, but the **architecture must allow it**:
the audit table already does, the master switch already does, k-anon already
does. The reputation column doesn't exist yet — should land before we open the
B2B channel.

### What we explicitly are not doing

- **Not** building a social network. No public profiles, no following, no
  reviews. The community is statistical, not social. Lower abuse surface,
  better LGPD posture, less product complexity.
- **Not** publishing per-household data anywhere, ever. Even if a user wants
  to share it, we're not the channel.
- **Not** optimizing for "most contributions" leaderboards. Gamification in
  this space attracts spammers more than it attracts good signal.
- **Not** building user-to-user features (chat, comments). Same reason.

### Cold-start strategy

The panel is useless to user 1 (no one to compare against) but valuable to
user 1,000. Bridge the gap:

1. **Niche down hard at launch.** Porto Alegre supermarkets only. A user there
   gets useful "best markets near you" answers from week 1 because we focus
   density. Expanding "Brazil-wide" before density is fatal.
2. **Personal product carries the panel.** The hook is "I save money on my own
   shopping" (works for a user of one). The panel is a side effect they don't
   need to care about for the app to be valuable.
3. **Showcase what the panel makes possible** as soon as K=3 fires for
   anything — "5 households in your area also tracked this product, here's the
   range" — even if the data is tiny, the *concept* is what makes them invite
   others.
4. **Direct outreach.** Personal posts in r/portoalegre, local Whatsapp groups
   for grocery deals. The first 100 users come from one founder's network.

### Tech Stack

- Java 21, Spring Boot 4.0.6, Maven
- PostgreSQL (primary store)
- Flyway (database migrations)
- Spring Security + JWT (auth)
- Lombok (boilerplate)
- SLF4J (logging)
- ZXing (QR decode, when needed server-side)
- Jsoup (HTML parsing of SEFAZ pages)
- springdoc-openapi (Swagger UI)
- Bucket4j (rate limiting — added in Phase 2)
- Spring Mail (verification + password reset — added when needed)
- Bean Validation (Jakarta)

---

## Roadmap

The roadmap below maps directly to the product spec (E1–E5). Phases are
deliverable units; ship a phase, then plan the next.

### Phase 0 — Foundations (this session)

- [x] Project bootstrapped (Spring Initializr)
- [ ] CLAUDE.md, HELP.md, MONETIZATION.md committed
- [ ] pom.xml hardened (Flyway, JWT, springdoc, validation, Jsoup, ZXing)
- [ ] application.yml with profile-based config
- [ ] .env.example with required vars
- [ ] i18n scaffolding (messages_en, messages_pt, DomainException, LocalizedMessageService)
- [ ] BaseEntity (id, createdAt, updatedAt)
- [ ] GlobalExceptionHandler
- [ ] Postman collection skeleton with E2E Flow folder
- [ ] First Flyway migration (users table)
- [ ] Git repo initialized, local user config, GitHub remote, initial commit pushed

### Phase 1 — MVP: NFC-e capture (E1 + E2)

**Goal:** A user can register, log in, scan an NFC-e QR, and see the parsed receipt.

- Auth Service (register, login, /me, password change) — port from parkhere
- Household entity (single-user household auto-created on registration; share/invite later)
- Receipt ingestion endpoint: `POST /api/v1/receipts` accepting `{ qrPayload }`
- RS-state SEFAZ adapter (start where the user lives)
- Receipt + ReceiptItem entities, raw HTML snapshot column
- Confirmation flow: parsed receipt returned to user, user confirms or edits items, then it's persisted as `CONFIRMED`
- `GET /api/v1/receipts` (paginated history), `GET /api/v1/receipts/{id}`
- Postman E2E covers: register → login → submit QR → confirm → list → fetch
- i18n for all error messages

**Out of MVP:** product canonicalization, price index, dashboards, predictions, collaboration, multi-state adapters.

### Phase 2 — History & Insights (E3)

**Goal:** Users see their spending and price patterns.

- Product canonicalization V1: EAN match + alias table + manual review queue
- Spend dashboard: by month, by category, by market
- Per-product price evolution chart data endpoint
- Purchase history with filters (date range, market, category)
- Top markets / top categories endpoints
- Migration adds `products`, `product_aliases`, normalized FKs on `receipt_items`

### Phase 2.5 — Auto-categorization (exploratory)

**Goal:** Eliminate the "every new product has `category=null`" friction so
the spend-by-category dashboard is useful from day 1, without paying an LLM
per-call.

**Why exploratory:** classical ML accuracy on Brazilian product short-text
needs to be validated against real receipt data before we commit. If
v1 lands < 85% accuracy, we revisit (LLM fallback for low-confidence,
hybrid, or just punt to manual).

- `ProductCategoryClassifier` Spring service using **Smile** (Apache 2.0,
  ~2 MB JAR). Algorithm: **TF-IDF on character n-grams (3-5) +
  Multinomial Naive Bayes** as the v1 baseline. Char n-grams handle
  noisy receipt text ("ARROZ TIO J TP1 5KG") without needing perfect
  tokenization.
- Training data: every `Product` where `category IS NOT NULL` is one
  labeled example. Cold-start with a curated CSV under
  `src/main/resources/seed/product-categories.csv` (~500 common
  Brazilian items) until organic data accumulates.
- Inference is in-process, sub-millisecond. No per-call cost.
- Confidence threshold: only auto-set `product.category` when
  `predictedProbability > 0.75`. Otherwise leave null and let the user
  set it manually (the failure mode is "review queue grows", not
  "wrong category pollutes dashboards").
- Wire into `CanonicalizationService`: after creating/matching a Product,
  if `category` is null, predict and set if confident.
- Feedback loop: when a user `PATCH`es `product.category`, mark that
  example as high-priority for the next training pass.
- Endpoint: `POST /admin/categorizer/retrain` (manual trigger) +
  weekly cron once volume justifies it.

**Reach goal:** export the trained model + 100k labeled Brazilian
product examples as a B2B asset (CPG analytics firms struggle with
NFC-e text normalization).

### Phase 2.6 — Preferences & right-sized quantities (auto-derive shipped)

**Status:** auto-derivation shipped (read-only). Manual override deferred —
intended as a PRO feature (see MONETIZATION.md).

- ✅ `HouseholdPreferenceService` derives per-generic preferences directly
  from confirmed purchase history. Pure stateless computation, no new tables —
  the data lives in receipts already, recomputing per request is cheap at
  current volume.
- ✅ Volume-gated: silently skip generics with fewer than
  `economizaai.preferences.min-purchases-per-generic` (default 5) confirmed
  purchases. Empty list until the household has data — no low-confidence
  noise.
- ✅ Brand preference uses concentration thresholds:
  top brand share ≥ `must-have-brand-share` (default 0.85) → `MUST_HAVE`,
  ≥ `preferred-brand-share` (default 0.60) → `PREFERRED`, otherwise omitted.
  `AVOID` is intentionally NOT auto-derived — it requires "user actively
  rejected this brand" signal which only manual UI gives. Reserved for PRO.
- ✅ Pack-size preference: dominant `(packSize, packUnit)` of the household's
  purchases of that generic, plus the observed min/max range so downstream
  consumers can soft-rank rather than hard-exclude.
- ✅ `GET /api/v1/preferences` — list of `HouseholdPreferenceResponse` with
  confidence (LOW/MEDIUM/HIGH based on sample size).
- 🟡 **Wire into best-markets / suggested-list ranking** — deferred. The
  derived preferences are computed but other endpoints don't consume them
  yet. Adding the soft-ranking is a one-shot change once we want to validate
  it; meanwhile the data is observable via `/preferences` for the FE to
  surface as-is ("we noticed you usually buy 1L Italac").

**Original (pre-implementation) plan, kept for reference:**

**Goal:** Recommendations respect what this household actually wants and
can store, instead of always pushing the cheapest unit price.

**Motivation:** A 5kg sack of rice is cheaper per kg, but if a single-person
household takes 8 months to finish it (and might spoil it), it's not
actually a better deal. Same for brand preference — if the user always
buys Tio João, recommending an unknown white-label even at 30% off may
just be ignored.

**Why exploratory:** the preference model can over-engineer fast (storage
capacity dimensions, perishability tables, consumption rate per person).
V1 should be lean and prove value before adding sophistication.

V1 scope (lean):

- Add `Household.householdSize` (Integer 1-10, default 1, user-editable
  in profile).
- New `HouseholdProductPreference` entity: `(household_id, product_id)`
  with optional `preferredBrand`, `preferredQuantityMin/Max`,
  `strength` enum (`NICE_TO_HAVE` / `IMPORTANT` / `MUST_HAVE`).
- **Implicit learning** (no UI required): on receipt confirm, derive
  per-(household, product) stats from purchase history:
  - typical brand = mode of brands purchased
  - typical pack size = median of quantities
  - frequency = purchases per month
  Surface these as read-only "auto-detected preferences" in the API.
- **Explicit override:** user can `PATCH` a preference to lock it
  (e.g., "MUST_HAVE lactose-free milk", strength=MUST_HAVE).

V1 uses (Phase 3 features built on top of this):

- "Best market" recommendations filter out pack sizes outside the
  household's preferred range when ranking by total cost.
- Shopping list generator picks the pack size that minimizes
  `cost / consumed_units_before_typical_repurchase` instead of raw
  unit price — this is the "right-size" heuristic.
- For `MUST_HAVE` brand preferences, the cheapest-substitute path is
  hidden entirely.

V2 (out of scope for this exploratory phase, but architectural
placeholder):

- Storage capacity hints (`SMALL_APARTMENT` / `HOUSE` / `BULK_FRIENDLY`).
- Per-product perishability flag (rice = OK to bulk, milk = not).
- Consumption rate model per person (4-person family eats 3.5x what a
  couple eats — not exactly 2x).

### Phase 3 — Consumption Intelligence (E4) — shipped

**Goal:** App tells the user what to buy next, where, and when.

- ✅ Purchase-cadence model per (household, product). Simple mean of
  intervals between unique purchase dates over the last
  `economizaai.consumption.history-lookback-days` (default 365).
- ✅ Stock-out / running-low classification with three states
  (`RAN_OUT`, `RUNNING_LOW`, `OK`). Threshold configurable via
  `economizaai.consumption.running-low-threshold-days` (default 7).
- ✅ Confidence label (`LOW` / `MEDIUM` / `HIGH`) so the FE can
  down-weight noisy estimates.
- ✅ Endpoints under `/api/v1/consumption`:
  - `GET /predictions` — per-product prediction list, soonest first.
  - `GET /suggested-list` — union of `RAN_OUT` + `RUNNING_LOW`.
- ✅ Volume-gated: products with fewer than
  `economizaai.consumption.min-purchases-for-prediction` purchases
  (default 3) are silently skipped — we don't surface low-confidence
  noise.
- 🟡 Basket optimization (split a list across nearby markets) — deferred
  until we have enough cross-market price coverage to make it
  meaningful.
- 🟡 Phase 2.6 preference filter (right-sized pack, preferred brand)
  — deferred until pack-preference data exists per household.

**Volume-gate env vars:**

| Var | Default | What it gates |
|---|---|---|
| `economizaai.consumption.enabled` | `true` | Master switch |
| `economizaai.consumption.min-purchases-for-prediction` | `3` | Need this many prior purchases of a product before predicting |
| `economizaai.consumption.history-lookback-days` | `365` | Window for interval calculation |
| `economizaai.consumption.running-low-threshold-days` | `7` | Days-until-runout that triggers `RUNNING_LOW` |
| `economizaai.consumption.ran-out-grace-days` | `0` | Tolerance before flipping to `RAN_OUT` |

### Phase 5c — Watched Markets (shipped)

User-curated CNPJs to monitor regardless of distance — solves the
"market on my commute is outside home radius but I want its promos"
case. Combines with `radiusKm` filter as `radius OR watched`.

- ✅ V13 migration: `user_watched_markets (user_id, market_cnpj)` —
  unique on the pair, cascade on user delete. Soft FK to
  `market_locations` (the cache rebuilds itself from receipts).
- ✅ `MarketController` endpoints:
  - `GET /api/v1/markets[?radiusKm=X]` — catalogue for the picker UI:
    union of (a) markets the household has shopped at, (b) currently
    watched, (c) (optional) within radius. Each row carries `visited`
    and `watching` flags so the FE can draw the right checkbox state.
  - `GET /api/v1/markets/watched` — "Meus mercados" view.
  - `POST /api/v1/markets/watched/{cnpj}` (idempotent), `DELETE /…/{cnpj}`.
- ✅ Wired into existing price-index queries: watched markets bypass
  the radius filter in both `GET /price-index/products/{id}/best-markets`
  and `GET /price-index/promos`. Each row in the response carries a
  `watching` boolean.
- Markets enter the catalogue automatically when a household submits a
  receipt with a previously unseen CNPJ — see
  `MarketLocationService.registerMarketFromReceipt`.

### Phase 4 — Collaborative Price Index (E5) — shipped

**Goal:** Anonymized contributions power shared price intelligence.

- ✅ `PriceObservation` table (no user_id; LGPD-anonymized) + private
  `PriceObservationAudit` for k-anon counting and right-to-deletion.
  V10 migration.
- ✅ Write path runs on `POST /receipts/{id}/confirm`. Skipped when
  `User.contributionOptIn = false` or master switch
  `economizaai.collaborative.enabled` is off.
- ✅ K-anonymity-guarded queries — return empty when fewer than
  `min-households-for-public` distinct households contributed.
- ✅ Public endpoints under `/api/v1/price-index`:
  - `GET /products/{id}/markets/{cnpj}/reference` — median + min + max
    + sample size + distinct-household count for that pair.
  - `GET /products/{id}/best-markets?limit=10` — markets ranked by
    median price, k-anon checked per row.
  - `GET /promos` — current community promos (recent median X% below
    baseline).
- ✅ **Personal promo detector** runs on every confirm. Compares paid
  unit price vs the user's own historical median; threshold and
  baseline-size configurable via `economizaai.personal-promo.*`.
  `POST /receipts/{id}/confirm` now returns
  `{ receipt: ReceiptResponse, personalPromos: [...] }`.
- ✅ **Community promo detector** in `CommunityPromoService.detectAll()`
  — recent (last 7 days) median vs baseline (8-90 days) per
  (product, market). Returned by `GET /price-index/promos`.
- ✅ `market_cnpj_root` column (first 8 digits of CNPJ) preserved on
  every observation so future queries can aggregate per chain
  (Zaffari Hipica vs Zaffari Centro vs all Zaffari).
- 🟡 Outlier filter — column exists (`is_outlier`); flagging logic
  deferred until we have enough volume to validate the math.
- ✅ Geolocation / distance-based filtering — Phase 5a (V11 migration).
  User has `homeLatitude/Longitude` set via `PATCH /users/me/location`.
  Markets registered in `market_locations` table on receipt confirm,
  geocoded asynchronously by `MarketLocationService` via Nominatim
  (1 req/sec rate-limited, 3 retries max). `bestMarkets` and `promos`
  accept `radiusKm` query param to filter to within X km of user's home.
- ✅ Notifications + per-user channel preferences — Phase 5b
  (V12 migration). EmailDispatcher (SMTP via Spring Boot Mail, gated by
  `economizaai.notifications.email.enabled`) + PushDispatcher (V1 stub
  that logs FCM payload — wire `firebase-admin` SDK to ship real push).
  `NotificationPreference` per (user, type) chooses channel; default is
  PUSH if user has registered a `pushDeviceToken`, else EMAIL.
  Personal promos detected on receipt confirm dispatch immediately via
  `NotificationService` and the result is logged to the `notifications`
  table (delivered/failed + reason).
  Endpoints: `PATCH /users/me/push-token`,
  `GET/PUT /users/me/notification-preferences`.

**Volume-gate env vars** (so features stay quiet until data is real):

| Var | Default | What it gates |
|---|---|---|
| `economizaai.collaborative.enabled` | `true` | Master switch — turn off to disable all reads/writes |
| `economizaai.collaborative.min-households-for-public` | `3` | K-anon: queries return empty until N distinct households contributed |
| `economizaai.collaborative.min-observations-per-product-market` | `5` | Reference price hidden until enough samples |
| `economizaai.collaborative.min-observations-for-community-promo` | `10` | Community promo not flagged until baseline is solid |
| `economizaai.collaborative.community-promo-threshold-pct` | `15` | Recent median must be X% below baseline |
| `economizaai.collaborative.lookback-days` | `90` | Window for "recent" data |
| `economizaai.personal-promo.threshold-pct` | `10` | Personal promo if price < median - X% |
| `economizaai.personal-promo.min-purchases-for-baseline` | `3` | Need this many prior buys to call a personal promo |

### Phase 4.5 — LGPD compliance baseline (shipped)

**Goal:** minimum infrastructure for the app to legally collect personal data
from real Brazilian users.

- `User.acceptedTermsVersion` + `acceptedPrivacyVersion` + `acceptedLegalAt`
  required at registration. New `RegisterRequest` rejects unknown versions.
- `GET /api/v1/legal/terms` and `GET /api/v1/legal/privacy-policy` (public)
  serve the current markdown versions of both documents.
- LGPD rights endpoints on `/api/v1/users/me`:
  - `PATCH /me/contribution` — opt in/out of the collaborative price index
  - `GET /me/export` — full data export (user + household + receipts as JSON)
  - `DELETE /me` — hard delete of the user; receipts cascade-deleted via
    V6 migration; household removed if no members remain
- pt + en i18n for the new error/success messages.
- Stub markdown documents under `src/main/resources/legal/` marked as
  v1.0 — must be reviewed by a Brazilian privacy lawyer before any
  public launch.

The collaborative price index itself (Phase 4) hasn't shipped yet — when it
does, it must respect `User.contributionOptIn` and never write rows that
include a user identifier in the public table.

### Phase 5 — Monetization (parallel from Phase 2 onward)

See `MONETIZATION.md` for the full strategy. High-level:

- FREE/PRO subscription tier scaffolding (entity + middleware, even if not enforced yet)
- Affiliate-link infrastructure for retailer apps/promotions
- B2B aggregated export endpoints (gated)
- Sponsored placements (with disclosure)

### Phase 6 — Frontend

Backend-only for now. When frontend lands, mirror parkhere (Next.js,
Portuguese-first, Render deployment).

---

## Extraction Pipeline Reference

This is the **single source of truth** for how a raw NFC-e item description
becomes structured data (genericName, brand, packSize, packUnit, category).
Read this section to understand the runtime behavior without opening
source code.

### What gets extracted

For every new `Product` (one is created the first time we see a unique
EAN, or via `POST /products`):

| Field | Source | Example |
|---|---|---|
| `normalizedName` | raw NFC-e description, untouched | `ARROZ TIO J TP1 5KG` |
| `genericName` | dictionary or ML | `Arroz` |
| `brand` | brand registry CSV | `Tio João` |
| `packSize` + `packUnit` | regex on description | `5`, `KG` |
| `category` | dictionary, learned dict, or ML | `GROCERIES` |
| `categorizationSource` | which layer set the category | `DICTIONARY` |
| `ean` | from the receipt | `7891234567890` |

### The cascade (in order, per new Product)

```
raw description: "ARROZ TIO J TP1 5KG"
        │
        ▼
┌───────────────────────────────────┐
│ 1. PackSizeExtractor (regex)       │  packSize=5, packUnit=KG
│    always runs                     │
└───────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────┐
│ 2. BrandExtractor (registry)       │  brand="Tio João"
│    always runs                     │
└───────────────────────────────────┘
        │
        ▼
┌───────────────────────────────────┐
│ 3. DictionaryClassifier            │  if hit: genericName + category
│    a) curated CSV (highest)        │  source = DICTIONARY or
│    b) learned dictionary (auto)    │           LEARNED_DICTIONARY
└───────────────────────────────────┘
        │ (if dict missed any field)
        ▼
┌───────────────────────────────────┐
│ 4. MlClassifierService (Naive Bayes)│ if confidence ≥ 0.75:
│    only fires if dict missed AND   │   set field
│    classifier is ready             │   source = ML
└───────────────────────────────────┘
        │ (if all layers missed)
        ▼
┌───────────────────────────────────┐
│ Product saved with field=null      │  shows up in
│ source=NONE                        │  /products/unmatched
└───────────────────────────────────┘
```

**Extraction runs ONCE per Product** (when the Product is first created).
Subsequent receipts with the same EAN just link to the existing Product —
no extraction is re-run.

### `categorizationSource` enum (audit trail)

Stored on every `Product`. Tells you exactly how the category got there:

| Value | Set by |
|---|---|
| `NONE` | Nothing extracted; needs manual review |
| `DICTIONARY` | Hit on `seed/product-dictionary.csv` (curated by humans) |
| `LEARNED_DICTIONARY` | Hit on auto-promoted entry (started life as ML) |
| `ML` | Naive Bayes prediction with confidence ≥ threshold |
| `USER` | Manual `PATCH /products/{id}` |

The pipeline NEVER trains on `ML` rows — only `DICTIONARY`,
`LEARNED_DICTIONARY`, and `USER` are trusted training data. This prevents
self-reinforcement.

### Where data lives

| What | Where |
|---|---|
| Curated dictionary | `src/main/resources/seed/product-dictionary.csv` (in repo, edit + redeploy) |
| Brand registry | `src/main/resources/seed/brand-registry.csv` (in repo, edit + redeploy) |
| Learned dictionary | `learned_dictionary` table in Postgres (managed by AutoPromotionService) |
| ML training data | derived from `products` table at retrain time (in-memory only) |
| Trained ML models | in-memory only on the running JVM (re-trained on startup + weekly) |
| Per-Product source | `products.categorization_source` column |

### Schedules + manual triggers

| Operation | Schedule | Manual trigger | Notes |
|---|---|---|---|
| ML retrain | weekly + on app startup | `POST /api/v1/categorizer/retrain` | needs ≥30 trusted training examples to actually train; otherwise stays "not ready" |
| Auto-promote ML → learned dict | daily + on app startup | `POST /api/v1/categorizer/auto-promote` | promotes tokens with ≥30 ML samples + ≥90% category agreement + 0 user overrides |
| Status check | n/a | `GET /api/v1/categorizer/status` | shows ready, lastTrainedAt, confidenceThreshold |

### How to read the logs to verify the cascade

Logs use MDC tags `[req=… user=… rcpt=… item=…]`. Filter by `rcpt=<id>` or
`item=<id>` in your log viewer to see one receipt's full story.

Per-item logs from `CanonicalizationService`:

```
item.matched_by_ean   ean=… product=…                      (existing product reused)
item.matched_by_alias product=… normalized='…'             (alias linked)
item.created_from_ean ean=… product=… extracted={…}        (NEW product, extraction ran)
item.unmatched description='…' (no EAN, no alias)          (review queue)
```

Per-Product extraction details (when ML fires):

```
extract.ml.category.hit            confidence=0.87 predicted=GROCERIES description='…'
extract.ml.category.below_threshold confidence=0.42 predicted=… description='…'   (DEBUG)
extract.ml.genericName.hit         confidence=0.81 predicted='Arroz' description='…'
```

ML training lifecycle:

```
ml.retrain.skipped reason=insufficient-data trustedProducts=17 categoryExamples=15 minRequired=30
ml.retrain.scheduled trigger
ml.retrain.done categoryExamples=312 categoryLabels=8 vocab=4521 elapsedMs=84
```

Auto-promotion lifecycle:

```
auto_promote.scheduled trigger
auto_promote.promoted token='racao' category=OTHER genericName='Ração' samples=42 agreement=0.95
auto_promote.done PromotionOutcome[promoted=3, skippedDueToHuman=1, skippedDueToAgreement=2, skippedDueToSamples=18, learnedTotal=3]
```

### Tuning knobs (env vars)

| Var | Default | Effect |
|---|---|---|
| `economizaai.ml.confidence-threshold` | `0.75` | Below this, ML predictions are ignored (kept null) |
| `economizaai.ml.retrain-interval-ms` | `604800000` (7 days) | How often the ML retrains on schedule |
| `economizaai.ml.auto-promote-interval-ms` | `86400000` (1 day) | How often auto-promotion scans Products |

Bump confidence higher to be more conservative (more items go to review,
fewer auto-categorizations). Lower it to be more aggressive (more
auto-categorizations, more risk of wrong categories).

### What to do when the cascade gets something wrong

1. **Wrong category on one product** → `PATCH /products/{id}` with the
   correct category. Source becomes `USER`. Next ML retrain treats this
   as ground truth and learns from it.
2. **Wrong category on many products with same description token** → the
   user PATCH on any of them blocks auto-promotion of that token (per
   2.5c rules). Eventually the ML retrain (which uses USER as truth)
   adjusts its predictions for similar items.
3. **Missing dictionary entry that you'd like to add** → edit
   `src/main/resources/seed/product-dictionary.csv`, redeploy. New
   inferences immediately use it.
4. **Missing brand** → same, edit `seed/brand-registry.csv`, redeploy.
5. **Stale learned-dictionary entry** → `DELETE` the row in
   `learned_dictionary` table; will be re-evaluated on next promotion
   pass (and re-promoted only if criteria still hold).

### Quality tracking + ML status (2026-06-06)

- **ML is gated OFF in the live cascade** (`economizaai.ml.category-apply-enabled=false`). It's still **trained** every cycle and still **measured** (shadow) by the benchmark, so we keep validating it. Flip it back on (env `ML_CATEGORY_APPLY_ENABLED=true`) once the benchmark's `mlCategoryAccuracyPct` is consistently high. Until then the cascade is dictionary-only (deterministic), which is the right call at current data volume.
- **Quality is measured + tracked over time.** `GET /categorizer/benchmark` reports per-field accuracy (category, brand, quantity) + ML shadow accuracy over `seed/categorization-benchmark.csv`. Every benchmark run and every backfill writes a row to `categorization_quality_snapshots`; `GET /categorizer/quality/history` is the trend. Add golden rows whenever a new failure surfaces.

### Planned (documented, NOT built — avoid complexity until categorization is mature)

These are deliberately deferred. Capturing the intended design so we build it right later:

1. **User corrections should be per-household, not global.** Today `PATCH /products/{id}` mutates the **shared** canonical product (any logged-in user changes it for everyone). Intended model: a user's category/brand/quantity correction writes a **household-scoped override** (mirror `HouseholdProductAlias`), so it only affects that household's view. Reads (items / receipt detail / dashboard) apply the override when present.
   - *Interim guardrail:* consider restricting `PATCH /products/**` to `ROLE_ADMIN` until the per-household path exists, so end users can't mutate the global catalog.
2. **Corrections "count" toward learning (not overwrite).** ✅ **BUILT (2026-06-07).** `ConsensusPromotionService`: when ≥N distinct households correct the same product→category, the product graduates to a global category (source USER) and recurring agreed tokens enter the `learned_dictionary` (cascade source #2). A single household stays personal. Daily + `POST /categorizer/promote-consensus`.
3. **Custom (user-defined) categories.** ✅ **BUILT (2026-06-07).** Households create their own categories (`household_custom_categories`) and migrate products into them via `/api/v1/categories` (+ `/categories/migrate`). Implemented as an overlay on `household_product_category_overrides` (the same household-scoped "evidence, not truth" override that powers per-item correction): a custom override carries `custom_category_id` instead of a global enum, so the global product/catalog is untouched and other households are unaffected. Custom overrides never graduate via consensus (`ConsensusPromotionService` skips null-enum overrides). List a custom category's items with `GET /items?customCategoryId=<uuid>`. The global enum stays the shared baseline.

## Suggested Additions (Beyond the Spec)

These came up while structuring the project — open for discussion:

- **EAN/barcode scan** for shopping-list items, so users can pre-build lists by scanning packages at home before going shopping.
- **Recipe / meal planning** tied to stock predictions ("you have these items expiring, here are recipes").
- **Personal inflation index** — IPCA-equivalent computed from the user's own basket. Genuinely interesting and shareable.
- **E-commerce vs. mercado — onde compensa comprar** — comparar o preço por item no e-commerce (NF-e modelo 55) vs. no mercado físico (NFC-e modelo 65) e sugerir ao usuário quais itens valem mais a pena comprar online. Insumo natural: as NF-e 55 que entram via import de XML / backfill por CPF (ver [`docs/ONBOARDING_IMPORT.md`](./docs/ONBOARDING_IMPORT.md)). Ideia de 2026-07-08.
- **Receipt OCR fallback** for damaged or missing QR codes (post-MVP — Tesseract or a cloud OCR API).
- **Onboarding: scan em lote** — *intenção:* usuário enfileira várias notas e passa a câmera continuamente (cada QR decodificado on-device → pipeline atual), ou seleciona várias fotos da galeria de uma vez. Sem custo, funciona em toda UF suportada, 100% sob nosso controle. Design em [`docs/ONBOARDING_IMPORT.md`](./docs/ONBOARDING_IMPORT.md) (item 1).
- **Onboarding: import de XML (a melhor aposta de import em massa)** — *intenção:* usuário sobe XML (avulso ou lote/zip) e parseamos os itens direto do arquivo, sem SEFAZ/captcha, para qualquer UF. É a ponte natural com e-commerce (NF-e 55 chega por e-mail na compra online); um endereço "email-in" (encaminhe a nota) é o funil de menor atrito. Design em [`docs/ONBOARDING_IMPORT.md`](./docs/ONBOARDING_IMPORT.md) (item 2).
- **Auto-import de notas por CPF** — "CPF na nota" no caixa → compras aparecem sozinhas, sem scan. Spike feito (2026-07-07): viável só com credencial do titular (senha do portal estadual OU e-CPF), nunca com o CPF sozinho; polling não push; fragmentado por estado. Feature paga/"endgame" — achados e design em [`docs/ONBOARDING_IMPORT.md`](./docs/ONBOARDING_IMPORT.md) (item 4).
- **Onboarding: importar export do portal estadual (CSV/Excel/PDF)** — *intenção:* usuário entra no programa estadual (Nota Fiscal Gaúcha / Paulista / etc.), exporta a lista das notas do CPF e sobe o arquivo → app extrai as chaves, filtra supermercado por CNAE (`MerchantSegment.SUPERMARKET`, CNPJ vem da chave) e busca os itens pra popular o histórico já no primeiro acesso. **Bloqueio (2026-07-09):** o export é **só cabeçalho** (chave, emitente, total, data — SEM itens), então os itens dependem de reconsulta por chave, que **só funciona nos estados consultáveis** (SP/PR/CE via Infosimples, pago). **RS não é reconsultável por chave** (muro gov.br; Infosimples também não resolve — ver `ReceiptService`), e o export da NFG traz **apenas notas RS** (toda chave começa com "43") → para usuário do RS rende **zero notas com itens**. Não vale a spike sem acesso a uma conta de outro estado. Revisitar quando: (a) tivermos credencial de outro estado pra testar, ou (b) massa de usuários fora do RS. Bônus regional — ver [`docs/ONBOARDING_IMPORT.md`](./docs/ONBOARDING_IMPORT.md) (item 3).
- **Group/household budget split** — when a household has multiple members, allocate the receipt total across them.
- **Brand loyalty / cashback awareness** — surface that retailer X has a cashback app the user isn't using.
- **LGPD compliance plumbing** — data export, account deletion, anonymization audit trail. Non-negotiable for a public Brazilian app handling financial data.

---

## Sensitive Topics

- **LGPD (Brazilian GDPR)** — receipts contain CPF when the user requested it on the receipt. We must (a) detect and strip CPF before persisting where possible, (b) provide data export and account deletion, (c) keep a clear consent record for collaborative contribution.
- **B2B data sales** — must guarantee k-anonymity in any external export. No re-identifiable timestamps, no per-user trails leaking through aggregations. Document the anonymization invariants and test them.
- **Competitive sensitivity** — once retailers notice us, expect pushback. Keep the SEFAZ-based ingestion clearly within the public-data scope it's designed for.

---

## Build & Run

```bash
./mvnw spring-boot:run        # run the app
./mvnw test                    # run tests
./mvnw clean package           # build jar
docker build -t economizai .  # build production image (mirror of Render build)
```

### Deploying to Render

The repo ships with a `Dockerfile` (multi-stage Eclipse Temurin 21 build →
JRE runtime, exposes port 10000) and a `.dockerignore`. Render auto-detects
the Dockerfile when you create a Web Service from the GitHub repo — no
`render.yaml` needed (mirrors the parkhere setup).

Steps:

1. Push to `main` on `https://github.com/XandiVieira/economizaai`.
2. In Render, **New → PostgreSQL** with name `economizai-db`.
3. **New → Web Service** → connect the GitHub repo. Runtime: **Docker**.
4. Set environment variables (see `.env.example`):
   - `DATABASE_URL` → JDBC form, e.g. `jdbc:postgresql://<internal-host>/<db>`.
     Take the host/port/db from Render's "Internal Database URL" but
     change the `postgres://...` prefix to `jdbc:postgresql://...`.
   - `DB_USERNAME` / `DB_PASSWORD` → from Render's DB credentials.
   - `JWT_SECRET` → generate a random 64+ char string (`openssl rand -hex 64`).
   - `CORS_ORIGINS` → the eventual frontend Render URL.
5. Deploy. Flyway runs migrations V1–V4 on first boot.

Smoke-check after deploy: `https://<service>.onrender.com/swagger-ui` should
return Swagger UI.

---

## Session Log

### Session 1 (2026-04-27)

- Project initialized via Spring Initializr (Java 21, Spring Boot 4.0.6).
- Defined product spec collaboratively — five epics (E1 NFC-e capture / E2 Auth / E3 History & Insights / E4 Consumption Intelligence / E5 Collaborative Base).
- Decided: backend only for now, multi-tenant public app, mirror parkhere conventions for CLAUDE.md, Flyway+Postgres, JWT auth, en+pt i18n.
- Decided: monetization architected from Day 1 — see MONETIZATION.md.
- Wrote CLAUDE.md, HELP.md, MONETIZATION.md.
- Hardened pom.xml and added base scaffolding (i18n, db/migration, postman folders).
- Initialized git, configured local user (Alexandre Vieira / xandivieira@gmail.com), pushed initial three commits to https://github.com/XandiVieira/economizaai.git.
- **Auth foundation implemented (Phase 1, ported from parkhere):**
  - BaseEntity (UUID id, createdAt, updatedAt) + User (extends UserDetails) + Role + SubscriptionTier enums.
  - User has `subscriptionTier` (FREE default) and `contributionOptIn` (true default) — Day-1 hooks for monetization and LGPD-aware collaborative contribution.
  - UserRepository (findByEmail, existsByEmail).
  - JWT layer: JwtService (HS256 via JJWT 0.12), JwtAuthenticationFilter, ApplicationConfig (UserDetailsService bean).
  - SecurityConfig: stateless sessions, BCrypt, CORS via `economizaai.cors.allowed-origins`, public `/api/v1/auth/**` + `/swagger-ui/**` + `/v3/api-docs/**`, `/api/v1/admin/**` requires ADMIN, everything else authenticated, 401 entry point.
  - i18n-aware exception handling: DomainException, LocalizedMessageService, MessageSourceConfig (default locale pt), GlobalExceptionHandler with EmailAlreadyExists / InvalidCredentials / InvalidCurrentPassword / UserNotFound / validation / generic.
  - DTOs: RegisterRequest, LoginRequest, UpdateUserRequest, ChangePasswordRequest, AuthResponse, UserResponse.
  - UserService: register, login, getProfile, updateProfile, changePassword.
  - AuthController: POST /api/v1/auth/register (201), POST /api/v1/auth/login (200).
  - UserController: GET /api/v1/users/me, PUT /api/v1/users/me, PUT /api/v1/users/me/password.
  - Flyway V1__create_users.sql.
  - i18n keys for all auth/validation messages in pt + en.
  - 26 tests passing: JwtServiceTest (5), UserServiceTest (9), AuthControllerTest (5), UserControllerTest (6), contextLoads (1).
  - Postman collection populated: Auth (register, login), Users (me / update / change password), and a 7-step E2E Flow that exercises register → login → me → update → change password → re-login with new password → old password rejected.
- **Spring Boot 4 notes carried forward from parkhere:**
  - `@WebMvcTest` lives in `org.springframework.boot.webmvc.test.autoconfigure`.
  - ObjectMapper not auto-wired in `@WebMvcTest` — instantiate manually.
  - Need `HttpStatusEntryPoint(UNAUTHORIZED)` in SecurityConfig for proper 401 responses.
- Test setup: H2 in-memory in `application-test.yaml`, `@ActiveProfiles("test")` on `@SpringBootTest`, Flyway disabled in tests (Hibernate `create-drop` recreates schema).

### Session 2 (2026-04-27) — Phase 1 implemented

**Households (E2):**
- `Household` entity with 6-char alphanumeric `inviteCode` (avoids visually ambiguous chars 0/O/1/I).
- Auto-created on registration via `HouseholdService.createSoloHousehold` wired into `UserService.register`.
- Endpoints: `GET /api/v1/households/me`, `POST /api/v1/households/join`, `POST /api/v1/households/leave`. Empty households auto-deleted on leave/join.
- V2 migration: `households` table + `users.household_id` FK with backfill block.

**Receipt domain (E1):**
- `Receipt` (status-machine PENDING_CONFIRMATION → CONFIRMED/REJECTED) + `ReceiptItem` with `NUMERIC(12,3)` qty / `NUMERIC(12,2)` totals per CLAUDE.md money rule.
- `UnidadeFederativa` enum keyed by IBGE code; `ReceiptStatus` enum.
- V3 migration: `receipts` + `receipt_items` with FK cascade and indexes on `household_id`, `status`, `issued_at`.

**SEFAZ ingestion (E1):**
- `SefazAdapter` interface + `RioGrandeDoSulAdapter` (Jsoup parser, RestClient with timeout).
- `SefazIngestionService` discovers adapters by `supportedState()`; throws `UnsupportedStateException` for unknown UFs (only RS implemented).
- `ChaveAcessoParser` extracts the 44-digit chave from raw chave / pipe-separated payload / full SEFAZ URL; derives UF from positions 0-1.
- `CpfMasker` strips formatted (`123.456.789-00`) and raw 11-digit CPFs from HTML before persistence (LGPD).
- `RestClientConfig` provides `RestClient.Builder` bean — Spring Boot 4 webmvc starter does not auto-configure it.

**Receipt API:**
- `POST /api/v1/receipts` (201) submit QR; `GET` paginated list scoped to household; `GET /{id}`, `PATCH /{id}/items/{itemId}`, `POST /{id}/confirm`, `POST /{id}/reject`. Edits + status transitions only allowed in `PENDING_CONFIRMATION`.
- Authorization scoped at household level (not user) — household members share the receipt history.
- Duplicate chave returns 409 (`ReceiptAlreadyIngestedException`).

**Cross-cutting:**
- `GlobalExceptionHandler` extended for all new exceptions (404/400/409/502 mapping).
- i18n: 11 new keys in pt + en (households, receipts, SEFAZ).
- Synthetic NFC-e fixture under `src/test/resources/fixtures/sefaz/rs/nfce-sample.html` — replace with real captured pages as production volume grows.
- Postman collection extended: Households + Receipts folders, E2E flow grew 7 → 15 steps. Receipt steps gracefully skip when `qrPayload` collection variable isn't set to a real QR.

**Tests: 26 → 78 passing.**
- New: `ChaveAcessoParserTest` (10), `CpfMaskerTest` (5), `RioGrandeDoSulAdapterTest` (6), `HouseholdServiceTest` (7), `ReceiptServiceTest` (8), `HouseholdControllerTest` (5), `ReceiptControllerTest` (11).

### Session 3 (2026-04-28) — Phase 2 implemented

**Product domain (E3 canonicalization):**
- `Product` (canonical: ean unique nullable, normalizedName, brand, category enum, unit) + `ProductAlias` (rawDescription → normalizedDescription unique → product). `ProductCategory` enum: GROCERIES / BEVERAGES / PRODUCE / MEAT_DAIRY / BAKERY / CLEANING / PERSONAL_CARE / OTHER (English internal, can localize at display layer).
- `DescriptionNormalizer` (NFD strip accents, lowercase, collapse punctuation/whitespace) is the join key between raw NFC-e descriptions and aliases.
- V4 migration: `products` + `product_aliases` + `receipt_items.product_id` nullable FK.

**Canonicalization pipeline:**
- `CanonicalizationService.canonicalize(Receipt)` runs **on confirm** (not on submit — lets users fix descriptions first). Strategy: EAN match → auto-create `Product` if EAN unknown → fallback to alias lookup → leave unmatched.
- Auto-creates an alias when EAN matches (so future receipts with the same product but different raw description hit the alias path).
- Wired into `ReceiptService.confirm` so canonicalization is transparent.

**Product API:**
- `GET /products?query=...` (paginated search by name or exact EAN), `GET /products/{id}`, `POST /products` (201, backfills receipt items by EAN), `PATCH /products/{id}` (set category/brand), `POST /products/{id}/aliases` (creates alias + backfills matching unmatched items in current household).
- `GET /products/unmatched` (review queue): list of receipt items in current household with `product_id IS NULL` from CONFIRMED receipts.

**Insights API:**
- `GET /insights/spend?from=&to=` returns total + buckets by month (year+month numeric), market (cnpj+name), category. Only CONFIRMED receipts.
- `GET /insights/markets/top?limit=`, `GET /insights/categories/top?limit=` — top-N slice of `/spend`.
- `GET /insights/products/{id}/price-history?from=&to=` — chronological unit-price points for a canonical product, scoped to household.
- All endpoints scoped to household_id.

**Receipt list filters:**
- `GET /receipts?from=&to=&marketCnpj=&category=` — added optional filters using JPA Specifications (cleanest for variable WHERE clauses).
- Default sort `issuedAt DESC`. Replaces previous fixed `findAllByHouseholdIdOrderByIssuedAtDesc`.

**Cross-cutting fixes:**
- **Spring Boot 4.0 split Flyway autoconfig** into a separate `spring-boot-flyway` artifact — added to pom.xml. Without it, Flyway dependency is on classpath but no autoconfig runs and the schema is never created. Symptom: app starts cleanly but every JPA call fails with `relation "users" does not exist`.
- **Postgres "could not determine data type of parameter $2"**: JPQL `:param IS NULL OR ...` patterns send untyped null parameters that PostgreSQL JDBC can't infer. Fixed by (a) sentinel dates (1900-01-01 / 2999-12-31) at the service layer for InsightsRepository, (b) JPA Specifications for ReceiptRepository (predicates only added when params non-null).
- **LazyInitializationException on `User.household.getInviteCode()`**: `User.household` is `FetchType.LAZY`, accessing fields beyond `getId()` outside a transaction throws. Fixed `HouseholdService.getMine` to re-fetch the household by ID inside `@Transactional(readOnly = true)`. ID-only access on lazy proxies is still safe (Hibernate short-circuits).
- `GlobalExceptionHandler` extended for product/alias/EAN exceptions (404/409). i18n: 3 new keys pt + en.

**Postman:**
- Products + Insights folders added.
- E2E flow grew 15 → 21 steps. Newman E2E full pass against real Postgres + running app: **21/21 requests, 29/29 assertions green**.

**Tests: 78 → 110 passing.**
- New: `DescriptionNormalizerTest` (5), `CanonicalizationServiceTest` (6), `ProductServiceTest` (6), `ProductControllerTest` (7), `InsightsControllerTest` (4), `InsightsRepositoryTest` (4, integration `@DataJpaTest`).

### Roadmap note (2026-07-22) — rich data exports

Shipped today: `GET /receipts/export` (CSV + XLSX, direct download). The full
vision (user-validated by an organic suggestion + owner direction):
- Export basically ANY filterable data set (receipts, items, categories,
  date/market filters) — not just the fixed purchase history.
- A beautiful spreadsheet (charts/pivots baked into the XLSX).
- A pretty PDF report (tables + charts — monthly summary, category breakdown,
  savings vs. index). PDF as designed report, not raw table dump.
- Delivery choice: direct download OR send via e-mail.
Natural PRO surface (Feature.CSV_EXPORT already gates the endpoint).

### Session (2026-07-16b) — Merchant support gate (retail vs. food service)

Triggered by a real scan: a bar's NFC-e failed with the misleading
`no-items-found` — it was actually a **contingency-issued** note (tpEmis=9) the
SVRS portal rejected with cStat 227. Two changes shipped:

**Merchant support gate** — product rule: recurring food/essentials RETAIL is
in, one-off food SERVICE is out.
- CNAE → segment map extended: `FOOD_RETAIL` (4721-4724, 4729 — padarias,
  açougues, bebidas, hortifrúti, conveniência) and `FOOD_SERVICE` (56xx).
  Any supported retail CNAE (primary or secondary) wins, so posto+conveniência
  counts.
- `MerchantSupportGate` (single decision point): SUPPORTED (retail segments or
  admin override) / BLOCKED (food service or admin override) / GREY (OTHER,
  UNKNOWN). Blocked: known CNPJ → 400 at submit, store nothing; new CNPJ →
  inline CNAE classify during ingest, FAILED_PARSE tombstone without items or
  rawHtml. Grey: full ingest for the user, but PriceObservations held out of
  the collaborative index until reviewed; admin emailed once per CNPJ; queue at
  `GET /admin/merchants/grey`, verdict via `PUT /admin/merchants/{cnpj}/support`
  (SUPPORTED backfills the index from already-confirmed receipts).
- Wrong-CNAE restaurants slipping into grey are acceptable — the user picks
  which items to persist at confirm, and the review queue catches the merchant.

**SEFAZ error-page classification** — `ResponsiveDanfeParser` now detects the
SVRS `alert-danger` rejection page: contingency chave → `receipt.contingency.pending`
("tente mais tarde"); otherwise `receipt.sefaz.rejected_qr:<cStat>`. Real 227
fixture saved under `fixtures/sefaz/rs/`.

**Future (user request):** eventually support gas-station FUEL purchases
themselves (recurring spend, price-comparable per liter) — postos today
classify OTHER (grey) unless they carry a conveniência CNAE.

### Session (2026-07-16) — Prod-readiness hardening + experimental all-states rollout

**Contact/beta parity:** optional `phone` on `POST /contact` (mirrors beta-signup).

**Prod-readiness (from the DEV_NOTES gap review):**
- Auth codes (password reset / email verify / phone OTP) are never logged in prod:
  `economizaai.auth.dev-code-log-enabled`, hard `false` in `application-prod.yaml`.
- Twilio SMS/WhatsApp metered through the paid-API guard (`TWILIO_MESSAGE`, ~R$0.30):
  per-user daily cap (`TWILIO_DAILY_CAP`, default 10) + global budget + ledger. The OTP
  endpoint returns a localized 429 when over cap.
- Render disk for profile pics verified live (mount `/data/profile-pics` matches
  `PROFILE_PICTURE_DIR`; a picture + pre-deploy JWT survived a redeploy).

**Experimental all-states fallback chain (release unblocker):**
- All 27 UFs accepted at submit. Unclaimed UFs gap-fill to `GenericQrPortalAdapter`:
  GET the QR's own portal URL (SSRF-bounded to gov.br) → shared `ResponsiveDanfeParser`
  → Infosimples rescue on fetch OR parse failure. Kill-switch `SEFAZ_EXPERIMENTAL_ENABLED`.
- On-demand learning: `state_ingestion_attempts` telemetry per layer/outcome,
  `GET /admin/state-coverage` per-UF map, admin email on first success per UF
  (promotion instructions) and on total chain failure (evidence bundle, deduped 1/UF/day).
- User-facing terminal failure: `receipt.state.experimental_failed` ("estamos
  trabalhando nisso"); raw HTML kept on the FAILED_PARSE row when the portal responded.
- Bare-chave fail-fast generalized: rejected at submit for ANY state that needs the QR
  signature and has no paid fallback enabled (was RS-only).

### Session (2026-09-22) — e-commerce comparison + discount-hunter leaderboard (INERT, no FE)
- Shipped the backend foundation for the "máquina de economizar" vision: **e-commerce price
  comparison** ("vale a pena online?") — pluggable `EcommerceProvider` SPI (Mercado Livre,
  inert until creds), admin-curated offers (precision-first), `EcommerceOfferService`,
  `GET /receipt-items/{id}/offer`, admin CRUD `/admin/ecommerce/offers`. Generic per-provider
  config with **empty env vars** ready to fill on Render.
- Plus an **opt-in discount-hunter leaderboard** (`/leaderboard/discount-hunters` public/admin,
  `PATCH /leaderboard/opt-in`; `share_in_leaderboard` on User).
- **Everything inert** (nothing changes until `ECOMMERCE_ENABLED` + creds). **No frontend by
  choice** — not exposing an unfinished feature. Migrations V74 (ecommerce_offers) + V75
  (share_in_leaderboard). Suite 1894 green.
- **Full design, current state, and how to continue: [`docs/ECOMMERCE_COMPARISON.md`](docs/ECOMMERCE_COMPARISON.md).**
  Also verified: Mercado Livre has NO official affiliate API (catalog via official API +
  OAuth; affiliate links via manual tag or a third-party link service).

### Session (2026-09-22b) — garimpo de promoções absorvido como módulo (admin API completa)

- **Decisão de arquitetura**: o protótipo standalone `~/Documents/projects/garimpo` (busca ML
  por termo) foi absorvido pelo backend como módulo — ~80% do motor já existia no subsistema
  ecommerce (OAuth ML, normalização, SPI de providers, links de afiliado). Repo/serviço
  separado só quando o runtime de crawling pesar (aí: Render Background Worker, mesmo jar).
- **Entregue** (`/api/v1/admin/garimpo/**`, ADMIN-only): busca por termo (`searchByTerm` no
  provider SPI + token OAuth cacheado), histórico de preços append-only (uma linha por
  MUDANÇA de preço — base do "desconto é real?"), watches com `targetPrice`/`minDiscountPercent`
  varridos por scheduler (hits NOVOS → webhook `GARIMPO_WEBHOOK_URL`, sem re-spam), run-now.
  Migration V76. 25 testes novos verdes (fixture real do payload ML).
- **Bloqueio externo**: Search API pública do ML agora responde **403** (verificado ao vivo) —
  busca ao vivo exige credenciais `ECOMMERCE_MERCADOLIVRE_*` (App ID/Secret). Sem elas, 503
  localizado; CRUD de watches/marketplaces/histórico funcionam. Ver DEV_NOTES.
- Postman: pasta "Garimpo (admin)" + passos 49b3–49b7 no E2E Flow (skip sem admin creds).
