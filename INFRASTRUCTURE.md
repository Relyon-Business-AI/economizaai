# economizai — Dev Server Infrastructure

> **Moving to a real 2-env host (dev + prod on Render):** step-by-step runbook in
> [`RENDER_SETUP.md`](./RENDER_SETUP.md) — services, managed Postgres, per-env env
> vars, the ephemeral-disk/profile-pic gotcha, and secret migration. This file
> below documents the current self-hosted Windows box (to be retired).

## 🔗 Quick Links (dev environment)

| What | Link | Notes |
|---|---|---|
| **API base** | https://economizai.economizai.workers.dev/api/v1 | from anywhere |
| **Swagger** | https://economizai.economizai.workers.dev/swagger-ui/index.html | from anywhere |
| **Health check** | https://economizai.economizai.workers.dev/actuator/health | should return `{"status":"UP"}` |
| **OpenAPI JSON** | https://economizai.economizai.workers.dev/v3/api-docs | for codegen/import |
| **Logs (Dozzle UI)** | http://192.168.68.108:9999 | **same Wi-Fi only** (not public) |
| **CI / deploy runs** | https://github.com/XandiVieira/economiz.AI/actions | auto-deploy status |
| **Uptime monitor** | https://uptimerobot.com (dashboard) | down/up alerts |
| **SonarCloud** | https://sonarcloud.io/project/overview?id=XandiVieira_economiz.AI | code quality / hotspots |

**Auto-opened on boot:** the `economizai - open dashboards` Scheduled Task opens
Dozzle (`:9999`), the UptimeRobot dashboard, the SonarCloud project, and Swagger in
Chrome ~45s after logon.

**On the LAN (same Wi-Fi), the API is also at** `http://192.168.68.108:8080/api/v1`
(health `…/actuator/health`, swagger `…/swagger-ui/index.html`).

---

Single source of truth for how the **self-hosted dev environment** runs. This is
a home Windows 11 box acting as the dev backend for the team (replaced Render).
Every column has a **"→ prod"** note so we know what to swap when we get there.

> Scope: this is a **dev** environment — fine for the team + early data, NOT a
> production target (home connection, no HA, no TLS-at-origin, single disk).
> See the "Going to prod" section at the bottom for the migration map.

---

## TL;DR — what's where

| Thing | Value |
|---|---|
| Host machine | Windows 11, user `Xandi`, computer `DESKTOP-PLT5POI` |
| Repo clone | `C:\Users\Xandi\OneDrive\Documents\projects\economiz.AI` |
| LAN IP (static) | `192.168.68.108` |
| **Public API (FE uses this)** | `https://economizai.economizai.workers.dev/api/v1` |
| Health | `…/actuator/health` · Swagger `…/swagger-ui/index.html` |
| Log UI (LAN only) | `http://192.168.68.108:9999` (Dozzle) |
| Branch deployed | `development` (auto-deploys on push) |

---

## The stack (Docker, via `docker-compose.yml`, `--profile server`)

| Container | Port (host→container) | Role |
|---|---|---|
| `economizai-app` | 8080 → 10000 | Spring Boot API |
| `economizai-db` | 5432 → 5432 | PostgreSQL 18 |
| `economizai-logs` | 9999 → 8080 | Dozzle (live log web UI) |

- Data lives in the named volume `economizai_economizai-pgdata` (schema rebuilt by
  Flyway on a fresh volume). **`docker compose down -v` wipes it** — don't, unless intended.
- Bring up: `docker compose --profile server up -d --build`
- `restart: unless-stopped` on all; Docker `json-file` logs capped (app 20MBx10, db 10MBx3).

---

## How traffic reaches the box (the "from anywhere" path)

```
FE anywhere ──https──▶ economizai.economizai.workers.dev   (permanent URL, free)
                          │  Cloudflare Worker reads current tunnel URL from KV
                          ▼
                  https://<random>.trycloudflare.com        (changes per restart)
                          │  cloudflared quick-tunnel (dials OUT, no open ports)
                          ▼
                  localhost:8080  ──▶ economizai-app
```

- **Worker** (`tunnel-proxy-worker/`): permanent front door. Deployed to Cloudflare
  (free account `alexandrecvieiracolorado@…`). Reads the live tunnel URL from a KV
  namespace (`TUNNEL`, key `origin`).
