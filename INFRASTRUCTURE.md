# economizai — Infrastructure

**The app runs on Render.** Two web services (one repo, branch-per-environment)
plus a managed Postgres. Step-by-step setup runbook: [`RENDER_SETUP.md`](./RENDER_SETUP.md).
Bird's-eye map of the WHOLE product (mobile, web, admin, landing, e-mail, costs):
[`docs/INFRA_MAP.md`](./docs/INFRA_MAP.md).

> The old self-hosted Windows box was **RETIRED** on 2026-09-22 and its scripts were removed
> from the repo on 2026-11-11 — a short history note is at the very bottom.

## Current reality (Render) — clean dev/prod split (cutover 2026-09-22)

| Env | Service | Branch | Deploys when | Domain / URL | DB |
|---|---|---|---|---|---|
| **prod** | `economizai-api-prod` (`srv-d7odp50k1i2s73ep8o5g`) | `main` | push to `main` (auto-deploy) | **`api.economizaai.app`** (+ `economiz-ai.onrender.com`) | `economizai-db-prod` (real data) |
| **dev** | `economizaai-api-dev` (`srv-dbklrqnf3r2c73f93cdg`, plan **Free**) | `development` | push to `development` (auto-deploy) | `api-dev.economizaai.app` (+ `economizaai-api-dev-free.onrender.com`) | `economizaai-db-dev` (`dpg-dbklrc59fdbs73bmvjsg-a`, Basic 256 MB / 1 GB, stale copy = test) |

> ⚠️ **Prod's `*.onrender.com` subdomain is misleadingly named** (`economiz-ai.onrender.com`, a
> leftover from the pre-cutover topology; Render can't rename a subdomain without recreating the
> service). Always use the **custom domains** (`api.` = prod, `api-dev.` = dev) everywhere — code,
> docs, FE config. Never advertise the onrender URLs.

- **Dev is on the Free plan (since 2026-11-03, cost cut US$ 48 → 6/mo):** 512 MB, minimal CPU,
  **sleeps after 15 min idle, cold start ~3 min**. It fits in 512 MB only because of the
  `JAVA_TOOL_OPTIONS` env var on the service (`-Xmx220m … -XX:+UseSerialGC`); resting memory is
  ~500 MB, so heavy work (OCR, big parses) may OOM-kill it. Since 2026-11-10 it also runs the
  `lowmem` Spring profile (`SPRING_PROFILES_ACTIVE=dev,lowmem`: lazy beans, no boot backfills,
  delayed jobs — see DEV_NOTES). The service was RECREATED (Render
  can't switch paid→Free) and `db-dev` was recreated at Basic 256 MB from a dump (disks never
  shrink). The old `srv-d9p4…` service and the Basic 1 GB / 15 GB DB were deleted. Gotchas:
  `PUT env-vars` via API does not redeploy (trigger `POST /deploys`); from a service always use
  the Postgres INTERNAL host (`dpg-…-a:5432`) — the external one goes through the ipAllowList.
  If dev becomes a bottleneck, go back to Standard + scheduled suspend/resume, not Free.
- **Prod DB disk watch:** `disk-watch-prod.yml` (daily 09:00 BRT) reads the Render metrics API
  (`disk-usage` / `disk-capacity`) for `economizaai-db-prod` and opens a `prod-error` issue at
  80 / 90 / 95 %. Was at 47 % (480 / 1021 MB) on 2026-11-03. Disk only grows — plan the bump.

- **Cutover done 2026-09-22:** the real data + `api.economizaai.app` live on the service that
  is now **prod** on branch **`main`** (`srv-d7odp…`). The old standby (`srv-d9p4…`) became
  **dev** on `development` with its own DB + `api-dev.economizaai.app`. This was a rename +
  branch-flip only — **no data moved** (the `DATABASE_URL`s were untouched; only the Render
  resource labels changed). Done via the Render API; zero downtime on prod. (That standby
  service was later deleted and dev recreated on Free as `srv-dbklrqnf…` — see the first
  bullet.)
- **Prod instance:** Standard 2 GB (see DEV_NOTES "Dev no plano Free"). The `Dockerfile`
  caps the heap at 50 % of container RAM (`-XX:MaxRAMPercentage=50.0`) with
  `-XX:+ExitOnOutOfMemoryError`; the image build runs `-DskipTests`, so run the tests locally.
