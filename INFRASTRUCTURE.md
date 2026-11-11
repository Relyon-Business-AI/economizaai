# economizai — Infrastructure

**The app runs on Render.** Two web services (one repo, branch-per-environment)
plus a managed Postgres. Step-by-step setup runbook: [`RENDER_SETUP.md`](./RENDER_SETUP.md).
Bird's-eye map of the WHOLE product (mobile, web, admin, landing, e-mail, costs):
[`docs/INFRA_MAP.md`](./docs/INFRA_MAP.md).

> The old self-hosted Windows box (Cloudflare quick-tunnel, self-hosted CI runner,
> `.ps1` watchdogs, LAN log UI) has been **RETIRED** — it is documented at the very
> bottom for history only and is **not** the current deploy path.

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
migration). Two repo artifacts it references:

- **`render.yaml`** — a Blueprint (Render → New → Blueprint) that provisions the web service
  + Postgres + the profile-pics persistent disk in one shot. `DATABASE_URL`/creds are still
  set by hand (the `jdbc:` gotcha above). ⚠️ The file still describes the ORIGINAL dev setup
  (service `economizai-app`, plan `starter`, a 1 GB profile-pics disk, no R2 vars) — it does
  not match the live services in the table above; don't re-apply it without updating it.
- **`migrate-to-render.ps1`** — copied the old box's data (accounts, receipts, the
  **~910k-row EAN catalog**) into Render via `pg_dump --data-only` with row-count
  verification. Historical — used during the initial cutover.

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

_Last updated: 2026-11-10. Keep this in sync when infra changes._