- **Tunnel** (`start-tunnel.ps1`): on each start it captures the new `trycloudflare`
  URL, **writes it into the Worker's KV** (so the permanent URL auto-follows), and
  publishes it to `C:\economizai-data\logs\current-tunnel-url.txt`. Kept alive by a logon task.
- **LAN path** (no internet needed): `http://192.168.68.108:8080` — needs same Wi-Fi +
  the firewall rule (TCP 8080, Private).
- **→ prod:** replace the quick-tunnel + Worker with a **named Cloudflare tunnel on a
  real domain** (stable URL, no KV indirection), or put the app behind a proper
  load balancer / reverse proxy with TLS.

---

## Always-on / self-recovery

| Mechanism | Script / setting | What it covers |
|---|---|---|
| Never sleep/hibernate | `make-always-on.ps1` (powercfg) | idle, lid close, unplug |
| Auto-login | `enable-autologin.ps1` | unattended reboot → user session starts |
| Docker auto-start | task `economizai - start Docker engine` (at logon) | reboot → engine up |
| Containers restart | `restart: unless-stopped` | container/app crash |
| Stack watchdog | task `economizai - stack watchdog` (startup + every 5 min) → `stack-watchdog.ps1` | engine wedge / failed boot — re-runs `compose up` if health ≠ 200 |
| Tunnel keep-alive | task `economizai - cloudflare tunnel` (logon) → `start-tunnel.ps1` | tunnel reconnect after reboot |

- **Not covered:** power outage (needs BIOS "Restore on AC Power Loss = ON") and
  mesh-Wi-Fi roaming to the other subnet (prefer wired ethernet).
- **→ prod:** a managed host (Fly.io / Railway / VPS) handles all of this natively —
  none of these scripts are needed; they exist only because it's a home Windows box.

### ⏸️ Pausing the watchdogs before editing the repo

The autonomous bug-fix watchdog runs git `clean -fd` / `reset --hard origin/development`
on the OneDrive checkout every ~20 s, so **any uncommitted/untracked file in the repo
gets wiped within a poll**, and the stack-watchdog *revives* the autofix process after a
kill. Both tasks run elevated, so a normal terminal can't stop them (`Acesso negado`).

