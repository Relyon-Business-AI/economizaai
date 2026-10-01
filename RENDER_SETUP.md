# economizai — Render deployment runbook (dev + prod)

Concrete, followable setup for two isolated environments on Render. The app is
already Docker-ready (`Dockerfile`, multi-stage Temurin 21, Tesseract installed,
listens on `10000`, Flyway runs on boot, health at `/actuator/health`).

Topology: **two environments, each = 1 Web Service + 1 managed Postgres**, with
**separate databases** and **separate secrets**. Never point dev and prod at the
same DB.

| | dev | prod |
|---|---|---|
| Web Service | Starter (or Free, spins down) | **Standard — 1 CPU / 2 GB** (JVM needs ≥1 GB; do NOT use 512 MB Starter) |
| Postgres | Basic (256 MB) | Basic/Standard with **daily backups on** |
| Auto-deploy branch | `development` | `production` (merge `development → production` to release) |
| Always-on | Free spins down (schedulers pause — OK for dev) | Yes (paid = always-on; schedulers/sweepers need it) |

---

## 0. Prereqs
- GitHub repo connected to Render.
- Create a `production` branch off `development` (release by merging into it).
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
3. **Branch:** `production` (prod) / `development` (dev). Auto-deploy on push: on.
4. **Instance type:** Standard (prod) / Starter or Free (dev).
5. **Region:** same as the DB.
6. **Health check path:** `/actuator/health` (public, returns `{"status":"UP"}`).
7. **Port:** the container listens on `10000` (matches Render's default `PORT`). *(Optional hardening: change the Dockerfile entrypoint to `--server.port=${PORT:-10000}` so it honors whatever port Render injects.)*

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

### Third-party keys — copy from the self-host (secret); enable per readiness
| Var | Notes |
|---|---|
| `CAPTCHA_PROVIDER` / `CAPTCHA_API_KEY` | `capsolver` + key to enable MS/SC scraping (else those states 503) |
| `INFOSIMPLES_ENABLED` / `INFOSIMPLES_API_KEY` | `true` + key to enable CE + fallback (paid) |
| `SMTP_HOST/PORT/USERNAME/PASSWORD` + `EMAIL_FROM` | enables email verification + digests |
| `NOTIFICATIONS_EMAIL_ENABLED` | `true` once SMTP works |
| `EXPO_ACCESS_TOKEN` | push notifications |
| `TWILIO_ACCOUNT_SID/AUTH_TOKEN/FROM_SMS/FROM_WHATSAPP` | SMS/WhatsApp (PRO) |
| `GOOGLE_OAUTH_CLIENT_IDS` / `APPLE_OAUTH_CLIENT_IDS` | social login audience check |
| `REVENUECAT_WEBHOOK_AUTH` / `BILLING_WEBHOOK_SECRET` | billing webhooks (secret) |

### Cost guards (already have defaults — tune per env)
`PAID_API_GUARD_ENABLED=true`, `PAID_API_DAILY_BUDGET_CENTS` (R$50 default — raise/lower per env), `INFOSIMPLES_DAILY_CAP`, `CAPTCHA_DAILY_CAP`. In **dev**, consider `INFOSIMPLES_ENABLED=false` + `CAPTCHA_PROVIDER=none` so testing never spends money. (Status quo 2026-10: BOTH envs run `INFOSIMPLES_ENABLED=true` on purpose — CE flows are testable in dev; the daily caps + budget guard bound the spend.)

### Behavior flags (defaults are fine; flip when ready)
`SUBSCRIPTION_ENFORCE=false` (keep off during warm-up), `RELEVANCE_MODE=SHADOW`, `ML_CATEGORY_APPLY_ENABLED=false`, timezone crons already `America/Sao_Paulo`.

---

## 4. Persistent storage gotcha (profile pictures)
Render web filesystems are **ephemeral** — wiped on every deploy/restart. `PROFILE_PICTURE_DIR` (default `/tmp/...`) means **uploaded profile pics vanish on each deploy**. Options:
- **Now (quick):** attach a Render **Disk** to the service, mount at e.g. `/var/economizai/pics`, set `PROFILE_PICTURE_DIR` to it. (A disk pins the service to 1 instance — fine until you scale horizontally.)
- **Before scale:** move to object storage (S3/Cloudflare R2). Tracked in DEV_NOTES.

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

## 7. Secrets migration from the self-host
- **Generate fresh** (do NOT copy from dev): `JWT_SECRET`, `METRICS_PASSWORD`, webhook secrets.
- **Copy** the third-party keys (captcha, infosimples, SMTP, Twilio, Expo, OAuth client IDs) into Render env (Secret type).
- Never commit any of these; the repo only holds the `${VAR:default}` placeholders.
