# economizai — Render deployment runbook (dev + prod)

Concrete, followable setup for two isolated environments on Render. The app is
already Docker-ready (`Dockerfile`, multi-stage Temurin 21, Tesseract installed,
listens on `10000`, Flyway runs on boot, health at `/actuator/health`).

Topology: **two environments, each = 1 Web Service + 1 managed Postgres**, with
**separate databases** and **separate secrets**. Never point dev and prod at the
same DB.

> This is the generic setup runbook. The live services, plans and IDs (dev on Free since
> 2026-11-03, prod on Standard 2 GB) are in [`INFRASTRUCTURE.md`](./INFRASTRUCTURE.md).

| | dev | prod |
|---|---|---|
| Web Service | Starter (or Free, spins down) | **Standard — 1 CPU / 2 GB** (JVM needs ≥1 GB; do NOT use 512 MB Starter) |
| Postgres | Basic (256 MB) | Basic/Standard with **daily backups on** |
| Auto-deploy branch | `development` | `main` (merge `development → main` to release; the old `production` branch was deleted 2026-09-01) |
| Always-on | Free spins down (schedulers pause — OK for dev) | Yes (paid = always-on; schedulers/sweepers need it) |

---

## 0. Prereqs
- GitHub repo connected to Render.
- Prod deploys from `main` (release by merging `development` into it — see `DEPLOY.md`).
- Decide region — Render has **no Brazil region**; pick **Ohio** (closest East) or Oregon. Use the SAME region for a service and its DB (private networking + low latency).

---

## 1. Create the Postgres (do this first — the web service needs its URL)
For **each** environment:
1. Render → New → **Postgres**. Name `economizai-db-prod` / `economizai-db-dev`. Same region as the web service.
2. Prod: enable **daily backups**, pick a plan with enough storage/connections.
3. After it provisions, open **Connections** and copy the **Internal Database URL**:
   `postgresql://<user>:<password>@<host>/<db>`