- **Deploy semantics now:** push `development` → deploys **dev only** (safe, no real users);
  merge `development` → `main` + push → deploys **prod** (`api.economizaai.app`). Keep `main`
  == `development` in code before a release so prod never regresses.
- **`main`, not `production`** — the old `production` branch was **deleted on 2026-09-01**.
- **Postgres:** managed by Render, plan **basic-256mb** (never the free tier — its Postgres
  expires in 30 days). Postgres 18, separate DB per env, separate secrets. Never point dev
  and prod at the same DB.
- **Profile pictures:** stored in **Cloudflare R2** (`PROFILE_PICTURE_STORAGE=r2` + `R2_*`
  env vars → `CloudflareR2ProfilePictureStorage`; since 2026-10-09, see DEV_NOTES "Profile
  picture storage"). The old Render Disk at `/data/profile-pics` was removed after the
  migration — a disk pins the service to one instance and blocked zero-downtime deploys.
  With `PROFILE_PICTURE_STORAGE` unset the app falls back to local disk (`PROFILE_PICTURE_DIR`),
  which is ephemeral on Render.
- **Logs:** the app logs to **stdout**, captured by Render's log stream (use the Render
  dashboard log view; no disk needed for logs).
- **Env vars / secrets:** set on each Web Service under **Environment** (see `RENDER_SETUP.md`
  §3). `DATABASE_URL` must be the **JDBC** form (`jdbc:postgresql://<host>:5432/<db>`) — the
  #1 gotcha, set by hand from the Postgres Info tab along with `DB_USERNAME` / `DB_PASSWORD`.

### ✅ RESOLVED (2026-09-22) — dev and prod are now cleanly separated

The old trap (the domain `api.economizaai.app` was bound to the *dev-branch* service, making
"dev" the de-facto prod) is **fixed**. After the cutover, `api.economizaai.app` is served by
the **prod** service on **`main`**, and `development` deploys an isolated **dev** env at
`api-dev.economizaai.app`. Pushing `development` no longer touches real users.

**Releasing to prod** = PR `development` → `main`, merged with the owner's go (see `DEPLOY.md`).
That merge IS the `economizai-api-prod` deploy — a **GATED** action, never autonomous; `main`
is otherwise never committed to directly.

## 🔗 Quick Links

| What | Link | Notes |
|---|---|---|
| **API base** | https://api.economizaai.app/api/v1 | prod (`main`); dev = https://api-dev.economizaai.app/api/v1 |
| **Swagger** | https://api-dev.economizaai.app/swagger-ui/index.html | dev; the `prod` Spring profile disables Swagger/api-docs (`application-prod.yaml`) |
| **Health check** | https://api.economizaai.app/actuator/health | returns `{"status":"UP"}` |
| **OpenAPI JSON** | https://api-dev.economizaai.app/v3/api-docs | for codegen/import (dev — see Swagger note) |
| **Render dashboard** | https://dashboard.render.com | services, env vars, logs, deploys |
| **CI / deploy runs** | Render dashboard → service → Events | auto-deploy status + build logs |
| **Uptime monitor** | https://uptimerobot.com (dashboard) | down/up alerts |
| **SonarCloud** | https://sonarcloud.io/project/overview?id=XandiVieira_economiz.AI | code quality / hotspots |

---

## Render setup — pointers

Full click-by-click runbook is **[`RENDER_SETUP.md`](./RENDER_SETUP.md)** (Postgres first,
the JDBC-URL gotcha, per-env env vars, persistent-disk gotcha, verify checklist, secrets
migration). One repo artifact it references:

- **`render.yaml`** — a Blueprint describing the two live services (dev on Free, prod on
  Standard) and their databases, with every secret marked `sync: false` and the cost
  ceilings spelled out. **The Render dashboard is the source of truth**: the services were
  created and are changed there (and through the Render API), not by applying the file —
  keep the file in step when a service changes, and never re-apply it over the live
  services without diffing first. `DATABASE_URL`/creds are set by hand (the `jdbc:` gotcha
  above).

---

## Environment variables — added in the 2026-11-11 release

