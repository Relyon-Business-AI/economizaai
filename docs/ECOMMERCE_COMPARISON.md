# E-commerce price comparison + "savings machine" — design & handoff

**Status (2026-09-22):** backend foundation SHIPPED and **inert** (dev + prod). No
frontend yet — deliberately deferred so the feature stays dark until we choose to
expose it. This doc is the single source of truth: what exists, why, and how to
continue. A future Claude should be able to resume from here without re-deriving.

---

## 1. The vision (north star)

Turn economizai from a passive spend tracker into an **active "máquina de economizar"** —
the user's best friend for buying groceries for less. The end state the owner described:

- User builds a **shopping list**.
- We suggest the **best shopping route**: which physical market to buy each item at,
  which items to buy **online** (e-commerce), and whether a second market is worth the
  trip — i.e. *is the extra saving greater than the travel cost?*
- We earn **affiliate commission** on the e-commerce purchases (monetization that aligns
  with the user: we save them money AND get paid).
- Possibly a curated shelf of **"itens avulsos"** (one-off deals) — as affiliate, never
  our own inventory.
- A **competition / leaderboard** layer to drive the behaviors that monetize (scanning →
  data density; buying via our links → commission).

**Why e-commerce first:** the physical multi-market route optimizer needs **dense, fresh
price-per-market data**, which we don't have yet (pre-PMF, ~50 real users). The e-commerce
comparison **monetizes without density** (the catalog is external) and delivers value on
day 1. So we built the e-commerce layer first; the physical route optimizer is a later phase.

**Strategic note (owner, 2026):** don't force daily engagement — grocery shopping is
weekly/biweekly. Win the moments that matter (right after shopping: "pagou bem?"; before:
"onde está mais barato"; a weekly digest). Metric that matters here: **savings delivered**,
not screen time.

---

## 2. What's built (current state)

All backend. **INERT by default** — nothing changes on existing flows until configured.
With no provider configured, the feature serves **only admin-curated offers**.

### 2.1 E-commerce comparison

| Concern | File | Notes |
|---|---|---|
| Config (per-provider, env vars) | `config/EcommerceProperties.java` | `@ConfigurationProperties("economizai.ecommerce")`. Master `enabled`, `worthItMinSavings`, `defaultCep`, `Map<String,Provider> providers`. Provider = enabled/clientId/clientSecret/affiliateTag/affiliateApiKey/affiliateApiUrl/siteId/baseUrl + `isConfigured()`. |
| Provider SPI | `service/ecommerce/EcommerceProvider.java` | `key()`, `isConfigured()`, `searchByEan(ean, cep)`. MUST be inert (return empty, never throw) when unconfigured. |
| Fetched-offer shape | `service/ecommerce/ProviderOffer.java` | transient record; `total()` = price + freight. |
| Mercado Livre provider | `service/ecommerce/MercadoLivreProvider.java` | Inert until creds. Live path (OAuth client-credentials → Bearer → `/sites/{site}/search?q=EAN`) is written to the documented ML shape but **UNTESTED LIVE**. Guarded (returns empty on any error). |
| Offer resolution | `service/ecommerce/EcommerceOfferService.java` | `bestOfferForItem(item, cep)`: curated offers (by EAN) + each configured provider's matches → cheapest total in stock → compare to `paidUnitPrice` → `worthIt`. |
| Curated offer entity | `model/EcommerceOffer.java` + migration `V74__ecommerce_offers.sql` | table `ecommerce_offers` keyed by `ean`; `curated` flag distinguishes human vs auto-fetched. |
| Repo | `repository/EcommerceOfferRepository.java` | `findByEanAndActiveTrue`, paged admin lists. |
| Admin curation | `service/admin/AdminEcommerceService.java` + `controller/AdminEcommerceController.java` | CRUD, ADMIN-gated via `/api/v1/admin/**`. |
| User endpoint | `controller/EcommerceController.java` | `GET /receipt-items/{id}/offer` (household-scoped). |
| DTOs | `dto/response/EcommerceOfferResponse.java`, `dto/response/CuratedOfferResponse.java`, `dto/request/CuratedOfferRequest.java` | |
| Exception + i18n | `exception/EcommerceOfferNotFoundException.java` (+ `GlobalExceptionHandler`, `messages_en/pt`) | 404 mapping. |
| Tests | `service/ecommerce/EcommerceOfferServiceTest`, `MercadoLivreProviderTest`, `service/admin/AdminEcommerceServiceTest` | curated logic, worth-it, cheapest-wins, inert provider. |

