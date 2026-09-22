# economizai — dev shortcuts to revisit before prod

Things we shipped that work for development / early users but need an
upgrade before we have real volume / real revenue / real privacy stakes.
Each entry: **what's there now**, **why it's OK for dev**, **what to
change before prod**, **rough effort**.

When in doubt: search the codebase for `// TODO(prod)` markers — they
mirror entries here.

---

## Import em massa por chave (RS) — reconsulta server-side, sem throttle (2026-09-22)
- **Now**: `POST /receipts/import[/nfg-csv]` reconsulta cada chave RS direto do nosso IP
  de servidor — NFC-e via `SAT-WEB-NFE-NFC_*.asp`, NF-e 55 via SVRS `ConsultaPublicaDfe`
  (`RsChaveReconsultClient`). Sem backoff/throttle explícito além do limite de concorrência
  do pool `RECEIPT_INGEST_EXECUTOR`.
- **OK for dev**: volume baixo; um usuário importando dezenas de chaves passa tranquilo.
- **Before prod**:
  1. **Rate-limit / bloqueio de IP**: um lote grande (ou muitos usuários) batendo em
     `sefaz.rs.gov.br` pode fazer o SEFAZ throttlar/bloquear nosso IP. Adicionar throttle por
     host + backoff, e/ou mover a reconsulta para **on-device (PE-style, `/receipts/prefetched`)**
     usando o IP residencial do usuário. Bound do tamanho do lote também.
  2. **reCAPTCHA da NF-e 55**: o POST do `ConsultaPublicaDfe` hoje **não exige** o captcha, mas
     ele está na página — se o SEFAZ passar a exigir, o caminho NF-e 55 quebra e precisa de
     solver (pago) ou on-device.
  3. **Só RS**: outras UFs voltam `receipt.import.unsupported`. Estender por UF conforme
     `docs/MULTI_STATE_RECON.md`.
- **Effort**: throttle+backoff pequeno; on-device fallback médio.

## E-commerce price comparison — built but INERT for dev (2026-09-22)
- **Now**: the "vale a pena online?" subsystem is wired end to end but ships **dark**.
  With no provider configured it serves only **admin-CURATED offers** (precision-first):
  an admin maps an EAN → an online product + price + link via
  `POST/PUT/DELETE /admin/ecommerce/offers`, and `GET /receipt-items/{id}/offer` returns
  the cheapest offer compared to what the user paid.
- **Why it's OK for dev**: no external calls until credentials land; curated offers alone
  prove the loop and monetize via affiliate links without needing catalog API access.
