# economizai — Infrastructure

**The app runs on Render.** Two web services (one repo, branch-per-environment)
plus a managed Postgres. Step-by-step setup runbook: [`RENDER_SETUP.md`](./RENDER_SETUP.md).

> The old self-hosted Windows box (Cloudflare quick-tunnel, self-hosted CI runner,
> `.ps1` watchdogs, LAN log UI) has been **RETIRED** — it is documented at the very
> bottom for history only and is **not** the current deploy path.

## Current reality (Render) — clean dev/prod split (cutover 2026-09-22)

| Env | Service | Branch | Deploys when | Domain / URL | DB |
|---|---|---|---|---|---|
| **prod** | `economizai-api-prod` (`srv-d7odp50k1i2s73ep8o5g`) | `main` | push to `main` (auto-deploy) | **`api.economizaai.app`** (+ `economiz-ai.onrender.com`) | `economizai-db-prod` (real data) |
| **dev** | `economizai-api-dev` (`srv-d9p4nctbedkc73e3veb0`) | `development` | push to `development` (auto-deploy) | `api-dev.economizaai.app` (+ `economizai-app-prod.onrender.com`) | `economizai-db-dev` (stale copy = test) |

- **Cutover done 2026-09-22:** the real data + `api.economizaai.app` live on the service that
  is now **prod** on branch **`main`** (`srv-d7odp…`). The old standby (`srv-d9p4…`) became
  **dev** on `development` with its own DB + `api-dev.economizaai.app`. This was a rename +
  branch-flip only — **no data moved** (the `DATABASE_URL`s were untouched; only the Render
  resource labels changed). Done via the Render API; zero downtime on prod.
- **Deploy semantics now:** push `development` → deploys **dev only** (safe, no real users);
  merge `development` → `main` + push → deploys **prod** (`api.economizaai.app`). Keep `main`
  == `development` in code before a release so prod never regresses.
- **`main`, not `production`** — the old `production` branch was **deleted on 2026-09-01**.
- **Postgres:** managed by Render, plan **basic-256mb** (never the free tier — its Postgres
  expires in 30 days). Postgres 18, separate DB per env, separate secrets. Never point dev
  and prod at the same DB.
- **Profile pictures:** persisted on a **Render Disk** mounted at `/data/profile-pics`
  (`PROFILE_PICTURE_DIR`) — Render's container filesystem is ephemeral and wiped on every
  deploy, so this disk keeps uploads across deploys.
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

**Releasing to prod** = merge `development` → `main` and push `main`. That push IS the
`economizai-app-prod` deploy — a **GATED** action (owner's go), never autonomous; `main` is
otherwise never committed to directly. Until the domain cutover, this alone does NOT reach
real users — you must also push `development`.

## 🔗 Quick Links

| What | Link | Notes |
|---|---|---|
| **API base** | https://api.economizaai.app/api/v1 | de-facto prod (dev-branch service) |
| **Swagger** | https://api.economizaai.app/swagger-ui/index.html | |
| **Health check** | https://api.economizaai.app/actuator/health | returns `{"status":"UP"}` |
| **OpenAPI JSON** | https://api.economizaai.app/v3/api-docs | for codegen/import |
| **Render dashboard** | https://dashboard.render.com | services, env vars, logs, deploys |
| **CI / deploy runs** | Render dashboard → service → Events | auto-deploy status + build logs |
| **Uptime monitor** | https://uptimerobot.com (dashboard) | down/up alerts |
| **SonarCloud** | https://sonarcloud.io/project/overview?id=XandiVieira_economiz.AI | code quality / hotspots |

---

## Render setup — pointers

Full click-by-click runbook is **[`RENDER_SETUP.md`](./RENDER_SETUP.md)** (Postgres first,
the JDBC-URL gotcha, per-env env vars, persistent-disk gotcha, verify checklist, secrets
migration). Two repo artifacts it references:

- **`render.yaml`** — a Blueprint (Render → New → Blueprint) that provisions the web service
  + Postgres + the profile-pics persistent disk in one shot. `DATABASE_URL`/creds are still
  set by hand (the `jdbc:` gotcha above).
- **`migrate-to-render.ps1`** — copied the old box's data (accounts, receipts, the
  **~910k-row EAN catalog**) into Render via `pg_dump --data-only` with row-count
  verification. Historical — used during the initial cutover.