**Endpoints:**
- `GET /api/v1/receipt-items/{id}/offer?cep=` → `EcommerceOfferResponse` (`provider, title,
  price, freight, total, currency, externalUrl, affiliateUrl, imageUrl, inStock, curated,
  worthIt, paidPrice, savings`). **204** when no offer for the item's EAN. Household-scoped.
- `GET/POST/PUT/DELETE /api/v1/admin/ecommerce/offers` → curated CRUD (ADMIN).

**"worthIt" logic:** `savings = paidUnitPrice − (price + freight)`; `worthIt` = savings > 0
AND savings ≥ `worthItMinSavings`. Honest by design (freight always included in the total).

### 2.2 Discount-hunter leaderboard (opt-in)

| Concern | File |
|---|---|
| Opt-in flag | `model/User.java` `shareInLeaderboard` (default false) + migration `V75__user_share_in_leaderboard.sql` |
| Ranking query | `repository/ReceiptItemRepository.discountHuntersSince` (native CTE) |
| Service | `service/LeaderboardService.java` |
| DTO + request | `dto/response/LeaderboardResponse.java`, `dto/request/LeaderboardOptInRequest.java` |
| Controller | `controller/LeaderboardController.java` |
| Repo helpers | `UserRepository.findByShareInLeaderboardTrue`, `findByHouseholdIdIn` |
| Tests | `service/LeaderboardServiceTest` |

**Endpoints:**
- `GET /api/v1/leaderboard/discount-hunters?days=30` — public, **opted-in households only**,
  first-name handles, always returns the caller's own `me` standing.
- `PATCH /api/v1/leaderboard/opt-in` `{ optIn }` — toggle appearing publicly.
- `GET /api/v1/admin/leaderboard/discount-hunters?days=30` — admin, everyone, email handles.

**Metric ("discount caught"):** a confirmed item whose `paid_unit_price` is below the
**community average** unit price for that product in the window, where the average only
counts products bought by **≥2 households** (so you can't beat your own average). `savings`
= total R$ below that average.

---

## 3. How to turn it on (config / env vars)

All env vars ship **empty** (see `application.yaml` `economizai.ecommerce.*` and DEV_NOTES).
Set on Render when ready — **the owner fills these, not Claude** (secrets/infra are gated).

```
ECOMMERCE_ENABLED=true                      # master switch
ECOMMERCE_MERCADOLIVRE_ENABLED=true
ECOMMERCE_MERCADOLIVRE_CLIENT_ID=...        # ML App ID  (developers.mercadolivre.com.br)
ECOMMERCE_MERCADOLIVRE_CLIENT_SECRET=...    # ML Secret Key
ECOMMERCE_MERCADOLIVRE_AFFILIATE_TAG=...    # ML affiliate tag (appended to links)
ECOMMERCE_MERCADOLIVRE_AFFILIATE_API_KEY=   # optional: third-party link API (e.g. Bot do Afiliado)
ECOMMERCE_MERCADOLIVRE_AFFILIATE_API_URL=
ECOMMERCE_MERCADOLIVRE_SITE_ID=MLB
ECOMMERCE_WORTH_IT_MIN_SAVINGS=0
ECOMMERCE_DEFAULT_CEP=
```

**Mercado Livre integration reality (verified 2026-09-22):**
- **Catalog/price**: official ML Developer API. OAuth2 (App ID + Secret → access token),
  product search supports GTIN/EAN. This is the `MercadoLivreProvider` path.
- **Affiliate links**: ML has **NO official affiliate API**. Options: (a) a manual affiliate
  **tag** appended to product links (from the ML affiliate panel), (b) a third-party link
  API such as **Bot do Afiliado** (`X-API-Key: bk_...` + your ML tag + session cookie,
  `/api/v1/convert-links`). The exact link/commission format **must be confirmed live**.

Adding a **new e-commerce** (Amazon BR, Magalu, …) = implement `EcommerceProvider` + add a
`economizai.ecommerce.providers.<key>` block with its env vars. No orchestration changes.

---

## 4. What's NOT built yet / next steps

### 4.1 Frontend — DEFERRED ON PURPOSE
Not built to avoid exposing the feature prematurely. When ready:
- **Admin curation screen** — CRUD over `/admin/ecommerce/offers` (mirror the categorization
  admin screens). Search by EAN, add/edit/delete an offer, toggle active.
- **User "vale a pena online?" card** — on a receipt-item detail (and later the shopping
  list), call `GET /receipt-items/{id}/offer`, show price + freight + savings + buy button
  (the `affiliateUrl`). Only render when `worthIt` (or show "não compensa" honestly).
- **Leaderboard screen** + an **opt-in toggle** in settings (`PATCH /leaderboard/opt-in`).
- Gate visibility behind the master `enabled`/curated-data availability so it doesn't show
  empty.

### 4.2 Matching accuracy (the make-or-break)
Currently **EAN-exact only** (precision first) + admin curation. Coverage is low by design.
Next: **fuzzy matching** behind a confidence threshold, reusing the **categorizer**
(brand + normalized name + pack size). Never surface a low-confidence match — it destroys
trust. Keep precision > coverage.

### 4.3 Provider hardening (when creds land)
- Verify `MercadoLivreProvider` live: token grant, EAN search response shape, **freight**
  (currently only free-shipping detected; paid freight needs a per-item shipping call to a
  CEP), and the **affiliate link format** (commission tracking).
- Add token caching (currently fetches per call — fine while curated is primary).
- Consider persisting fetched offers (`curated=false`) with a short TTL to cut API calls.

### 4.4 The bigger roadmap (owner's full vision)
- **Phase 2 — physical per-market comparison**: "arroz mais barato no Zaffari vs Bistek perto
  de você." Needs price density per market/region (turn on region-by-region as the index
  densifies). Ingredients already exist: `MarketLocation`, `DistanceCalculator`,
  `NominatimGeocoder`, the price index.