Everything below has a default baked into `application.yaml`, so the app boots without any
of them. **Set explicitly on Render (owner decision 2026-11-11), on dev AND prod:** the six
`PAID_API_BUDGET_*_CENTS` ceilings; and on **prod only** `SIGNUP_ALERT_ENABLED=false` (stops
the admin alert on every signup during the campaign). The rest are tuning knobs — leave
them unset unless there is a reason. Env vars changed through the Render API do not
redeploy by themselves (trigger a deploy).

**Paid-API ceilings and per-user caps** (`economizaai.paid-api.*`; cents; `0` = no limit)

| Variable | Default | Meaning |
|---|---|---|
| `PAID_API_BUDGET_INFOSIMPLES_CENTS` | `12000` | Daily ceiling for Infosimples (R$ 120). Reached → only that service stops until midnight UTC. **Set on Render.** |
| `PAID_API_BUDGET_CAPTCHA_CENTS` | `3000` | Daily ceiling for captcha solves (R$ 30). **Set on Render.** |
| `PAID_API_BUDGET_LLM_VISION_CENTS` | `3000` | Daily ceiling for receipt-by-photo extraction (R$ 30). **Set on Render.** |
| `PAID_API_BUDGET_LLM_HTML_RESCUE_CENTS` | `1500` | Daily ceiling for the AI HTML parse rescue (R$ 15). **Set on Render.** |
| `PAID_API_BUDGET_LLM_CATEGORIZATION_CENTS` | `5000` | Daily ceiling for AI categorization (R$ 50). **Set on Render.** |
| `PAID_API_BUDGET_TWILIO_CENTS` | `1500` | Daily ceiling for SMS / WhatsApp (R$ 15). **Set on Render.** |
| `PAID_API_DAILY_BUDGET_CENTS` | `0` | Optional overall ceiling across all services. Was `5000` (the old single R$ 50 kill-switch); now off by default. |
| `INFOSIMPLES_DAILY_CAP` | `10` | Infosimples calls per user per day (was 20). |
| `CAPTCHA_DAILY_CAP` | `30` | Captcha solves per user per day (was 60). |
| `LLM_VISION_DAILY_CAP` | `10` | Photos sent to vision per user per day (was 15; counted per photo). |
| `LLM_HTML_RESCUE_DAILY_CAP` | `5` | AI HTML rescues per user per day (was 10). |
| `LLM_ENRICH_DAILY_CAP` | `60` | AI categorization calls per user per day (new). |
| `TWILIO_DAILY_CAP` | `3` | SMS / WhatsApp messages per user per day (was 10). |
| `ECONOMIZAAI_AI_DAILY_REQUEST_CAP` | `3000` | Secondary fence on the number of AI calls per day (was 300); the R$ ceilings above are the real limit. |