4. **Convert it for Spring** (this is the #1 gotcha — our app wants a JDBC URL + separate user/pass):
   - `DATABASE_URL` → `jdbc:postgresql://<host>:5432/<db>` (add `jdbc:` and `:5432`, drop the `user:pass@`)
   - `DB_USERNAME` → `<user>`
   - `DB_PASSWORD` → `<password>`
   - Internal URL = same private network → **no `sslmode` needed**. (Only external connections need SSL.)

---

## 2. Create the Web Service
For **each** environment:
1. Render → New → **Web Service** → from the repo.
2. **Runtime: Docker** (it auto-detects the `Dockerfile`). No build/start command needed.
3. **Branch:** `main` (prod) / `development` (dev). Auto-deploy on push: on.
4. **Instance type:** Standard (prod) / Starter or Free (dev).
5. **Region:** same as the DB.
6. **Health check path:** `/actuator/health/readiness` (reflects only the app lifecycle, so a deploy swaps instances without dropping requests); `/actuator/health` stays public for uptime monitors (`{"status":"UP"}`).
7. **Port:** the container listens on `10000` (matches Render's default `PORT`); the Dockerfile entrypoint already uses `--server.port=${PORT:-10000}`, so it honors whatever port Render injects.

---

## 3. Environment variables (set on each Web Service)

Render → the service → **Environment**. Mark API keys/passwords as **Secret**.
Everything not listed here has a safe default baked into `application.yaml`.

### Must set — DB (from step 1)
| Var | Value |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://<host>:5432/<db>` |
| `DB_USERNAME` | from Render PG |
| `DB_PASSWORD` | from Render PG (secret) |

### Must set — security (generate FRESH per env — never reuse the dev placeholder)
| Var | How |
|---|---|
| `JWT_SECRET` | ≥256-bit random. `openssl rand -base64 48` (secret) |
| `METRICS_PASSWORD` | random; guards `/actuator/prometheus` (secret) |
| `ADMIN_EMAILS` | comma-list of admin accounts |
| `CORS_ORIGINS` | your real FE origins (prod domain / app scheme). Do NOT leave the localhost default in prod |

### Third-party keys (secret) — enable per readiness
| Var | Notes |
|---|---|
| `CAPTCHA_PROVIDER` / `CAPTCHA_API_KEY` | `capsolver` + key to enable MS/SC scraping (else those states 503) |
| `INFOSIMPLES_ENABLED` / `INFOSIMPLES_API_KEY` | `true` + key to enable the paid SEFAZ fallback (all UFs) |
| `ANTHROPIC_API_KEY` | AI layer (`economizaai.ai`: photo-receipt extraction, HTML parse-rescue, curadoria). Blank = AI features fail closed |
| `PROFILE_PICTURE_STORAGE` + `R2_ENDPOINT` / `R2_BUCKET` / `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` | `r2` + Cloudflare R2 creds for profile pictures (see §4) |
| `SMTP_HOST/PORT/USERNAME/PASSWORD` + `EMAIL_FROM` | enables email verification + digests |
| `NOTIFICATIONS_EMAIL_ENABLED` | `true` once SMTP works |
| `EXPO_ACCESS_TOKEN` | push notifications |
| `TWILIO_ACCOUNT_SID/AUTH_TOKEN/FROM_SMS/FROM_WHATSAPP` | SMS/WhatsApp (PRO) |
| `GOOGLE_OAUTH_CLIENT_IDS` / `APPLE_OAUTH_CLIENT_IDS` | social login audience check |
| `REVENUECAT_WEBHOOK_AUTH` | RevenueCat (apps) billing webhook (secret) |
| `MP_ACCESS_TOKEN` / `MP_WEBHOOK_SECRET` | Mercado Pago (web) checkout + webhook (secret) |
| `ADMIN_PHONE_NUMBERS` | where the admin WhatsApp alerts go (cost ceilings, ops) |

### Cost guards (defaults exist — the six ceilings are set explicitly on both envs)
`PAID_API_GUARD_ENABLED=true`. Each paid service has its own **daily ceiling in cents** — set all six on dev AND prod (owner decision 2026-11-11):

| Var | Value | Service |
|---|---|---|
| `PAID_API_BUDGET_INFOSIMPLES_CENTS` | `12000` | Infosimples (R$ 120/day) |
| `PAID_API_BUDGET_CAPTCHA_CENTS` | `3000` | captcha solves (R$ 30/day) |
| `PAID_API_BUDGET_LLM_VISION_CENTS` | `3000` | receipt by photo (R$ 30/day) |
| `PAID_API_BUDGET_LLM_HTML_RESCUE_CENTS` | `1500` | AI HTML rescue (R$ 15/day) |
| `PAID_API_BUDGET_LLM_CATEGORIZATION_CENTS` | `5000` | AI categorization (R$ 50/day) |
| `PAID_API_BUDGET_TWILIO_CENTS` | `1500` | SMS / WhatsApp (R$ 15/day) |

Per-user daily caps keep their defaults (`INFOSIMPLES_DAILY_CAP=10`, `CAPTCHA_DAILY_CAP=30`, `LLM_VISION_DAILY_CAP=10`, `LLM_HTML_RESCUE_DAILY_CAP=5`, `LLM_ENRICH_DAILY_CAP=60`, `TWILIO_DAILY_CAP=3`). The old single ceiling `PAID_API_DAILY_BUDGET_CENTS` is now an optional overall limit, off by default (`0`). The admin is warned on WhatsApp at 80% / 100% of each ceiling and of each provider's prepaid balance (`COST_ALERT*`). Both envs run `INFOSIMPLES_ENABLED=true` on purpose — CE flows are testable in dev; the caps and ceilings bound the spend. Full variable reference: `INFRASTRUCTURE.md` → "Environment variables".

### Behavior flags (defaults are fine; flip when ready)
`SUBSCRIPTION_ENFORCE=false` (keep off during warm-up), `RELEVANCE_MODE=SHADOW`, `ML_CATEGORY_APPLY_ENABLED=false`, timezone crons already `America/Sao_Paulo`. On **prod** set `SIGNUP_ALERT_ENABLED=false` (no admin alert per signup).

---

## 4. Persistent storage gotcha (profile pictures)
Render web filesystems are **ephemeral** — wiped on every deploy/restart. `PROFILE_PICTURE_DIR` (default `/tmp/...`) means **uploaded profile pics vanish on each deploy**. Options:
- **Now (quick):** attach a Render **Disk** to the service, mount at e.g. `/var/economizai/pics`, set `PROFILE_PICTURE_DIR` to it. (A disk pins the service to 1 instance — fine until you scale horizontally.)
- **Before scale:** move to object storage (S3/Cloudflare R2). Tracked in DEV_NOTES.
- **Current (since 2026-10-09):** the object-storage option is built and in use — set `PROFILE_PICTURE_STORAGE=r2` plus the `R2_*` vars (`application.yaml` → `economizaai.profile-picture`); no Render Disk needed, which also allows zero-downtime deploys. See DEV_NOTES "Profile picture storage".

Logs: `LOG_FILE` is also ephemeral, but the app logs to **stdout** too — Render captures that, so use the Render log stream (no disk needed for logs).

---

## 5. First deploy + seed
1. Push to the env's branch → Render builds the Dockerfile and boots. **Flyway runs all migrations automatically** against the fresh DB.
2. **Seed the EAN catalog** (fresh DB starts EMPTY — see DEV_NOTES; categorization degrades without it): run the OFF import once against the new env (`POST /api/v1/categorizer/ean-catalog/import-off`, stream the OFF dump — see the DEV_NOTES entry / `scratchpad/stream_import.py`).
3. Add a **custom domain** per env (Render → Settings → Custom Domains) → automatic TLS. Point the FE at the prod domain.

---

## 6. Verify checklist (per env)
- [ ] `GET /actuator/health` → `{"status":"UP"}`
- [ ] `/swagger-ui/index.html` loads
- [ ] Register → login works (JWT issued)
- [ ] Submit one real receipt → reaches PENDING_CONFIRMATION (proves SEFAZ + captcha/infosimples wiring)
- [ ] `GET /api/v1/admin/costs` shows the paid call in the ledger (if paid providers enabled)
- [ ] Flyway history present; EAN catalog seeded
- [ ] Scheduled jobs firing (check logs for sweeper/digest lines) — prod only, needs always-on

---

## 7. Secrets
- **Generate fresh** per environment (do NOT copy from dev): `JWT_SECRET`, `METRICS_PASSWORD`, webhook secrets.
- **Reuse** the third-party keys (captcha, infosimples, Anthropic, R2, SMTP, Twilio, Expo, OAuth client IDs) as Render env vars (Secret type).
- Never commit any of these; the repo only holds the `${VAR:default}` placeholders.