- **Turn on a provider (Mercado Livre) — set these on Render (all empty by default):**
  - `ECOMMERCE_ENABLED=true` (master switch)
  - `ECOMMERCE_MERCADOLIVRE_ENABLED=true`
  - `ECOMMERCE_MERCADOLIVRE_CLIENT_ID` / `ECOMMERCE_MERCADOLIVRE_CLIENT_SECRET` — the ML
    **App ID + Secret** (developers.mercadolivre.com.br). OAuth2 client-credentials → token.
  - `ECOMMERCE_MERCADOLIVRE_AFFILIATE_TAG` — your ML affiliate tag (appended to links).
    ⚠️ ML has **no official affiliate API**; the link/commission format must be confirmed
    live. Optional third-party link API: `ECOMMERCE_MERCADOLIVRE_AFFILIATE_API_KEY` +
    `ECOMMERCE_MERCADOLIVRE_AFFILIATE_API_URL` (e.g. Bot do Afiliado's `/convert-links`).
  - Optional: `ECOMMERCE_MERCADOLIVRE_SITE_ID=MLB`, `ECOMMERCE_MERCADOLIVRE_BASE_URL`,
    `ECOMMERCE_WORTH_IT_MIN_SAVINGS=0`, `ECOMMERCE_DEFAULT_CEP`.
- **Verify before trusting a provider**: `MercadoLivreProvider.searchByEan` (OAuth +
  `/sites/{site}/search?q=EAN`) is written to the documented ML shape but **untested live** —
  confirm the token grant, the EAN search, freight, and the affiliate link when creds land.
  It's guarded (returns empty on any error), so a bad config can't break the offer lookup.
- **Extensible**: add a new e-commerce = new `EcommerceProvider` impl + a
  `economizaai.ecommerce.providers.<key>` block. No orchestration changes.

## Garimpo de promoções — built, live search blocked on ML creds (2026-09-22)
- **Now**: admin deal-hunting on top of the e-commerce providers (`/admin/garimpo/**`):
  term search → append-only price history (`garimpo_price_snapshots`, one row per price
  CHANGE) → watches swept hourly (`GARIMPO_WATCH_SWEEP_DELAY_MS`) whose NEW hits POST to
  `GARIMPO_WEBHOOK_URL` (group bot / n8n; empty = log-only). Absorbs and retires the
  standalone `~/Documents/projects/garimpo` prototype.
- **Blocked on the same ML creds as above**: the PUBLIC ML Search API now returns
  **403 forbidden** (verified live 2026-09-22), so `searchByTerm` always authenticates via
  OAuth client-credentials — no creds → localized 503 on `/search` and watch runs. Watches
  CRUD, marketplaces and history work without creds. ⚠️ Confirm when creds land that the
  client-credentials token is accepted by `/sites/MLB/search?q=<term>` — ML has been
  tightening scopes; if it 403s too, the fallback is a user-token (authorization-code) flow.
- **Token caching**: ML tokens are now cached until ~expiry (in-memory, per instance) —
  relevant for the hourly sweep not to burn a token grant per watch.
- **No webhook receiver yet**: the group-bot side (Telegram/WhatsApp) doesn't exist; point
  `GARIMPO_WEBHOOK_URL` at n8n/Zapier or a tiny bot when the ofertas group launches.

## Discount-hunter leaderboard — opt-in (2026-09-22)
- Public "caçador de descontos" ranking (`GET /leaderboard/discount-hunters`) shows only
  households that **opted in** via `PATCH /leaderboard/opt-in` (`share_in_leaderboard`,
  default false). Admin view (`GET /admin/leaderboard/discount-hunters`) shows everyone.
  The metric ("item bought below the community average, product seen by ≥2 households") is a
  reasonable v1 — revisit the definition/weighting once there's more volume.

## LLM layers — activation + follow-ups (2026-07-22)
- **Activation**: the whole LLM stack (enrichment, auditor, photo extraction) is
  dormant until `OPENAI_API_KEY` is set on Render — workers skip silently, the
  photo endpoint returns a localized 503. Set the key to go live.
- **Sync vision call**: `POST /receipts/items-photo` runs the vision call on the
  request thread (~5-15s). Fine at current volume; move to the async
  PROCESSING+poll pattern if it ever strains the pool.
- **Premium-readiness**: `Feature.PHOTO_EXTRACTION` already gates the endpoint
  (dormant while subscription enforcement is off). Enrichment/auditor are
  global env switches, not per-user.

## Meta Ads spend sync — built but GATED/INERT for dev (2026-09-14)
- **Now**: signup attribution (`utm_*`/`clickId`/`referrer`/`landingPath` on the auth
  calls) + the admin acquisition dashboard (`GET /admin/analytics/acquisition`) are live
  and work with zero external config. The **Meta (Facebook/Instagram) Marketing API daily
  spend sync** that fills the dashboard's `adSpend` block is wired but **dormant** — it
  writes nothing until `META_ADS_ENABLED=true`, `META_ADS_TOKEN`, and `META_AD_ACCOUNT_ID`
  are set (optional tuning: `META_ADS_API_VERSION`=v21.0, `META_ADS_SYNC_DAYS`=30,
  `META_ADS_GRAPH_BASE_URL`). The token must be a **Meta Business System User token with
  `ads_read`** for that ad account.
- **Why OK for dev**: with the vars unset the sync never runs and the `meta_ad_spend` table
  stays empty — the dashboard degrades cleanly (`adSpend.configured=false`, `costPerSignup`/
  `costPerActivated` come back `null`). No paid calls, nothing to break.
- **Before prod**: create the System User + long-lived token in Meta Business Manager, grab
  the ad account id (`act_<digits>`), set the three env vars, and confirm a first sync pulls
  spend (dashboard `adSpend.configured` flips to `true`). Until that's wired with real creds
  the cost-per-signup numbers are all null — the dashboard shows organic/channel funnel only.

## Meta Conversions API (server-side signup events) — built but GATED/INERT for dev (2026-09-16)
- **Now**: every new signup (e-mail + Google/Apple) is reported server-side to the Meta
  Conversions API — email SHA-256-hashed, the ad-click passed as `fbc`, deduped with the
  browser Pixel via `event_id`. Survives ad-blockers / iOS ITP and attributes conversions
  to the campaign. Dispatched async (never blocks or fails a registration).
- **Dormant until** `META_CAPI_ENABLED=true`, `META_CAPI_PIXEL_ID` (the Pixel/dataset id,
  `1088687550381259`) and `META_CAPI_TOKEN` are set. The token is a **Conversions API access
  token generated per-Pixel in Events Manager** (Settings → Conversions API → Generate access
  token) — SEPARATE from the ads token (`META_ADS_TOKEN`). Optional: `META_CAPI_API_VERSION`
  (v21.0), `META_CAPI_GRAPH_BASE_URL`, `META_CAPI_TEST_EVENT_CODE` (Events Manager → Test
  Events, to validate without polluting live data).
- **Why OK for dev**: with the vars unset `MetaConversionsService.isConfigured()` is false and
  nothing is sent — zero external calls, nothing to break.
- **Before prod**: generate the CAPI token in Events Manager for Pixel `1088687550381259`, set
  the three env vars, and confirm `CompleteRegistration` events arrive in Events Manager → Test
  Events (use `META_CAPI_TEST_EVENT_CODE` first). Pairs with the landing Pixel (already live)
  and the app SDK scaffold (frontend `META_APP_EVENTS_SETUP.md`).

## Merchant support gate — two scale follow-ups (2026-07-16)
- **Now**: grey-merchant promotion (`PUT /admin/merchants/{cnpj}/support` →
  SUPPORTED) backfills the price index from ALL of the merchant's confirmed
  receipts in one synchronous transaction.
- **Why OK for dev**: a grey merchant has at most a handful of receipts today.
- **Before prod/scale**: page the backfill (or push it to the async pool) once a
  merchant can have hundreds of confirmed receipts — the admin request would
  otherwise hold a long transaction.
- **Also**: when CNAE classification is disabled (`economizaai.merchant.classify.enabled=false`),
  the index gate FAILS OPEN for unclassified merchants (deliberate, so dev
  environments aren't index-starved). Prod must keep classification enabled or
  every unclassified merchant contributes unreviewed. Effort: small.

## Paid-API cost caps — in place, with two known follow-ups (2026-07-09)
- **Now**: `PaidApiGuardService` meters every paid external call — per-user daily caps
  (`economizaai.paid-api.*`: Infosimples 20/day, captcha 60/day), an Infosimples circuit
  breaker (5 failures/10min → open 5min), and a `paid_api_call` ledger for invoice
  reconciliation. Enforcement toggles via `PAID_API_GUARD_ENABLED` (logging is always on).
- **Follow-up 1 (accuracy)**: captcha is metered **per scrape**, not per solve. A scrape
  can re-solve up to 3× on retry, so the ledger/cap under-counts captcha cost by up to 3×.
  Fine as a coarse abuse guard; tighten to per-solve (meter inside the solver) if captcha
  spend becomes material.
- **Follow-up 2 (UX)**: the cap is enforced in the **async** ingest, so hitting it produces
  a `FAILED_PARSE` (reason `receipt.paid_api.quota_exceeded`) instead of a fast 4xx at
  submit. Consider a fail-fast check at `POST /receipts` once we're comfortable predicting
  the paid service from the UF up front.

---

## ⚠️ EAN catalog is NOT seeded by a migration — prod DB starts EMPTY (2026-07-04)
- **Now**: `V51__create_ean_catalog.sql` creates the `ean_catalog` table but seeds NOTHING. The LIVE DB (dev-branch service, what real users hit) currently holds **~910k EAN entries** (verified 2026-09-22): **907.5k `OPEN_FOOD_FACTS`** (the full OFF dump was imported, NOT just the BR subset — the old "~32k Brazilian products" note was wrong), **2.5k `CURATED_IMPORT`**, **~720 `LIVE_API`** (the on-demand fallback cache-through, grows on every scan). ~594k have a brand. Imported via a one-off runtime stream through `POST /api/v1/categorizer/ean-catalog/import-off` (OFF CSV dump → gunzip → batches of 500).
- **Why it matters for prod**: a fresh prod DB rebuilds schema from Flyway on an empty volume → the EAN catalog is **empty**, so barcode-scan (`/products/by-ean`) returns catalog-preview misses and category-for-EAN-items silently degrades to dictionary-only. No migration and (until this note) no checklist item = data-loss-on-migrate trap.
- **Fix before prod**: after the first prod deploy, re-run the OFF import against the prod host (ADMIN token). ~40 min streaming. Consider baking a seed migration or a startup importer later.

## Chave OCR needs the NATIVE Tesseract on the host (2026-07-08)
- **Now**: `POST /receipts/chave/photo` OCRs the printed chave via Tess4J, which needs the native Tesseract lib + `eng.traineddata` on the host. The Dockerfile runtime stage installs `tesseract-ocr` (apt), and `TesseractOcrEngine` probes well-known tessdata paths (homebrew/apt), overridable via `OCR_TESSDATA_PATH`. Local dev on macOS: `brew install tesseract`.
- **Why it's OK for dev**: if Tesseract is missing the feature degrades cleanly — the endpoint returns a localized 503 (`receipt.ocr.unavailable`) and everything else is untouched. Free (Apache-2.0), no per-call cost, safe for commercial use.
- **Before prod**: keep the apt install in any new base image; smoke-test the endpoint after image changes (the `ocr.init` log line at first use says whether the native lib loaded). If real-photo accuracy on thermal paper disappoints, consider a preprocessing pass (deskew/contrast) before reaching for a paid vision API.

## Paid external services — cost + toggles (CapSolver, Infosimples) (2026-07-04)
- **CapSolver** (captcha): required for MS + SC receipts. `CAPTCHA_PROVIDER=capsolver` + `CAPTCHA_API_KEY`. Without it, MS/SC receipts 503 ("coming soon"). ~US$1-3 / 1000 solves. Key currently in the dev box `.env` and `.claude/settings.local.json` (untracked). **Rotate before prod** and set in the prod env only.
- **Infosimples** (paid SEFAZ fallback, all UFs): `INFOSIMPLES_ENABLED=false` by default; enable with the API key. **~R$0.24 PER QUERY** — a silent cost sink once on. Fires when the primary scraper exhausts retries on a rescuable failure, when a manually-typed bare chave hits a signature-only state (PR/SP), AND for **CE every time** (CE has no native scraper — it's Infosimples-only). **CE support is gated on `INFOSIMPLES_ENABLED=true`**: with it off, `InfosimplesBackedAdapter` isn't registered and CE submits fail fast as "unsupported state". Keep the toggle in the prod runbook.
- Both are absent from `application-prod.yaml`'s recommended-env list — add them there.

---

## Household merge/split — shipped dark, two follow-ups before enabling
- **Now**: full merge (join `bringData` + per-category), restore-on-leave by
  `origin_household_id`, and mutual data-share consent are implemented and tested
  (unit + real-DB integration), but gated OFF by `economizaai.households.merge-enabled`
  (default false). Conflict rule: host household wins; the joiner's colliding row is
  parked on its origin, restorable on split.
- **Why OK for dev**: flag is off, so join/leave behave exactly as before (membership
  only, no data loss thanks to the Phase 0 provenance + delete guard).
- **Before enabling in prod**:
  - **Consent copy is receipt-HEADERS only** — `HouseholdMergeService.copyReceiptHeader`
    does not deep-copy `ReceiptItem`s (TODO(prod) marker in the code). A partner who
    approves sharing gives the requester market/total/date/chave, not line items.
    Decide if that's the intended scope or wire an item deep-copy.
  - **3-way LeaveScope is partially wired**: the `LeaveScope` enum + consent path exist,
    but `leave()` currently restores ORIGINAL_ONLY; `ORIGINAL_PLUS_SHARED` needs a
    per-membership `joined_at` to define the shared window (design Q2, not yet built).
  - Add endpoint-level tests for the consent controller + a join-with-merge flow once
    the flag is turned on.

---

## SMTP wired to GoDaddy/Titan — deliverability not hardened
- **Now**: auth emails (password-reset code, email verification) send via GoDaddy/Titan
  SMTP (`smtpout.secureserver.net:587`, user `contato@relyonai.com.br`). Creds live in
  the gitignored `.env` on the dev box and are passed through to the app container in
  `docker-compose.yml`. Verified end-to-end (code arrives, reset works).
- **Why OK for dev**: real send works; the FE flow is fully testable.
- **Before prod**:
  - **Deliverability**: test emails landed in **spam (Lixo Eletrônico)**. Add **SPF**,
    **DKIM**, and **DMARC** DNS records for `relyonai.com.br` (Titan provides the values)
    so mail authenticates and stops being junked. ~1 hr + DNS propagation.
  - **Rotate the SMTP password** — it was shared in plaintext over chat during setup.
  - Consider a dedicated transactional provider (SES/SendGrid/Resend) for volume +
    bounce handling if auth-email traffic grows.

---

## Billing: apps ready (RevenueCat), web still pending
- **Now**: two webhook paths feed the same entitlement engine. **RevenueCat** (`POST /webhooks/revenuecat`, Authorization header == `REVENUECAT_WEBHOOK_AUTH`) covers iOS/Android IAP; the generic `POST /webhooks/subscription` (`BILLING_WEBHOOK_SECRET`, `X-Webhook-Secret`) is the seam for a web provider. Both **fail closed** when their secret is blank (constant-time compare). A scheduled `SubscriptionExpiryService` downgrades lapsed PRO hourly.
- **Why OK for dev**: dev grants PRO via the admin set-tier endpoint; webhooks aren't needed locally.
- **Before prod**:
  - **Apps:** set `REVENUECAT_WEBHOOK_AUTH`, and the app must set RevenueCat `app_user_id` = our user UUID (or email). That's it — env-var ready.
  - **Web:** NOT built yet — needs a Mercado Pago/Stripe **create-checkout endpoint** + a provider-specific webhook adapter (signature verify + event→activate/cancel mapping). Pending the provider choice (PIX-recurring vs one-off).

## Relevance filter rollout — first telemetry CONSUMER (2026-06-12, in SHADOW)

The first thing that *reads* `notification_events`: a user's own DISMISSED/MUTED
signals suppress deals from their screen + digest (`DealFeedbackService` plugged
into `DealsService`). Rules: MUTED product → hidden everywhere for 180d;
DISMISSED → hidden for 14d (the (product, market) pair when the event carries
`marketCnpj`, the whole product when it doesn't). Per-user scope — one member's
dismissal never hides a deal from the rest of the household.

**Rollout + validation protocol (`RELEVANCE_MODE`, default `SHADOW`):**
1. **SHADOW (now)**: filter computed + logged (`relevance.shadow suppressed=N`),
   UX unchanged. Let it run while real users dismiss/mute things.
2. **Check the report**: `GET /api/v1/admin/notifications/relevance-report?days=30`.
   The decision metric is **regret** — taps/list-adds/conversions on a product
   the same user had recently dismissed/muted, i.e. engagement the filter would
   have prevented. `regretSavings` is the R$ of conversions it would have blocked.
3. **Flip ON** when regret ≈ 0 with meaningful signal volume (`suppression.signals`
   double digits+). Record the pre-flip report numbers.
4. **Compare after 2–4 weeks**: `dismissalRate` should fall, `tapThroughRate`
   hold or rise, `conversions`/`attributedSavings` not fall. Worse → flip back to
   SHADOW (one env var, no data loss — everything derives from the event log).

The report works identically in SHADOW and ON (computed purely from
`notification_events`), so before/after windows are directly comparable.

## Notification telemetry: foundation (Phase A) — first consumer shipped 2026-06-12 (see "Relevance filter rollout" above)
- **Now**: `POST /api/v1/notifications/events` + `NotificationEventService` write a `notification_events` row per user-feedback signal (push opened, deal viewed/tapped, added-to-list, dismissed/muted; plus server-only SENT/DELIVERED/CONVERTED for later). The `deal_surface_state` table (dedup-by-state-change for a future digest) is created but **unread/unwritten**. This is the **telemetry foundation for a future ranking/learning engine** — events are logged now even though no code reads them yet.
- **`metadata` is a JSON string in a TEXT column** (not jsonb) to stay H2-test-portable. If we ever need to query *inside* it (e.g. filter by `discountFraction`), migrate to `jsonb`.
- **LGPD**: `notification_events` is per-user behavioral data on the user's own account. The DB FK is `ON DELETE CASCADE` (account delete reaps it), but it is **NOT yet in `UserService.exportData`** — fold it into the LGPD data-export when that's extended (see the entry above). Flagged.
- **Before prod / before the learning phase**: the producers are now wired (Phase B screen, Phase C digest emits SENT, Phase D attribution emits CONVERTED — see below). The **consumer** that ranks/learns on this data is still TODO; revisit jsonb + export.
- **Telemetry loop status: COMPLETE (producer side).** Phase A (events + state tables) → B (deals screen) → C (digest emits SENT + upserts surface state) → D (savings attribution emits CONVERTED + realized R$). The north-star — **R$ saved attributable to notifications** — is now produced end-to-end and readable at `GET /users/me/savings`. Remaining: the ranking/learning consumer.

## Deals screen ("Ofertas pra você") — Phase B of the notifications overhaul (2026-06-09)
- **Now**: `GET /api/v1/deals` + `DealsService` compute the household's currently-relevant discounts on demand. For each product the household bought, it calls `PriceIndexService.bestMarkets` (k-anon + radius + watched logic reused as-is) and keeps the cheapest market that beats last-paid by the progressive `RelevanceThreshold.requiredDropFraction` bar (extracted out of `NotificationRuleEngine` so the engine and the deals screen share one definition). Ranked by savings desc, tie-broken by purchase frequency.
- **Query cost**: one history query for the household, then one `bestMarkets` lookup per distinct product bought (each a couple of small queries). On-demand single-user, so acceptable — but it's an **N-product fan-out** that will want batching once a household buys hundreds of distinct products or once the digest (Phase C) computes this for many users at once.
- **Phase C (digest) reuses this**: the same deal computation feeds the future digest push, which will dedup against `deal_surface_state` (created in Phase A, still unread) so it only re-pushes a deal when the discount/price meaningfully changes. Savings **attribution** (did the user actually buy it cheaper after we surfaced it?) is also a later phase.
- **Telemetry**: this endpoint emits none by design — the app self-reports `SCREEN_OPENED` / `DEAL_VIEWED` / `DEAL_TAPPED` via the Phase A events API.

## Daily deals digest — Phase C of the notifications overhaul (2026-06-09)
- **Now**: `DealsDigestScheduler` runs hourly (cron `0 0 * * * *`, America/Sao_Paulo). Per due user it calls `DealsService`, filters to **newsworthy** deals via `deal_surface_state` (created in Phase A, now read+written), and — if ≥1 — sends ONE `DEALS_DIGEST` push, upserts the state rows, and records a `SENT` `notification_events` row per surfaced deal. The hour each user is "due" comes from `DigestScheduleService` (override → modal shopping-hour−1h with ≥5 issued-at receipts → default 16). Preferences live on `users` (`digest_frequency`, `digest_send_hour`) and are edited via `GET/PUT /api/v1/users/me/digest-preferences`.
- **Newsworthy rule**: no prior state row (new) OR discount improved by ≥ +0.05 (5 p.p.) / crossed a stricter `RelevanceThreshold` step OR last surfaced before the collaborative lookback window. Standing unchanged deals are skipped.
- **1/day cap mechanism**: a `last_digest_sent_at TIMESTAMPTZ` column on `users` (chosen over scanning `notification_events`) — same-calendar-day (Sao Paulo) ⇒ skip. Hard cap, checked before computing deals.
- **Timezone assumption (v1)**: everything ("now", the due-hour math, the 1/day day boundary) is **America/Sao_Paulo** for all users. **Per-user timezone is a later refinement** — store the user's TZ and resolve "now"/send-hour per user. ~half a day once we capture TZ.
- **WEEKLY day**: WEEKLY digests only fire on **Thursday** (`DealsDigestScheduler.WEEKLY_DAY`). Arbitrary sensible default; make it user-configurable later if needed.
- **O(users × products) fan-out**: each due user triggers `DealsService`, which fans out one `bestMarkets` lookup per distinct product bought. For the hourly batch that's O(due-users × products). We keep it cheap by only processing the small slice of users actually due that hour and isolating each in its own `REQUIRES_NEW` tx (one user's failure is logged and skipped, never aborts the run). **Before scale**: batch/cache the deal computation across users (shared product→market index per run) and/or precompute deal candidates off the write path. Flagged.
- **`DEALS_DIGEST` is SYSTEM-scope** (server-emitted, not a user-toggleable rule) — governed purely by `digest_frequency`, so it is intentionally NOT auto-seeded as a default `NotificationRule`.

## Savings attribution — Phase D of the notifications overhaul (2026-06-09)
- **Now**: `SavingsAttributionService.attribute(receipt)` runs from `ReceiptService.confirm`, **after** the receipt is CONFIRMED + observations recorded, wrapped in a try/catch (`attributeSavings`) so it can **NEVER** break a confirm — any failure is logged (`attribution.failed`) and swallowed (best-effort analytics). For each non-excluded, product-linked item it looks for a `deal_surface_state` row for the (product, market=`cnpjEmitente`) of **any user in the buyer's household** where `converted_at IS NULL` and `last_surfaced_at` is within the **attribution window**. A match records a `CONVERTED` `notification_events` row + stamps `converted_at` so one surfacing is attributed at most once (a re-surface clears it). Surfaces the total via `GET /users/me/savings` (`SavingsService`).
- **Attribution window**: `economizaai.attribution.window-days` (default **14**), a dedicated property in `CollaborativeProperties.Attribution` (distinct from the 90-day collaborative lookback — attribution is about "did they buy *soon after* we nudged", a tighter window).
- **Savings formula** (counted only when **positive**): `(previousLastPaid − paidUnitPrice) × quantity`. `previousLastPaid` = the household's last paid unit price for that product on a confirmed receipt **issued strictly before** this one (the very receipt being attributed never counts as its own baseline — `findHouseholdHistoryForProduct` filtered to `issuedAt < receipt.issuedAt`). If there's **no prior purchase**, we fall back to the surfaced deal's `last_unit_price` baseline, but only when it's higher than paid; otherwise there's no provable savings and we skip.
- **Approximations / honesty**: attribution is **heuristic + correlational, not proven causation** — we credit a deal whenever the user bought within the window after we surfaced it, with no proof the nudge caused it. `previousLastPaid` uses the household's own last unit price (no pack-size/quantity normalization beyond per-unit), and the "no prior purchase" surface-baseline fallback is a community-median proxy, not what *they* used to pay. Good enough to track a directional north-star; not an accounting figure.
- **User-resolution choice**: a digest could go to **any** household member, so we attribute against the surface rows of **every user in the buyer's household** (`UserRepository.findAllByHouseholdId`), not only the buyer — but the `CONVERTED` event itself is recorded against `receipt.getUser()` (the actual buyer). Savings read-back (`GET /users/me/savings`) re-aggregates across the whole household, so per-user vs per-buyer attribution doesn't change the household total.
- **Storage**: realized savings live in a dedicated `notification_events.savings_amount NUMERIC(12,2)` column (added so the north-star sums in plain SQL) **and** are echoed into the JSON `metadata` for completeness. Migration `V44__savings_attribution.sql` adds that column + `deal_surface_state.converted_at`.
- **This completes the notifications-overhaul telemetry loop** (A→D). See the Phase A entry above — the producer side is done; the ranking/learning consumer is the remaining work.

## Write-path is now explicit-alerts-only; PROMO_*/CHEAPER_MARKET toggles are vestigial (2026-06-09)
- **Now**: `NotificationRuleEngine.evaluate` (on receipt confirm) fires **only** the user's explicit `PRICE_DROP` alerts. The discovery defaults (`PROMO_PERSONAL` / `PROMO_COMMUNITY` / `CHEAPER_MARKET`) that used to fire in real time were removed from the write path — that function now lives entirely in the daily deals digest (`DealsService` / `DealsDigestScheduler`). The dead repo methods (`findActiveDefaultRuleOwnersWhoBought` both overloads + `ProductRuleOwner`; `findLastPaidHistoryForProductByHouseholds` + `HouseholdProductPrice`) were deleted with them.
- **Vestigial toggles**: the `PROMO_PERSONAL` / `PROMO_COMMUNITY` / `CHEAPER_MARKET` `NotificationType` values + their auto-seeded default `NotificationRule` toggles still exist (FE still shows them), but **nothing reads those per-type toggles anymore** — the digest computes discovery from `DealsService` and is governed by `digest_frequency`, not by these rules.
- **Open refinement**: decide whether the digest should **respect** those per-type toggles (e.g. a user who disabled `CHEAPER_MARKET` shouldn't see cheaper-market deals in the digest), or whether to retire the toggles entirely. Until then they're inert UI.

---

## Social login (Google/Apple): audience check skipped until client IDs are set
- **Now**: `POST /api/v1/auth/google` and `/auth/apple` verify the provider token's signature (RS256 via the provider JWKS), issuer, and expiry. The **audience (`aud`) check only runs when `GOOGLE_OAUTH_CLIENT_IDS` / `APPLE_OAUTH_CLIENT_IDS` are configured**; when empty (dev default) it's skipped with a WARN.
- **Why OK for dev**: signature+issuer+expiry still prove the token is a genuine, current Google/Apple token. Fine for local testing.
- **Before prod**: set both env vars (comma-separated client IDs the mobile app may present as `aud`) so a token minted for a *different* app can't be replayed against us. Google: the OAuth client IDs per platform (Web/iOS/Android) from Google Cloud Console. Apple: the app bundle ID (native) and/or Service ID (web). Team ID + Sign-in-with-Apple key are NOT needed for this identity-token flow (only for the auth-code/refresh flow). JWKS are fetched + cached 1h in-memory (`CachingJwksKeySource`).

## Notification channels: SMS + WhatsApp via Twilio (env-gated); Alexa still a stub; email off by default
- **Now**: the channel framework supports `PUSH` (Expo → FCM/APNs, working), `EMAIL` (SMTP via `EmailDispatcher`, gated by `NOTIFICATIONS_EMAIL_ENABLED`, off by default until SMTP creds are set), `SMS` + `WHATSAPP` (Twilio's Messages API via `TwilioMessageClient`, off by default), and `ALEXA` which remains **structure-only** (`AlexaDispatcher` extends `StubChannelDispatcher` — logs and records a "not implemented" failure on the audit row).
- **SMS/WhatsApp are now implemented** (`SmsDispatcher`/`WhatsAppDispatcher`): they deliver only when Twilio is configured **AND** the target user has a `phone_verified` phone. Otherwise they degrade gracefully — record a `twilio_not_configured` / `phone_not_verified` failure on the notification audit row, never throw. The phone OTP flow (`PATCH /users/me/phone`, `POST /users/me/phone/verify`) sends the 6-digit OTP over SMS via Twilio; when Twilio is unconfigured (dev) it falls back to a `[DEV-MODE] phone OTP for {maskedPhone} = {code}` WARN log so dev can still verify.
- **Env vars** (all blank/off by default): `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_SMS` (SMS sender, E.164), `TWILIO_FROM_WHATSAPP` (Twilio WhatsApp sender number, no `whatsapp:` prefix). `isConfigured(whatsApp)` is true only when sid + token + the relevant From are all set. Bound in `application.yaml` under `economizaai.notifications.twilio`.
- **Swappable**: `TwilioMessageClient` is the only seam — swap it (or its `post()` method) for another SMS/WhatsApp provider without touching the dispatchers.
- **Why OK for dev**: graceful degradation — an unconfigured/unverified channel just produces a failed audit row, never an exception. Push is the primary channel and works end-to-end.
- **Before prod**: set the `TWILIO_*` env vars + register a Twilio WhatsApp sender; implement `deliver()` for the remaining Alexa stub (Proactive Events, skill grant per user); flip `EMAIL` on once Render has SMTP env vars.

## Community-default notifications: backfill + owners-query batched — MOSTLY RESOLVED (2026-06-09)
- **Fixed**: a startup `NotificationDefaultsBackfill` (`ApplicationRunner`) now proactively materializes missing default rule rows for **all existing users** (idempotent bulk `INSERT...SELECT`, one query per default type), so community notifications no longer wait for the user to open settings. (Lazy seeding on `GET /notification-rules` + absent-as-enabled still apply as a fallback.) Also `NotificationRuleEngine` community defaults now run **2 queries per receipt** (one `IN` query per type over all products) instead of 2·P — the per-product `findActiveDefaultRuleOwnersWhoBought` N+1.
- **Also fixed (2026-06-09)**: the per-candidate last-paid lookup in `fireCheaperMarket` is now batched — one `findLastPaidHistoryForProductByHouseholds(productId, householdIds)` query per product builds a `Map<householdId, lastPaid>`, replacing the former one-query-per-rule. So the community write path is now bounded (no per-rule N+1).
- **Still open (minor)**: the 24h cooldown is per default-rule (coarse, one community ping/type/day/user) — revisit if users want per-product granularity.

---

## Merchant segment classification depends on BrasilAPI (best-effort)
- **Now (built)**: `MarketLocation.segment` (UNKNOWN/SUPERMARKET/PHARMACY/OTHER) is verified from the CNPJ's **CNAE** via BrasilAPI (`/api/cnpj/v1/{cnpj}`, free, no auth) — `CnpjActivityClient` + `MarketLocationService.classifyPendingSegments()` (scheduled batch, decoupled from confirm, attempt-capped, name-pattern fallback in `MerchantClassifier`). When a market resolves to PHARMACY, OTHER products bought there are backfilled to the HEALTH category. **Best-effort by design**: a lookup failure leaves the segment UNKNOWN and categorization proceeds normally on the name guess (or just the dictionary) — never blocks ingestion.
- **Why OK for dev**: external dependency is fully optional (toggle `MERCHANT_CLASSIFY_ENABLED=false`), retried, and degrades gracefully. The name fallback covers the big chains immediately, so the CNAE layer is pure accuracy upside.
- **Watch before prod**: BrasilAPI has no SLA / rate limits we control — if it gets flaky at volume, consider (a) a paid CNPJ provider or (b) caching/import a CNAE dataset. Also the first receipt from a brand-new oddly-named pharmacy categorizes on the name guess until the async batch resolves the segment (then the backfill corrects it). Headless/cron envs without outbound internet will just leave segments UNKNOWN.
- `MERCHANT`-sourced categories are excluded from ML training (`TRUSTED_SOURCES`) and locked against admin recategorization downgrades — intentional, keep it that way.

---

## Captcha solver — seam ready, dormant (MS wired, 2026-06-12)
- **Now**: captcha-gated states have a full ingestion path that's switched OFF until a solver provider is configured. `MsDfePortalAdapter` claims MS (`www.dfe.ms.gov.br`, reCAPTCHA v2): GET consult page → detect captcha + extract sitekey → `CaptchaSolver.solveRecaptchaV2` → resubmit → parse with the shared `ResponsiveDanfeParser`. With the default `provider=none` (`NoopCaptchaSolver`), an MS receipt fails fast with **503 `receipt.captcha.unavailable`** ("coming soon"), not a confusing parse error.
- **MS is ON via CapSolver**: `CAPTCHA_PROVIDER=capsolver` + `CAPTCHA_API_KEY=<key>` on the server (verified live 2026-07-02). `TwoCaptchaSolver` also exists as an alternative (`CAPTCHA_PROVIDER=twocaptcha`; `CAPTCHA_BASE_URL` applies only to it — CapSolver's URL is fixed). A provider with a different HTTP API = one new class implementing `CaptchaSolver` with `@ConditionalOnProperty(...havingValue="<name>")`; nothing else changes.
- **Post-captcha layout VERIFIED (2026-06-12)**: a real MS DANFE (C.VALE/Caarapó, saved from a browser after the user solved the captcha) parses correctly — `RealMsFixtureTest` locks all 9 items + R$61,79 total. MS uses `span.txtTit` (not `txtTit2`), 7-digit internal codes (→ ean null), single IBPT total (→ tax null); all handled by the shared parser. So the only thing still **VERIFY ON FIRST REAL SOLVE** is the HTTP transport that exchanges a solved token for the DANFE: `MsDfePortalAdapter.consultUrl` params + `fetchAuthorizedDanfe`'s resubmit shape (isolated for a quick fix — the page is reached via a form POST from the captcha page; capture it in DevTools Network during the first real solve). Cost when live: ~US$1-3 / 1000 solves.
- **Other captcha states**: each has its own portal, so they need their own adapter (reusing `CaptchaSolver` + `ResponsiveDanfeParser`); `economizaai.ingestion.sefaz.captcha.states` lists which UFs the MS adapter claims (MS only for now).

## Experimental all-states fallback chain — LIVE (2026-07-16), learning on demand
- **Now**: every UF without a dedicated adapter is gap-filled with `GenericQrPortalAdapter`:
  GET the QR's own portal URL (SSRF-bounded to `gov.br` hosts) → shared `ResponsiveDanfeParser`
  → Infosimples rescue on fetch OR parse failure (when enabled). All 27 UFs accepted at submit.
  Kill-switch: `SEFAZ_EXPERIMENTAL_ENABLED=false` restores the old fail-fast 400.
- **Learning loop**: every layer attempt on an experimental UF is a `state_ingestion_attempts`
  row (strategy, outcome, qr_host, evidence). `GET /admin/state-coverage` is the per-UF map.
  Admin inbox gets: (a) first-ever success per UF ("capture fixture, promote to svrs.states"),
  (b) total chain failure with chave + QR URL + portal snippet, deduped 1/UF/day.
- **Promotion path**: when a UF succeeds via QR_PORTAL and a real user confirms the items,
  add it to `economizaai.ingestion.sefaz.svrs.states` + its host to `allowed-url-hosts` — it
  becomes VERIFIED (dedicated adapter, no experimental telemetry).
- **Regression watch (verified states)**: we can't stop a supported portal changing its DANFE
  format, but we detect it. Every VERIFIED-state PARSE failure is recorded (strategy
  `VERIFIED_ADAPTER`); when `regression.min-failures` (default 5) parse-failures hit the same
  UF within `regression.window-hours` (default 6h), the admin gets ONE email/UF/day with the
  raw HTML + chave + parser reason — enough to fix the adapter or write a new one. Tuned to
  ignore single bad-scan/aged-out noise; raise the threshold if a low-traffic state false-alarms.
  Honest limit: it can't perfectly tell "format changed" from "burst of bad scans", so it's a
  threshold heuristic, not a proof — but a real format change fails EVERY scan, so it trips fast.
- **Captcha walls get ONE best-effort solve** (reCAPTCHA v2 / Turnstile; token resubmitted
  as a query param — a guess). Charged per solved token (~R$0.03) even when the portal
  rejects it, hence a single attempt, then Infosimples. Failure evidence (captcha type,
  sitekey, page snippet, HTTP status) rides the telemetry + admin email.
- **MG needs captcha + JSF postback** (`portalsped.fazenda.mg.gov.br`): PrimeFaces/ViewState
  portal, reCAPTCHA, DANFE delivered via an AJAX postback — needs a real post-solve fixture
  (DevTools capture) before an adapter can be built without guessing component IDs. Infosimples
  covers it meanwhile.

### JS-anti-bot-walled portals (RJ / F5 BIG-IP) — parser-ready, needs an unlocker fetch
- **Problem**: some SEFAZ portals sit behind a client-side **JavaScript anti-bot challenge**.
  Our fetch layer is a plain `RestClient` GET with **no JS engine**, so it never gets past the
  wall to the DANFE. Confirmed case: **RJ** (`consultadfe.fazenda.rj.gov.br`) runs **F5 BIG-IP
  ASM** — fingerprinted by the `TS0…`/`TSPD` + `BIGipServer…` cookies it sets and the obfuscated
  JS it serves instead of the receipt. A real browser solves the challenge invisibly (verified —
  the user opened the URL and saw the full receipt); our server gets only the challenge page.
  (MG's `ERR_CONNECTION_RESET` may be a related network-level bot defense; unconfirmed.)
- **NOT a parser problem**: RJ renders the SAME responsive-DANFE layout the shared parser
  already handles — proven by `RealRioDeJaneiroFixtureTest` on a real captured fixture (market,
  R$65,00 total, item all extracted). So once something gets past the wall, `ResponsiveDanfeParser`
  works UNCHANGED — zero parsing work to onboard RJ (and likely other F5/Imperva/Cloudflare states).
- **Works TODAY via Infosimples** (`INFOSIMPLES_ENABLED=true`): the paid by-chave fallback
  handles RJ fine (~R$0.24/nota). So nothing is broken — this note is about making it FREE-ish.
- **Solution options** (to move JS-walled states off the paid fallback):
  1. **Web-unlocker API** (RECOMMENDED — Bright Data Unlocker / ZenRows / ScrapingBee / Scrapfly).
     Send the URL, they run the JS challenge + rotating proxies server-side, return the HTML.
     Fits our stack perfectly: it's one HTTP call, drops in as a chain layer exactly where
     Infosimples sits, metered through `PaidApiGuardService`. ~R$0.008–0.015/nota (≈US$1.5/1k) —
     roughly **30× cheaper than Infosimples**, and covers ALL JS-walled vendors (F5/Imperva/
     Cloudflare/DataDome), not just RJ. Downside: a new paid vendor with a ~US$50–70/mo floor
     (or pay-as-you-go, no floor but higher per-call).
  2. **Self-hosted headless browser** (Playwright/Chromium): runs the challenge in-house.
     REJECTED for now — Chromium needs ~300–500 MB + system libs on top of the JVM's ~1 GB;
     tight on the Render Standard 2 GB box, big image, slow cold start, fragile.
  3. **Reverse-engineer the F5 TS-cookie generator in Java**: brittle, high maintenance (F5
     rotates the challenge JS). REJECTED.
- **Recommendation / when to build**: DON'T build yet — Infosimples covers RJ today. Add the
  unlocker layer when `/admin/state-coverage` shows JS-walled states crossing ~1–2k notas/month
  (that's roughly the break-even vs a US$69/mo plan at ~R$0.23 saved/nota). Build = one
  `UnlockerFetchClient` wired before Infosimples for the affected UFs, metered like the other
  paid layers. Parser side is already done + fixture-guarded.
- Refs: [Scrapfly F5 bypass](https://scrapfly.io/bypass/f5), [ZenRows F5 (2026)](https://www.zenrows.com/blog/bypass-f5),
  [Bright Data Web Unlocker pricing](https://brightdata.com/pricing/web-unlocker).
- **Watch before prod scale**: captcha-walled portals (AC, RN, TO…) that reject the guessed
  resubmit burn one solve + one Infosimples query per nota — if such a state gets popular,
  build its adapter (the telemetry tells you which).

## Multi-state SEFAZ coverage = 2 / 27 verified (PR added 2026-06-12; MS ready, needs solver)
- **Now**: RS + **PR** have working end-to-end ingestion. PR has its own portal
  (`www.fazenda.pr.gov.br/nfce/qrcode`) but renders the SAME responsive-DANFE
  layout as SVRS, so the shared adapter parses it unchanged — verified with a
  real RaiaDrogasil/Curitiba fixture (`fixtures/sefaz/pr/`). The QR carries the
  full portal URL; bare-chave fallback picks the portal by the chave's UF.
  PR caveat learned: drugstores print merchant-internal item codes (6-7 digits)
  in the EAN slot → codes < 8 digits are no longer stored as EANs (description
  matching takes over). Other UFs still return `UnsupportedStateException`.
- **What we know**: empirical probe of all 27 portals on 2026-05-06 documented in `docs/MULTI_STATE_RECON.md`. ~10 UFs are server-rendered + simple GET (Tier 1 — quick to add once we have real chaves). 3 UFs use JSF/ASP.NET ViewState (Tier 2, more code). 5 UFs gate behind captcha (Tier 4, needs 2Captcha or similar). 1-2 are SPAs requiring a headless browser.
- **Bottleneck**: not code, **data**. Adapters can't be written safely against synthetic chaves; each UF needs 1 real, recent (≤ 3 months) cupom HTML to develop selectors against. See `docs/MULTI_STATE_RECON.md` for the population coverage analysis (Tier 1 + Tier 2 = ~54% of Brazil) and recommended implementation order.
- **Fix when ready**: collect chaves via e-CAC, state cashback portals, or contacts in each state; save HTML in `src/test/resources/fixtures/sefaz/<uf>/`; implement adapter following `SvrsSharedPortalAdapter` template.

---

## Storage / infrastructure

### Profile picture storage = local disk (now on a persistent volume)
- **Now**: `LocalDiskProfilePictureStorage` writes to `PROFILE_PICTURE_DIR` (default `/tmp/economizai/profile-pics`). On the self-hosted server the app sets it to `/data/profile-pics`, backed by the named Docker volume `economizai-profilepics` (compose `app` service) — same durability as `economizai-pgdata`. Bytes served via `GET /users/me/profile-picture`.
- **History**: the default `/tmp` is **inside the container** and wiped on every `--build` redeploy. Since every push auto-deploys (rebuild), uploaded pics vanished while the DB key dangled → `read()` fell back to the initials avatar = pics "disappearing". Fixed 2026-06-07 by mounting a volume + pointing the env var at it.
- **Why still NOT prod-final**: local disk can't scale to multiple instances and isn't backed up off-box.
- **Fix before prod**: implement an `S3ProfilePictureStorage` (or Cloudinary), wire via the `ProfilePictureStorage` interface, switch via env var. ~2 hr.

### Push notifications = Expo Push Service (works in dev with no setup)
- **Now**: `PushDispatcher` calls the Expo Push HTTP API (`https://exp.host/--/api/v2/push/send`). The FE (React Native + Expo) registers an Expo Push Token via `PUT /api/v1/users/me/push-token`; the backend POSTs to Expo, which routes to FCM (Android) or APNs (iOS).
- **Why this stack and not firebase-admin**: the FE generates Expo tokens (`ExponentPushToken[...]`), which can't be sent through raw FCM. Expo also removes the need for service-account JSON, native config and SDK init.
- **Dev**: works out of the box — no env var, no project setup. Push reaches the device via Expo Go.
- **Prod (optional)**: set `EXPO_ACCESS_TOKEN` env var with a token from https://expo.dev → Account → Access Tokens. Raises rate limits and feeds the Expo analytics dashboard. Without it, sends still work but at lower throughput.
- **Prod (iOS)**: publishing to the App Store requires an Apple Developer Program membership ($99/year — Apple's fee, not Expo's) and uploading the APNs auth key to Expo (Expo handles the rest of the iOS push plumbing).

### SMTP email = disabled (impacts notifications + auth flows)
- **Now**: two paths consume SMTP:
  1. `EmailDispatcher` (notification channel) — `@ConditionalOnProperty(NOTIFICATIONS_EMAIL_ENABLED=true)`, defaults off, falls back to PUSH/NONE when off.
  2. `AuthEmailSender` (password reset + email verification) — always loaded; if SMTP isn't configured, **logs the link with `[DEV-MODE]` prefix** instead of sending. The reset/verify endpoints still return 204, so the FE flow works in dev — the developer copies the token from server logs.
- **Why OK for dev**: no SMTP creds, FE end-to-end testing still works (manually grab the link).
- **Why NOT OK for prod**: real users won't see a `[DEV-MODE]` log line. They get NO password-reset / verification email at all.
- **Fix before prod**: set SMTP creds in env (`SMTP_HOST/PORT/USERNAME/PASSWORD`) and flip `NOTIFICATIONS_EMAIL_ENABLED=true`. Recommend SES, Mailgun, or Postmark — Gmail SMTP rate-limits hard. ~30 min.

---

## Hosting / access — self-hosted (replaced Render)

> **2026-06-03:** Render's free Postgres was **suspended** (free DBs get reaped),
> which took the web service down. We moved the dev server **off Render to a
> self-hosted machine**. The old `https://economiz-ai.onrender.com` URL is dead.

### How to run it
One file does both jobs via Compose profiles — `docker-compose.yml` at repo root.

- **Backend-dev machine** (iterate fast — Postgres in Docker, app via Maven):
  ```bash
  docker compose up -d db          # Postgres only on :5432
  ./mvnw spring-boot:run           # app on :8080
  ```
- **Server machine** (full stack for everyone):
  ```bash
  docker compose --profile server up -d --build   # Postgres + app
  docker compose logs -f app                       # watch boot / Flyway
  ```

Defaults mirror `application.yaml` localhost fallbacks, so it runs with no `.env`.
Drop a `.env` (gitignored) beside the compose file to override `DB_USERNAME`,
`DB_PASSWORD`, `JWT_SECRET` (`openssl rand -hex 64`), `CORS_ORIGINS`.
`ddl-auto: none` + Flyway → a **fresh empty volume rebuilds the whole schema** on
first boot. No data to restore (Render data is gone).

### How to access it
- **Base URL (API):** `http://<server-LAN-IP>:8080/api/v1`
- **Health:** `http://<server-LAN-IP>:8080/actuator/health` → `{"status":"UP"}`
- **Swagger:** `http://<server-LAN-IP>:8080/swagger-ui/index.html`
- Host `:8080` maps to the container's `:10000` (Dockerfile port). Postgres is on `:5432`.
- The FE machine must be on the same LAN; the server's OS firewall must allow `:8080`.
  Add the FE origin (and any public URL) to `CORS_ORIGINS`.
- For off-LAN / public access, front it with a Cloudflare Tunnel or ngrok → add that
  https URL to `CORS_ORIGINS`.

### Ops
- Stop / restart / wipe: `docker compose --profile server down` / `restart` /
  `down -v` (the `-v` drops the `economizai-pgdata` volume → fresh schema next boot).
- Services use `restart: unless-stopped`; ensure Docker starts on machine boot so the
  stack returns after a reboot. Data persists in the named volume.

### Windows server machine (2026-06-05) — LIVE
The self-hosted dev server runs on a **Windows 11** box, **full stack in Docker**,
never-sleep on AC, LAN-only. Static LAN IP: **`192.168.68.108`**.

- **API base:** `http://192.168.68.108:8080/api/v1`
- **Health:** `http://192.168.68.108:8080/actuator/health` → `{"status":"UP"}`
- **Swagger:** `http://192.168.68.108:8080/swagger-ui/index.html`

**Remote access:** a **Cloudflare quick-tunnel** exposes the API over HTTPS from any
network. The live public URL is always in `current-tunnel-url.txt` at repo root.
⚠️ The free `trycloudflare` URL **changes on every restart** — `start-tunnel.ps1`
auto-updates `.env` CORS and restarts the app each time, but the FE must read the new
URL from `current-tunnel-url.txt`. Upgrade to a **named tunnel** (stable URL) once a
domain is on Cloudflare.

Helper scripts at repo root (run each in an **Administrator** PowerShell once):
- `setup-firewall.ps1` — inbound allow rule, TCP 8080, Private profile.
- `set-static-ip.ps1` — pins the Wi-Fi adapter to `192.168.68.108/24` (gw `192.168.68.1`).
  Revert to DHCP: `netsh interface ip set address name="Wi-Fi" source=dhcp`.
- `make-always-on.ps1` — power (never sleep), Docker auto-start, container restart,
  stack watchdog (startup + every 5 min via `stack-watchdog.ps1`).
- `enable-autologin.ps1` — auto-login this local account so reboots are unattended
  (flips Win11 Hello-only flag, sets Winlogon keys). Box is home-only/non-critical so
  the stored-password tradeoff is accepted; BitLocker skipped.
- `start-tunnel.ps1` — launches cloudflared, captures URL, syncs CORS, restarts app,
  publishes URL to `current-tunnel-url.txt`. Runtime files (`*.log`, `current-tunnel-url.txt`)
  are gitignored.
- `setup-tunnel-autostart.ps1` — Scheduled Task to keep the tunnel up at logon.

**Logs / debugging:**
- **Dozzle** (live web UI): `http://192.168.68.108:9999` (LAN) — view/search/follow
  container logs in the browser, filtered to `economizai-*`. Runs as the compose `logs`
  service. (Not exposed via the public tunnel — LAN only.)
- **`logs.ps1`** helper: `.\logs.ps1` (tail+follow app), `-Errors` (WARN/ERROR only),
  `-Grep rcpt=xxx` (filter by req/rcpt/user/item id), `-Db` (database), `-Save` (snapshot
  to `C:\economizai-data\logs`), `-Since 30m`. Logs use the MDC pattern
  `req= user= rcpt= item=` so grep is powerful.
- **Data dir:** all runtime data (logs, db backups, images) lives in
  `C:\economizai-data` (override: `ECONOMIZAI_DATA_ROOT`) — OUTSIDE the project tree /
  runner checkout, so nothing is committed or OneDrive-synced.
- **File history:** `C:\economizai-data\logs\app-YYYY-MM-DD.log` (+ db), 14-day retention.
  Written daily 02:55 by the "economizai - daily log save" task (`setup-logsave-schedule.ps1`).
- **Disk safety:** Docker `json-file` log caps in compose (app 20MBx10, db 10MBx3) so a
  24/7 box can't fill the disk.

**Gotchas learned setting this up (so we don't relive them):**
- **`postgres:18` changed its data-dir convention.** Mount the volume at
  `/var/lib/postgresql` (the parent), NOT `/var/lib/postgresql/data` — PG18 sees the
  old path as an "unused mount/volume" and `initdb` refuses to run, leaving the
  healthcheck stuck `unhealthy`. Fixed in `docker-compose.yml`.
- **Orphaned Docker processes wedge the engine.** Symptom: Docker Desktop dialog
  "Cannot start server … open `\\.\pipe\dockerExtensionManagerAPI`: Access is denied",
  and `dockerd` runs but never opens its socket. Cause: a leftover `com.docker.extensions`
  (or duplicate `com.docker.backend`) from a prior start still holds the pipe. Fix: kill
  ALL `Docker Desktop` / `com.docker.*` / `docker-ai` processes, `wsl --shutdown`, then
  start a single clean instance.
- **`docker context` resets to `default` (dead `npipe:docker_engine`) on each Docker
  restart.** If `docker` commands hang, run `docker context use desktop-linux`.
- **WSL re-corrupted itself once** (`REGDB_E_CLASSNOTREG`) and self-repaired via
  `wsl --update`. If it recurs and a repair doesn't stick → `winget install Microsoft.WSL`.
- **Docker Desktop didn't auto-start the engine after a reboot (2026-06-06).** The GUI
  launched at logon but came up as a white/hung window; `com.docker.service` was Stopped
  (StartType=Manual) and stale `com.docker.backend` processes from boot held the pipe.
  The old "start Docker engine" task just ran `Docker Desktop.exe` once with no retry — a
  hung window counts as "running", so it never recovered. **Fixes applied:** (1) service
  set to **Automatic** start; (2) the logon task now runs **`start-docker-wait.ps1`**, a
  wait-for-healthy wrapper that polls `docker version` and relaunches a CLEAN instance (kill
  all docker procs + `wsl --shutdown` + restart service) up to 4 times. Recovery actions are
  logged to `C:\economizai-data\logs\docker-recovery.log`. Manual recovery if it ever wedges again: just run
  `start-docker-wait.ps1` (elevated, so it can kill the protected processes).

**Known weak spots (revisit):**
- **Mesh/extender network**: this box roamed `192.168.0.x` → `192.168.68.x` once. The
  Windows static IP is tied to the `192.168.68.x` node — if it roams to the other node it
  goes offline until reverted. Durable fix: **wired ethernet** or a **router DHCP
  reservation** by MAC `44-AF-28-2B-02-A5`.
- **Auto-start needs a user session**: Docker Desktop's WSL backend won't run with nobody
  logged in. `setup-autostart.ps1` triggers at logon, so after an unattended reboot the
  server is down until someone logs in. For true headless recovery, also enable auto-login
  (`netplwiz`) — tradeoff: stored password, anyone with physical access gets in.

### Before prod (whenever that comes)
- A self-hosted box on a home connection isn't a prod target (uptime, dynamic IP, TLS).
  For real users: managed Postgres that doesn't expire (Neon / Supabase free, or paid)
  + a proper host (Fly.io / Railway / paid Render / a VPS). Document the chosen path.

---

## Data correctness

### ML categorization gated OFF (dictionary-only for now)
- **Now**: `economizaai.ml.category-apply-enabled=false`. The cascade applies dictionary entries only; the ML model is confidently wrong at current data volume (~hundreds of products), so its predictions aren't written. It's still trained + measured (shadow) via `/categorizer/benchmark`.
- **Why OK for dev**: dictionary-only is deterministic and currently 100% on the golden set; uncategorized is better than confidently-wrong.
- **Fix / revisit**: once `mlCategoryAccuracyPct` (shadow) is consistently high — after the catalog has thousands of trusted labels — flip `ML_CATEGORY_APPLY_ENABLED=true` and watch the benchmark. Track via `/categorizer/quality/history`.

### User corrections are global (should be per-household) — see HELP.md "Planned"
- **Now**: `PATCH /products/{id}` mutates the shared canonical product, but it IS
  **ADMIN-gated** in `SecurityConfig` (the interim fix is in place).
- **Fix before real multi-user volume**: household-scoped overrides + corrections-as-votes (design in HELP.md) so regular users can correct their own view again.

### Cross-household chave dedup breaks if a receipt ever has no chave
- **Now**: the same-chave double-count guard is solid for the only ingestion path we have (QR scan → 44-digit chave always present). Same household, same chave: CONFIRMED → 409, non-final → replaced. Different households, same chave: the second household keeps its own personal receipt, but `PriceIndexService.recordContributions` skips the community write (`existsContributionForChaveFromOtherHousehold`) and logs `price_index.write.skipped reason=duplicate_chave_other_household`. This is exactly the desired behavior.
- **The gap**: that guard is wrapped in `if (receipt.getChaveAcesso() != null)`. A receipt ingested **without** a chave would bypass dedup entirely and could double-count in the community index. No such path exists today.
- **Revisit when** we add any non-QR ingestion (manual 44-digit entry, photo/OCR fallback — backlog #21, or manual item entry). At that point either require a chave before a receipt can contribute to the community index, or add a content-hash fallback dedup (market CNPJ + issuedAt + item set) for chave-less receipts.

### Receipt discounts: tracked and reported alongside gross spend, never distributed
- **Now**: we stopped distributing the receipt-level discount across item prices (the old `reconcileItemsToTotal` proportional rateio — deleted). Item `unitPrice`/`totalPrice` are kept gross-as-printed so the collaborative price index records the real shelf price. The "Descontos R$" line is parsed into `receipts.discount_total` (V46) and exposed as `discountTotal` on receipt responses.
- **Consumed by insights/dashboard (gross + discount, FE nets it)**: spend totals stay gross; the discount is reported alongside so the FE computes net (`total − discount`). `SpendInsightsResponse.totalDiscount` + per-bucket `discount` on byMonth/byWeek/byMarket; `InsightsQueryResponse.summary.totalDiscount` + per-bucket `discount` for receipt-level groupings (DAY/WEEK/MONTH/YEAR/MARKET/CHAIN); `SpendSnapshot.discount` on the dashboard.
- **No per-bucket discount for CATEGORY/PRODUCT**: a receipt-level discount can't be attributed to one category/product without rateio (out of scope). Those buckets carry `discount=null`; only the period-level `totalDiscount` is provided. Per-item discount attribution is impossible on SVRS anyway (the portal only gives the receipt-level total).
- **Implementation note**: discount aggregations query `Receipt` directly (no item join) so `discountTotal` is counted once per receipt; `/insights/query` uses a DISTINCT-(receiptId, …) select so the item-join doesn't multiply it.
- **Why OK for dev**: the price index (what matters most) is honest, spend breakdowns stay internally consistent (gross everywhere), and the FE can show "gastou R$ X · R$ Y em descontos". Still no use of the discount beyond reporting (e.g. a future "markets with the biggest discounts").

---

## Billing / subscriptions

### No payment provider wired — PRO is granted manually / via webhook only
- **Now**: the PRO tier is fully **enforced** (gates in `SubscriptionGateService`, 402 on block), and a user can be made PRO two ways: (a) admin `PUT /api/v1/admin/users/{id}/subscription-tier {"tier":"PRO"}`, or (b) the provider-agnostic webhook `POST /api/v1/webhooks/subscription`. There is **no actual payment collection** — no Stripe/Mercado Pago/Pix integration, no checkout, no self-serve upgrade page.
- **Why OK for dev**: lets us test the entire gated experience (and demo PRO) without a payment processor or merchant account. The webhook **fails closed** when its secret is blank (constant-time compare), so an unset `BILLING_WEBHOOK_SECRET` means the route just rejects everything.
- **Before prod — to enable paid subscriptions**:
  1. **Pick a provider** (Stripe Brasil, Mercado Pago, or Pagar.me + Pix).
  2. **Get API keys** (publishable + secret) and create the product/price (R$9.90/mo per MONETIZATION §1).
  3. **Set `BILLING_WEBHOOK_SECRET`** (env → `economizaai.billing.webhook-secret`) to a strong random value so the webhook rejects unsigned calls (401).
  4. **Point the provider's webhook** at `POST /api/v1/webhooks/subscription` and map its event payload onto our `{ userEmail, action: ACTIVATE|CANCEL, provider, providerRef, currentPeriodEnd }` shape, sending the shared secret in `X-Webhook-Secret`. (If the provider signs with HMAC instead of a static header, add a small verify step in `SubscriptionWebhookController` for that scheme.)
  5. **Build the checkout / self-serve upgrade flow** in the app + a `PUT /users/me/subscription` (or hosted-checkout redirect). Currently only admins can flip the tier from inside the app.
  6. Decide period-end handling: a scheduled job should expire `current_period_end` PRO subs back to FREE if the provider stops sending renewals (not built).

---

## Monitoring / ops

### ~~`/actuator/prometheus` is public (no auth)~~ — RESOLVED (2026-06-11)
- **Fixed**: `/actuator/prometheus` now sits behind a dedicated HTTP Basic security chain (`SecurityConfig.metricsSecurityFilterChain`, `@Order(1)`), with the credential from `economizaai.metrics.username/password` (`METRICS_USERNAME`/`METRICS_PASSWORD`). **Fail-closed**: blank password ⇒ no user ⇒ every request 401, so it's never exposed unauthenticated. `/actuator/health` stays public on the main chain for UptimeRobot.
- **Remaining (infra, not code)**: nothing scrapes it yet — stand up Prometheus + Grafana on the box and set `METRICS_PASSWORD`. Steps in INFRASTRUCTURE.md → Monitoring → Metrics.

### Logs go to stdout + host file — no central aggregation
- **Now**: self-hosted box. App logs to a bind-mounted host file (`/var/log/economizai/app.log`, rotated 50MB×10) and Docker json-file; Dozzle gives a live web view. No search/retention across restarts beyond the rotated files.
- **Fix before serious ops**: ship logs to BetterStack, Loki, or Papertrail. ~1 hr.

### Read caches (dashboard / insights) are in-process
- **Now**: `dashboard` (2 min) + `insightsSpend` (5 min) are cached in-memory via Caffeine (`CachingConfig`), keyed by household with a per-household generation counter (`HouseholdCacheGen`) bumped on receipt mutations. ETags via `ShallowEtagHeaderFilter`.
- **Why OK for dev**: single instance, so one in-process cache is the whole truth; generation counter resets on restart (at worst one recompute).
- **Why NOT OK at scale**: multi-instance would give each node its own cache + its own generation map → a mutation on node A wouldn't invalidate node B's cache (stale reads up to TTL). The ETag filter is shallow (still computes the body), so no compute savings either.
- **Fix before multi-instance**: move the cache + generation counter to a shared store (Redis). ~half a day. Single instance is fine until then.

---

## Last-checked: 2026-09-22

When you take care of an item above, **delete it from this file** instead
of marking it done — keep the file lean so what remains is what's
actually outstanding.