**Cost alerts** (`economizaai.cost-alerts.*` — WhatsApp to the admin at 80% / 100% of each ceiling and of each provider's prepaid balance)

| Variable | Default | Meaning |
|---|---|---|
| `COST_ALERTS_ENABLED` | `true` | Master switch for the ceiling and balance alerts. |
| `COST_ALERT_BUDGET_CHECK_INTERVAL_MS` | `60000` | How often today's spend is compared with the ceilings. |
| `COST_ALERT_BALANCE_CHECK_CRON` | `0 5 * * * *` | When the providers' balances are read (hourly). |
| `COST_ALERT_RETRY_INTERVAL_MS` | `120000` | Wait between delivery attempts of an alert not yet accepted. |
| `COST_ALERT_EMAIL_FALLBACK_AFTER` | `3` | Failed WhatsApp attempts before the alert is also e-mailed. |
| `COST_ALERT_MAX_WHATSAPP_ATTEMPTS` | `8` | WhatsApp attempts before falling back to the slow retry (delivery is abandoned 24 h after the alert was raised). |

**Ingestion**

| Variable | Default | Meaning |
|---|---|---|
| `SEFAZ_ALLOW_PLAIN_HTTP` | `true` | Accept `http://` portal URLs (several states print them). `false` = https only. Redirects are always re-checked against the host allowlists. |
| `INGEST_SHUTDOWN_AWAIT_SECONDS` | `60` | On shutdown (deploy), how long the ingest pool waits for in-flight notas. |
| `EAN_WARMUP_BUDGET_MS` | `5000` | Wall-clock budget of the per-receipt barcode warm-up. |
| `EAN_WARMUP_MAX_EANS` | `15` | Unseen barcodes looked up per receipt. |
| `SEFAZ_STATE_STATUS_WINDOW_HOURS` | `24` | Window of `GET /sefaz/state-status` for "recent failures". |
| `SEFAZ_STATE_STATUS_MIN_FAILURES` | `3` | Portal failures in the window before a verified state can show as `UNSTABLE`. |
| `SEFAZ_STATE_STATUS_CACHE_SECONDS` | `300` | Server-side cache of the state-status answer. |
| `NFE_XML_TRUST_BUNDLE_PATH` | *(empty)* | PEM file with the ICP-Brasil root + intermediate certificates. Empty = no XML signature is accepted as proof of origin (see DEV_NOTES). |
| `NFE_XML_PAID_RECONSULT_ENABLED` | `true` | Confirm a user-uploaded online XML through the paid by-chave lookup. `false` = it stays out of the index. |
| `RECEIPTS_AUTO_CONFIRM_ENABLED` | `true` | Auto-confirm notas left pending. |
| `RECEIPTS_AUTO_CONFIRM_AFTER_HOURS` | `6` | Age at which a pending nota is auto-confirmed. |
| `RECEIPTS_AUTO_CONFIRM_SWEEPER_DELAY_MS` | `1800000` | Interval of the auto-confirm sweep (30 min). |
| `RECEIPTS_AUTO_CONFIRM_MAX_PER_RUN` | `50` | Notas auto-confirmed per sweep, oldest first. |

**Capacity**

| Variable | Default | Meaning |
|---|---|---|
| `TOMCAT_THREADS_MAX` | `50` | Request threads (was the Tomcat default, 200). |
| `DB_CONNECTION_TIMEOUT_MS` | `6000` | Wait for a pooled DB connection before answering the overload 503 (was 30 s). |
| `ASYNC_PHOTO_EXTRACT_THREADS` / `ASYNC_PHOTO_EXTRACT_QUEUE` | `3` / `30` | Receipt-by-photo extraction lane. |
| `ASYNC_AI_CATEGORIZATION_THREADS` / `ASYNC_AI_CATEGORIZATION_QUEUE` | `2` / `50` | User-triggered AI categorization lane. |
| `IMAGE_DECODE_MAX_CONCURRENT` | `3` | Simultaneous full-image decodes (QR photo, chave OCR, profile picture). |
| `IMAGE_DECODE_MAX_WAIT_MS` | `5000` | Wait for a decode permit before the 503 `image.decode.busy`. |
| `GEOCODE_MAX_PER_RUN` | `25` | Markets geocoded per scheduled scan. |
| `MERCHANT_CLASSIFY_MAX_PER_RUN` | `25` | Markets classified (CNAE) per scheduled scan. |
| `MARKET_TRADE_NAME_MAX_PER_RUN` | `20` | Markets whose nome fantasia is read from the CNPJ registry per scheduled backfill scan (every 10 min). |

**Notifications**

| Variable | Default | Meaning |
|---|---|---|
| `NOTIFICATIONS_QUIET_HOURS_ENABLED` | `true` | Quiet hours on/off. |
| `NOTIFICATIONS_QUIET_HOURS_START` / `NOTIFICATIONS_QUIET_HOURS_END` | `21` / `8` | Window (Brasília hours) in which nothing is pushed or e-mailed. |
| `NOTIFICATIONS_QUIET_HOURS_MAX_DEFERRED` | `3` | Held-back pushes sent per user when the window ends. |
| `NOTIFICATIONS_QUIET_HOURS_DRAIN_CRON` | `0 0 * * * *` | Job that sends the held-back pushes (hourly). |
| `NOTIFICATIONS_REPLENISHMENT_CRON` | `0 0 10 * * *` | "Running low" reminders (replaces `NOTIFICATIONS_REPLENISHMENT_INTERVAL_MS`). |
| `NOTIFICATIONS_BUDGET_CRON` | `0 0 9,18 * * *` | Monthly budget check (replaces `NOTIFICATIONS_BUDGET_INTERVAL_MS`). |
| `SIGNUP_ALERT_ENABLED` | `true` | Admin alert on every signup (existing variable). **Set to `false` on prod.** |

**Subscription, referral, deals, merchants**

| Variable | Default | Meaning |
|---|---|---|
| `SUBSCRIPTION_PAID_GRACE_DAYS` | `3` | Days past the period end before a paid, still-renewing subscription is downgraded. |
| `SUBSCRIPTION_PROMO_WARNINGS_ENABLED` | `true` | End-of-launch-promo warnings on/off. |
| `SUBSCRIPTION_PROMO_WARNING_DAYS` | `15,7,3,1` | Days before the promo end on which holders are warned. |
| `SUBSCRIPTION_PROMO_WARNING_CRON` | `0 0 10 * * *` | Daily job that sends those warnings (Brasília). |
| `SUBSCRIPTION_FREE_OPEN_FEATURES` | *(empty)* | PRO features left open to FREE while enforcement is on (comma-separated `Feature` names). |
| `REFERRAL_SHARE_BASE_URL` | `https://economizaai.app/?ref=` | Prefix of the invite link (the personal code is appended). |
| `DEALS_MAX_PRICE_AGE_DAYS` | `60` | Maximum age of a chain's latest price for it to become a deal. |
| `MERCHANT_CLAIM_COOLDOWN_HOURS` | `24` | Wait before re-claiming a chain after a closed claim. |

**Removed:** `BILLING_WEBHOOK_SECRET` (the generic billing webhook is gone — delete it from
the services), `NOTIFICATIONS_REPLENISHMENT_INTERVAL_MS`, `NOTIFICATIONS_BUDGET_INTERVAL_MS`.

---

## Going to prod — the dev→prod migration map

> ⚠️ Written BEFORE the 2026-09-22 cutover described above — the domain already points at the
> prod service on `main`, and step 4's profile-pics item is done (R2). Kept as a checklist for
> standing up a fresh prod; the "currently on the dev service" wording below is historical.

Checklist for the real prod cutover (repointing `api.economizaai.app` at
`economizai-app-prod`/`main`):

0. **Spring profile:** set `SPRING_PROFILES_ACTIVE=prod`. No profile (today's setup) defaults
   to `dev` and keeps every current fallback. The `prod` profile (`application-prod.yaml`) has
   NO weak defaults — boot fails unless `DATABASE_URL`, `DB_USERNAME`, `DB_PASSWORD`,
   `JWT_SECRET`, `CORS_ORIGINS` are set — and disables Swagger. The file's header is the full
   prod env-var checklist.
1. **DB:** managed Postgres with **daily backups + PITR** on the prod instance. Migrate data
   with `pg_dump`/`pg_restore` if starting fresh.
2. **Public URL:** repoint the custom domain `api.economizaai.app` → `economizai-app-prod`
   (currently on the dev service). Add TLS (Render automatic).
3. **Secrets:** rotate `JWT_SECRET`, `METRICS_PASSWORD`, webhook secrets (never reuse dev);
   tighten `CORS_ORIGINS` to the real FE origins.
4. **Dev shortcuts (see `DEV_NOTES.md`):** local-disk profile pics → S3/Cloudflare R2; SMTP
   enabled; `/actuator/prometheus` secured; etc.
5. **Logs/monitoring:** stdout → centralized aggregator + alerting; add latency/error-rate
   alerts + status page.
6. **Re-seed the EAN catalog (data, not schema):** Flyway builds the `ean_catalog` TABLE but
   seeds NO rows — a fresh prod DB starts EMPTY, so barcode/category lookups silently degrade
   to dictionary-only. After the first prod boot, re-run the Open Food Facts import
   (`POST /api/v1/categorizer/ean-catalog/import-off`, ADMIN token, ~40 min streaming). If you
   `pg_dump`/`pg_restore` the dev DB the catalog comes along and this step is moot. See
   `DEV_NOTES.md` "EAN catalog is NOT seeded by a migration".

---

## History — self-hosted Windows box (retired 2026-09-22)

Between 2026-06 and 2026-09 the dev backend ran on a home Windows box: the full stack in
Docker, reached through a Cloudflare quick-tunnel, deployed by a self-hosted GitHub Actions
runner and kept alive by PowerShell scheduled tasks. It was migrated to Render (decision
recorded 2026-07-12) and retired on 2026-09-22. Its setup/ops scripts and the tunnel proxy
Worker were deleted from the repo on 2026-11-11; nothing in the current deploy path depends
on them. The only PowerShell file left is `scripts/flyway-dedupe.ps1`, used by the pre-push
hook to catch duplicate Flyway version numbers.

---

_Last updated: 2026-11-11. Keep this in sync when infra changes._