- **Phase 3 — route optimizer** (the crown jewel): shopping list → cheapest source per item
  (each market / online+freight) → "is a second market worth the trip?" = marginal saving of
  splitting − travel cost (user sets R$/km). Small N of markets → brute-force over subsets is
  fine. All ingredients exist (list, geo, distance) EXCEPT dense per-market prices.
- **"Itens avulsos"** — NOT our inventory. Curated affiliate "achados da semana" + 1-tap
  reorder of frequently-bought items from history + affiliate bundles ("kit limpeza do mês").
- **Sponsored challenges** — brand-funded scan challenges (direct monetization + shopper
  activation). Needs volume to sell + a brand partner → later. The opt-in leaderboard is the
  seed.

### 4.5 Leaderboard tuning
The "below community average" metric is a reasonable v1. Revisit weighting (savings vs count),
anti-gaming (min observations, exclude outliers), and privacy handles once there's volume.

---

## 5. Design decisions & rationale

- **Curated-first, providers inert** — monetizes/works before any API access; precision is
  human-controlled; degrades gracefully. Matches "começar focando em precisão + admin cura".
- **Pluggable provider SPI + generic per-provider config** — so new e-commerces plug in
  without touching orchestration. Owner's explicit ask.
- **Everything inert by default** — same pattern as Meta Ads / LLM layers. Ships dark; the
  owner flips env vars when ready. No risk of leaking an unfinished feature.
- **Precision > coverage** — a wrong match destroys the trust that is the app's core asset.
- **Opt-in leaderboard** — public ranking only for users who consent; admin sees all. Privacy
  by default.
- **Freight always in the total** — "cheaper online" that ignores shipping is a lie; honesty
  protects trust and the affiliate relationship.

---

## 6. How to resume (checklist for a future Claude)

1. Read this doc + DEV_NOTES ("E-commerce price comparison" + "Discount-hunter leaderboard").
2. Confirm the backend is still inert (`ECOMMERCE_ENABLED` unset → curated-only).
3. If building the **FE**: start with the admin curation screen (safe, admin-only), then the
   user card gated behind data availability. Update CHANGELOG/API as usual.
4. If turning on **Mercado Livre**: owner sets env vars; then live-verify `MercadoLivreProvider`
   (token, EAN search, freight, affiliate link) before trusting commissions.
5. If improving **matching**: add fuzzy matching via the categorizer behind a confidence gate.
6. For the **route optimizer**: it's blocked on per-market price density — check the index
   depth per region first (admin "Preços" screen / market-intel).

**Related code/docs:** `CHANGELOG.md` (2026-09-22 entry), `DEV_NOTES.md`, `MONETIZATION.md`
(affiliate/commerce as a revenue model), `API.md` (endpoint contracts).