---

## Going to prod — the dev→prod migration map

Still-accurate checklist for the real prod cutover (repointing `api.economizaai.app` at
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

## Legacy — self-hosted Windows box (RETIRED 2026-09-22)

> **None of the below is the current deploy path.** Kept for historical reference only.
> Before Render, the dev backend ran on a home Windows 11 box (user `Xandi`, computer
> `DESKTOP-PLT5POI`, static LAN IP `192.168.68.108`, repo clone under OneDrive). Migrated
> to Render on the decision recorded 2026-07-12; box retired 2026-09-22.

**Stack (Docker, `docker-compose.yml --profile server`):** `economizai-app` (8080→10000,
Spring Boot), `economizai-db` (PostgreSQL 18), `economizai-logs` (Dozzle log UI, 9999→8080).
Data in named volumes (`economizai-pgdata`, `economizai-profilepics`).

**Public path (the "from anywhere" URL):** FE → `https://economizaai.economizaai.workers.dev`
(a permanent **Cloudflare Worker** front door, `tunnel-proxy-worker/`) → reads the live tunnel
URL from a Worker KV namespace → `https://<random>.trycloudflare.com` (a **cloudflared
quick-tunnel**, dials out, no open ports, URL changes per restart) → `localhost:8080`.
`start-tunnel.ps1` captured each new tunnel URL and wrote it back into KV. LAN path:
`http://192.168.68.108:8080` (needed same Wi-Fi + firewall TCP 8080).

**Auto-deploy:** push to `development` → `.github/workflows/deploy-dev-server.yml` → ran on a
**self-hosted GitHub Actions runner** (`C:\actions-runner`, Windows service, label
`economizai-dev`) → `ci-deploy.ps1` (`compose up -d --build`, health verify, tunnel self-heal).

**Always-on / self-recovery (Scheduled Tasks + `.ps1`):** never-sleep (`make-always-on.ps1`),
auto-login (`enable-autologin.ps1`), Docker autostart, `stack-watchdog.ps1` (every 5 min,
re-runs `compose up` if unhealthy), tunnel keep-alive (`start-tunnel.ps1`). The autonomous
bug-fix watchdog ran git `reset --hard`/`clean -fd` on the checkout every ~20s — control
scripts (`pause-watchdogs.bat` / `resume-watchdogs.bat`) lived outside the repo at
`C:\economizai-data\` so they wouldn't be wiped.

**Data / backups:** runtime data under `C:\economizai-data` (`ECONOMIZAI_DATA_ROOT`), outside
the tree. `backup-db.ps1` → compressed `pg_dump` to `db-backups\` (14-day retention, daily
03:00). Logs saved daily 02:55 via `logs.ps1 -Save`.

**Logging / monitoring:** Dozzle at `http://192.168.68.108:9999` (LAN only). `logs.ps1`
(`-Errors -Grep -Db -Save -Since`). UptimeRobot pinged `/actuator/health` every 5 min.
`/actuator/prometheus` fail-closed behind `METRICS_PASSWORD` (Prometheus/Grafana never stood
up on the box).

**Scheduled tasks:** `start Docker engine` (logon), `stack watchdog` (startup + 5 min),
`cloudflare tunnel` (logon), `daily db backup` (03:00), `daily log save` (02:55), plus the
GitHub runner Windows service.

**Setup scripts (repo root, admin PowerShell):** `make-always-on.ps1`, `enable-autologin.ps1`,
`set-static-ip.ps1`, `setup-firewall.ps1`, `start-tunnel.ps1` / `setup-tunnel-autostart.ps1`,
`setup-github-runner.ps1` / `install-runner-service.ps1`, `ci-deploy.ps1`, `update-server.ps1`,
`backup-db.ps1`, `logs.ps1`, `tunnel-proxy-worker/` (Cloudflare Worker, `wrangler deploy`).

---

_Last updated: 2026-09-22. Keep this in sync when infra changes._
