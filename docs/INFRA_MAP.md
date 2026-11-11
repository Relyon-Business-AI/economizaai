# economizai — Infra Map (the whole product, not just this repo)

Where every piece of the product lives, what it costs, what's free, and the
recommended target shape. Backend-only detail stays in
[`INFRASTRUCTURE.md`](../INFRASTRUCTURE.md); this doc is the bird's-eye view
across ALL repos and providers.

_Last updated: 2026-11-10 (table rows for dev plan, profile pics, workflows, AI and web
payments corrected against the code; costs and §3–4 recommendations are still the
2026-09-22 text)._

---

## 1. The pieces — where each one lives

| Piece | Repo | Runs on | Deploys via | Cost |
|---|---|---|---|---|
| **Backend API (prod)** | `Relyon-Business-AI/economizaai` (`main`) | Render `economizai-api-prod` → `api.economizaai.app` | push to `main` (GATED, owner's go) | paid |
| **Backend API (dev)** | same repo (`development`) | Render `economizaai-api-dev` → `api-dev.economizaai.app` | merge/push to `development` (auto) | **free** (Free plan since 2026-11-03; sleeps after 15 min) |
| **Postgres ×2** | — | Render managed (one per env; dev is basic-256mb) | — | paid |
| **Profile pics** | — | Cloudflare R2 (`PROFILE_PICTURE_STORAGE=r2`, since 2026-10-09; the Render Disk was removed) | — | R2 free tier |
| **Mobile app (Android/iOS)** | `Relyon-Business-AI/economiza-ai-front` | user devices; built by **EAS Build**, shipped via stores + **EAS Update (OTA)** | `ota-deploy.yml` / EAS; production channel GATED | Apple US$99/yr; Google US$25 once; EAS free tier |
| **Web app** | same FE repo | **Cloudflare Worker** `economiza-ai-front` → `dashboard.economizaai.app` (static Expo web export, SPA fallback) | `web-deploy.yml` on push to `master` | **free** |
| **Admin UI** | ⚠️ INSIDE the Expo app (`src/screens/Admin*.tsx`, 9 screens + `adminService.ts`) | ships with every mobile build/OTA and the web bundle | rides the app's deploys | free but **coupled** |
| **Landing page** | `Relyon-Business-AI/economiza-ai-landing` (Astro) | Cloudflare (static + worker/) | own deploy, independent | **free** |
| **Garimpo de promoções (e-commerce comparison)** | backend repo (module) | inside the Spring Boot monolith — **INERT** (no ML creds) | rides backend deploys | free while inert |
| **DNS / TLS** | — | Cloudflare zone `economizaai.app` (Render terminates TLS for api.*) | — | **free** |
| **E-mail** | — | Titan/GoDaddy mailboxes (`contato@`, `alexandre@economizaai.app`); SMTP `smtpout.secureserver.net:587` | — | paid (mailbox) |

> ⚠️ DEV_NOTES still references `contato@relyonai.com.br` as the SMTP login —
> verify which sender identity is actually configured; SPF/DKIM/DMARC hardening
> is pending either way.

## 2. External services — by status

**Live & paid (fixed monthly):**
- **Render** — 2 web services (prod Standard 2 GB, dev Free) + 2 Postgres, no disk
  anymore. The only significant fixed cost. Exact plans: `INFRASTRUCTURE.md` / the dashboard.
- **Titan/GoDaddy mailbox** — e-mail domain.
- **Domain** `economizaai.app` (~US$15–20/yr) (+ `relyonai.com.br` if still held).
- **Apple Developer Program** — US$99/yr (mandatory for iOS).

**Live & paid (per-use, capped):**
- **Infosimples** — SEFAZ fallback (~R$0.24/query; rescues fetch failures in every state).
  Daily ceiling R$120, per-user cap 10/day, circuit breaker per state, `paid_api_call` ledger.
- **CapSolver** — captcha for the captcha-walled states (~US$1–3 per 1000 solves; daily
  ceiling R$30, per-user cap 30/day).
- **Meta Ads** — ad spend itself (API is free; spend-sync + CAPI built, dormant).

**Live & free:**
- **Cloudflare** — DNS, web app Worker, landing. Free tier covers all of it.
- **GitHub Actions** — backend repo is public → unlimited minutes (10 workflows:
  autofix, e2e-daily, e2e-prod, log-sweep ×2, sonar, chaos-weekly,
  observation-audit, event-watch-prod, disk-watch-prod). FE repos are **private** → 2 000 min/mo free cap; CI +
  web-deploy + OTA burn from that pool.
- **SonarCloud** (public repo), **UptimeRobot** (free tier), **k6** (OSS),
  **Expo Push Service**, **Google/Apple OAuth**.

**Built but dormant (cost only when switched on):**
- **OpenAI** (optional second provider of the AI layer — inert until `OPENAI_API_KEY`).
  Note: the AI layer itself (photo-receipt extraction, HTML parse-rescue, curadoria) runs
  on **Anthropic** via `AiGateway` (`ANTHROPIC_API_KEY`) and is a live per-use cost, capped
  by the per-service daily ceilings of the paid-API guard (photo R$30, HTML rescue R$15,
  categorization R$50) plus `economizaai.ai.daily-request-cap` as a secondary fence.
- **Twilio** (SMS/WhatsApp OTP — no creds)
- **RevenueCat** (IAP webhooks ready — needs secret; 0–1% of revenue at scale)
- **Mercado Livre API** (garimpo — no creds)
- **Web payment provider** — Mercado Pago (chosen 2026-09-29; `POST /subscriptions/checkout`
  + `/webhooks/mercadopago` built, inert until `MP_ACCESS_TOKEN` / `MP_WEBHOOK_SECRET`).

## 3. What can be free (that isn't yet)

1. **Profile pics → Cloudflare R2.** ✔ DONE 2026-10-09 (see DEV_NOTES). Free tier: 10 GB storage, no egress fees.
   Kills the Render Disk cost AND the single-instance coupling (already a
   DEV_NOTES prod prereq). Best money-saver available.
2. **Admin as a static web app on Cloudflare.** Same free Workers/Pages tier the
   web app already uses — separating admin costs R$0 in hosting (see §4).
3. **Landing + web app** — already free on Cloudflare. Nothing to do. ✔
4. **EAS builds** — free tier is limited/queued; if it ever bites, local
   `eas build --local` on the Mac is free and unlimited.
5. **What NOT to make free:** Render Postgres free tier (expires in 30 days —
   already a hard rule), and don't downgrade the prod web service to free
   (cold-start sleep would wreck receipt processing UX).

## 4. Recommendation — target shape

The deploy-coupling pain has ONE real offender left: **the admin UI lives
inside the consumer app**. Landing is already separate; web app already has its
own pipeline; the garimpo is a dormant module. So:

1. **Extract admin into its own web app** (workspace in the FE repo or tiny new
   repo) → own Cloudflare Worker, e.g. `admin.economizaai.app`, own deploy.
   Result: admin changes never trigger a mobile build/OTA, and the consumer
   bundle stops shipping admin code to every user (also a security win).
2. **Keep the backend a single modular monolith.** Split the garimpo into a
   worker service only when it's live AND its runtime profile (long crawls)
   measurably hurts the API. Not before — every extra Render service is +cost
   +ops for a solo team.
3. **Keep Android/iOS/web as one Expo codebase** — that sharing is the point.
4. **Move profile pics to R2** next time you touch that area (free + unblocks
   horizontal scaling).
5. **Decision rule for any future split:** different deploy cadence or
   different runtime profile → separate the DEPLOY. Otherwise it's a folder,
   not a repo.

### Deploy independence after step 1

| Change to… | Triggers deploy of… | Touches users? |
|---|---|---|
| Backend (`development`) | dev API only | no |
| Backend (`main`, gated) | prod API | blip, gated |
| Mobile app | EAS build / OTA | yes (app users) |
| Web app | CF Worker `economiza-ai-front` | web users only |
| **Admin** | **its own CF Worker** | **no one but you** |
| Landing | its own CF deploy | visitors only |