**Before hand-editing the repo on the box, pause them.** Control scripts live **outside**
the repo (so the watchdog can't clean them), at `C:\economizai-data\`:

| Action | Double-click | What happens |
|---|---|---|
| Pause | `pause-watchdogs.bat` | UAC prompt → **Sim** → disables both tasks + kills the autofix process (stays down) |
| Resume | `resume-watchdogs.bat` | UAC prompt → **Sim** → re-enables + starts both tasks |
| Status | `watchdog-control.ps1 -Action status` | reports state, no elevation needed |

- The `.bat` files **self-elevate** (relaunch themselves as admin via UAC). Windows always
  requires the UAC click — there is no fully-silent path short of disabling UAC (don't).
- Tip: put desktop shortcuts to the two `.bat` files for one-click pause/resume.
- **Remember to resume** when done — while paused, the box won't auto-deploy or self-heal.

---

## Auto-deploy (push → live)

- **Trigger:** push to `development` → `.github/workflows/deploy-dev-server.yml`.
- **Runs on:** a **self-hosted GitHub Actions runner** (`C:\actions-runner`, Windows
  service, runs as `Xandi`, label `economizai-dev`). Dials OUT — nothing exposed.
- **Does:** checkout pushed commit → `ci-deploy.ps1` (copy `.env`, `compose up -d --build`,
  verify health). Postgres volume untouched → data preserved. ~1–2 min, ~30–60s API blip.
- **Tunnel self-heal (2026-07-09):** after the local health check, `ci-deploy.ps1` probes
  the PERMANENT public URL; if it's down (dead quick-tunnel → Cloudflare 1016) it restarts
  the "economizai - cloudflare tunnel" Scheduled Task, which republishes the KV origin.
  Any push (or Actions → re-run of the last deploy) doubles as a remote tunnel restart —
  no RDP needed. Best-effort: never fails an otherwise good deploy.
- **Watch runs:** repo → Actions tab.
- **Setup scripts:** `setup-github-runner.ps1` / `install-runner-service.ps1`.
- **Quirks baked in (Windows):** uses `shell: cmd` (not PowerShell — avoids
  ErrorActionPreference choking on docker stderr); script invoked with
  `-ExecutionPolicy Bypass`; runner has Windows PowerShell 5.1 only (no `pwsh`).
- **→ prod:** replace the self-hosted runner with the host's native CI/CD (GitHub
  Actions on cloud runners + the platform's deploy hook). Drop `ci-deploy.ps1`.

---

## Config & secrets (`.env`)

- `.env` at repo root (gitignored): `DB_USERNAME/PASSWORD`, `JWT_SECRET` (CSPRNG),
  `CORS_ORIGINS` (localhost + LAN IP + the workers.dev URL), `NOTIFICATIONS_EMAIL_ENABLED`,
  `METRICS_PASSWORD` (+ optional `METRICS_USERNAME`) to enable Prometheus scraping — blank = `/actuator/prometheus` is locked (401).
- **⚠️ Two copies:** the runner service can't reliably read the OneDrive path, so a
  copy lives at **`C:\actions-runner\.env`**. **If you change `.env`, update BOTH** or
  auto-deploy uses stale values.
- **→ prod:** move secrets to the platform's secret manager; generate a fresh
  `JWT_SECRET`; drop localhost/LAN entries from `CORS_ORIGINS`; real SMTP creds; the
  dev shortcuts in `DEV_NOTES.md` (local-disk profile pics, etc.) must be addressed.

---

## Data directory

All runtime data lives in the **machine data dir `C:\economizai-data`** (override via
the `ECONOMIZAI_DATA_ROOT` env var), deliberately OUTSIDE the project tree / runner
checkout so it's never committed or OneDrive-synced:
- `C:\economizai-data\db-backups\` — `pg_dump` files
- `C:\economizai-data\logs\` — app, tunnel, watchdog, recovery logs + `current-tunnel-url.txt`
- `C:\economizai-data\logs\app\app.log` — persistent rolling app log (compose bind mount)
- `C:\economizai-data\images\` — reserved for future host-side image storage

DB data and profile pictures already live in Docker named volumes
(`economizai-pgdata`, `economizai-profilepics`), also outside the tree.

> **TODO (watchdog isolation):** the autonomous bug-fix watchdog currently runs git
> ops against the OneDrive working copy (the human's editing checkout). A dirty-tree
> guard now stops it from `reset --hard`-ing over uncommitted work, but the proper
> fix is a dedicated clone outside OneDrive (e.g. `C:\economizai-watchdog`) so it can
> never collide with edits. Not done yet — the runner checkout can't be reused (it's
> Administrators-owned and rewritten on every deploy).

## Backups

- **DB:** `backup-db.ps1` → compressed `pg_dump` to `C:\economizai-data\db-backups\`
  (14-day retention, monthly anchors). Daily 03:00 via task `economizai - daily db backup`.
  Verified restorable. Restore: `pg_restore -d <db> <file>.dump`.
- **Logs:** `logs.ps1 -Save` → dated files in `C:\economizai-data\logs\` (14-day). Daily
  02:55 via task `economizai - daily log save`.
- **⚠️ Off-box copy:** the data dir is no longer OneDrive-synced. For disaster recovery
  add a second destination (external drive / cloud bucket / scheduled OneDrive copy).
- **→ prod:** managed Postgres with automated backups + PITR; ship logs to a real
  aggregator.

---

## Logging / debugging

- **Live UI:** Dozzle at `http://192.168.68.108:9999` (LAN only — not on the public
  tunnel, since logs can contain sensitive data). Search/filter/follow `economizai-*`.
- **CLI:** `logs.ps1` — `-Errors`, `-Grep rcpt=xxx`, `-Db`, `-Save`, `-Since 30m`.
  App logs carry MDC `req= user= rcpt= item=` — grep by `rcpt=<id>` to trace one
  receipt end-to-end.
- **History:** `C:\economizai-data\logs\*.log` (14-day; off the project tree).
- **→ prod:** centralized logging (Loki/Grafana, Better Stack, New Relic) with longer
  retention + alerting; protect/disable Dozzle.

---

## Monitoring

- **UptimeRobot** (free) pings `…/actuator/health` every 5 min, emails/pushes on
  down/up. Catches outages the watchdog can't self-heal (machine off, internet down).
- **→ prod:** add latency/error-rate alerting, status page, on-call.

### Metrics — `/actuator/prometheus` (HTTP Basic protected)

The app exposes Prometheus metrics at `/actuator/prometheus`. It is **fail-closed**:
with no password set it returns `401` to everyone (so it's never leaked publicly).
`/actuator/health` stays public for UptimeRobot.

To enable scraping:
1. In the server `.env` set `METRICS_PASSWORD` (and optionally `METRICS_USERNAME`,
   default `metrics`), then `docker compose --profile server up -d` to apply.
2. Scrape over the **LAN** (`http://192.168.68.108:8080/actuator/prometheus`),
   not the public tunnel. Example `prometheus.yml` job:
   ```yaml
   scrape_configs:
     - job_name: economizai
       metrics_path: /actuator/prometheus
       basic_auth: { username: metrics, password: <METRICS_PASSWORD> }
       static_configs:
         - targets: ['192.168.68.108:8080']
   ```
3. **Grafana** (not yet installed): add Prometheus as a data source and import a
   Spring Boot / JVM (Micrometer) dashboard, e.g. dashboard ID `4701` or `11378`.
   Run both as containers on the box (compose) when set up.

**Not yet done:** Prometheus + Grafana aren't running on the box yet — the endpoint
is ready and secured; standing up the scraper + dashboards is the remaining step.

---

## All scheduled tasks (admin-registered)

| Task | When | Script |
|---|---|---|
| `economizai - start Docker engine` | at logon | starts Docker Desktop |
| `economizai - stack watchdog` | startup + every 5 min | `stack-watchdog.ps1` |
| `economizai - cloudflare tunnel` | at logon | `start-tunnel.ps1` |
| `economizai - daily db backup` | daily 03:00 | `backup-db.ps1` |
| `economizai - daily log save` | daily 02:55 | `logs.ps1 -Save` |

Plus the **GitHub runner** Windows service (`actions.runner.XandiVieira-economiz.AI.economizai-dev-box`).

---

## Setup scripts index (all at repo root, run in **Administrator** PowerShell unless noted)

| Script | Purpose |
|---|---|
| `make-always-on.ps1` | power settings + Docker autostart + watchdog task |
| `enable-autologin.ps1` | unattended-boot auto-login |
| `set-static-ip.ps1` | pin LAN IP (revert: `netsh interface ip set address name="Wi-Fi" source=dhcp`) |
| `setup-firewall.ps1` | inbound TCP 8080 (Private) |
| `start-tunnel.ps1` / `setup-tunnel-autostart.ps1` | Cloudflare tunnel + KV sync + autostart |
| `setup-github-runner.ps1` / `install-runner-service.ps1` | self-hosted CI runner |
| `ci-deploy.ps1` | invoked by the deploy workflow (don't run by hand normally) |
| `update-server.ps1` | manual pull+rebuild fallback |
| `backup-db.ps1` / `setup-backup-schedule.ps1` | DB backup + daily schedule |
| `logs.ps1` / `setup-logsave-schedule.ps1` | log viewing/saving + daily schedule |
| `tunnel-proxy-worker/` | Cloudflare Worker (permanent URL) — `wrangler deploy` |

---

## Environments & release flow (Render)

Two Render web services, one repo, branch-per-environment (since 2026-09-01):

| Env | Service | Branch | Public URL | Deploys when |
|---|---|---|---|---|
| **dev** | `economiz.AI` (`srv-d7odp50k1i2s73ep8o5g`) | `development` | **`api.economizaai.app`** (+ `economiz-ai.onrender.com`) | every push (auto-deploy) |
| **prod** | `economizai-app-prod` (`srv-d9p4nctbedkc73e3veb0`) | `main` | `economizai-app-prod.onrender.com` (no custom domain) | every push to `main` (auto-deploy) |

> **⚠️ CURRENT REALITY (verified 2026-09-21):** the custom domain **`api.economizaai.app`**
> — the URL the mobile app / FE actually calls — is bound to the **`economiz.AI` (dev,
> `development` branch)** service, so **that service is the de-facto production backend**
> (all real receipt traffic lands there). `economizai-app-prod` (`main`) is live and
> healthy but has **no custom domain and no real traffic** — a standby the FE doesn't use
> yet. Net effect: **pushing `development` deploys the live app users hit.** The `main`/
> `economizai-app-prod` cutover (repoint the domain) hasn't happened. Deploy to BOTH to
> keep them in sync until then.

**Releasing to prod** = merge `development` → `main` and push `main`. That push IS the
`economizai-app-prod` deploy — it's a GATED action (owner's go), never autonomous. `main`
is otherwise never committed to directly. **Note:** until the domain cutover, this alone
does NOT reach real users — you must also push `development` (see the reality note above).

History: the prod service initially tracked `development` (every dev push hit prod);
on 2026-08-28 a `production` branch was created to isolate releases; on 2026-09-01 it
was deleted and the service repointed at `main` (which was code-identical), so `main`
is now the single release branch.

---

## Render migration (DEV) — pointers

Full click-by-click runbook is **[`RENDER_SETUP.md`](./RENDER_SETUP.md)** (services,
Postgres, per-env env vars, the `jdbc:` URL gotcha, verify checklist). This section
only records the **decisions + the two repo artifacts** it doesn't cover.

**Decisions (2026-07-12):**
- **DEV first, one env** (~US$14/mo: paid web + paid Postgres — never the free tier,
  whose Postgres expires in 30 days and killed the old Render). Prod is a later add.
- **New topology:** Render runs the app + Postgres + the public URL; the **home box
  keeps ONLY the autonomous bug-fix watchdog**. Retire on the box: the Cloudflare
  quick-tunnel + Worker + KV (the flaky `trycloudflare` URL), the stack-watchdog, and
  the self-hosted GitHub runner + `deploy-dev-server.yml` (Render auto-deploys on push).

**Two artifacts RENDER_SETUP.md doesn't include:**
- **`render.yaml`** — a Blueprint (Render → New → Blueprint) that provisions the web
  service + Postgres + the profile-pics persistent disk in one shot, instead of the
  manual service creation. `DATABASE_URL`/creds still set by hand (the `jdbc:` gotcha).
- **`migrate-to-render.ps1`** — copies the CURRENT box's data (accounts, receipts, the
  **~910k-row EAN catalog**) into Render via `pg_dump --data-only`, with row-count
  verification. Use this INSTEAD of the "re-import the OFF catalog (~40 min)" step in
  RENDER_SETUP.md §5 — migrating the data brings the catalog along. Run after the first
  healthy Render boot (Flyway must have created the schema first).

---

## Going to prod — the migration map

When the time comes, this is the dev→prod swap (most are "delete the home-box
workaround, use the platform feature"):

0. **Spring profile:** set `SPRING_PROFILES_ACTIVE=prod`. No profile (today's
   setup) defaults to `dev` and keeps every current fallback, so the dev box
   needs no change. The `prod` profile (`application-prod.yaml`) has NO weak
   defaults — boot fails unless `DATABASE_URL`, `DB_USERNAME`, `DB_PASSWORD`,
   `JWT_SECRET`, `CORS_ORIGINS` are set — and disables
   Swagger. The file's header is the full prod env-var checklist.
1. **Host:** home Windows box → managed platform (Fly.io / Railway / paid Render / VPS).
   Deletes: all scheduled tasks, the self-hosted runner, the always-on scripts.
2. **DB:** Docker Postgres on one disk → managed Postgres (Neon/Supabase/RDS) with
   automated backups + PITR. Migrate data with `pg_dump`/`pg_restore`.
3. **Public URL:** quick-tunnel + Worker + KV → a real domain with TLS (named tunnel
   or the platform's ingress). One stable origin, drop the KV indirection.
4. **CI/CD:** self-hosted runner + `ci-deploy.ps1` → cloud runners + platform deploy.
5. **Secrets:** `.env` (x2 copies) → platform secret manager; rotate `JWT_SECRET`;
   tighten `CORS_ORIGINS`.
6. **Dev shortcuts (see `DEV_NOTES.md`):** local-disk profile pics → S3/Cloudinary;
   SMTP enabled; `/actuator/prometheus` secured; etc.
7. **Logs/monitoring:** Dozzle/files → centralized aggregator + alerting.
8. **Re-seed the EAN catalog (data, not schema):** Flyway builds the `ean_catalog`
   TABLE but seeds NO rows — a fresh prod DB starts EMPTY, so barcode/category
   lookups silently degrade to dictionary-only. After the first prod boot, re-run
   the ~32k-row Open Food Facts import (`POST /api/v1/categorizer/ean-catalog/import-off`,
   ADMIN token, ~40 min streaming). If you `pg_dump`/`pg_restore` the dev DB (step 2)
   the catalog comes along and this step is moot — only needed for a clean-slate prod DB.
   See `DEV_NOTES.md` "EAN catalog is NOT seeded by a migration".

---

_Last updated: 2026-09-01. Keep this in sync when infra changes._
