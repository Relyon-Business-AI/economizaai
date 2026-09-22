# Operator Runbook — AI-owned SDLC

How economizai is operated by AI agents (Claude) with a human (the owner) on the
critical-decision button. This is the **constitution**: every scheduled agent and
every interactive session reads it and obeys the autonomous-vs-gated split below.

Covers **both repos**: the Spring Boot backend (`economiz.AI`) and the Expo/React
Native frontend (`polyf/economiza-ai-front`).

---

## Roles

- **AI operator** — monitors, triages, reproduces, fixes, tests, deploys the
  *non-critical* paths, documents, and escalates. Runs continuously via scheduled
  agents (CI) and on-demand via interactive sessions.
- **Owner (human)** — decides **what to build** and all **monetary/pricing** calls,
  and **clicks the button** on everything in the "GATED" column. The AI may *propose*
  product/monetary ideas but never *decides* them.

## The continuous loop (per repo)

```
monitor → triage → reproduce → fix → test (harness) → deploy → validate → document → (escalate at any gate)
```

- **Backend (dev)**: already implemented — see `.github/AUTOFIX.md` (log-sweep 2h, nightly
  E2E, chaos, reproduce-first autofix, `AUTONOMOUS_FIXES.md` ledger + `AUTOFIX_LESSONS.md`).
- **Backend (prod)**: ALERT-ONLY — `log-sweep-prod.yml` (2h) + `e2e-prod.yml` (nightly,
  self-cleaning flow) open deduplicated `prod-error` issues via `prod_alert.py`. The fixer
  never touches prod; chaos/stress stays dev-only. Fix on dev → gated prod deploy.
- **Frontend**: harness in `economiza-ai-front/TESTING.md` (web/Android/iOS). CI operator
  to be added (see "Build plan").

## Cross-repo coordination (backend ⇄ frontend)

The two repos are one product: the FE consumes the backend's API, so many fixes/syncs
span both. **Never reason about one in isolation when a change touches the contract.**

- **Shared workspace.** Any operator run (scheduled or interactive) that could touch the
  contract checks out **both** repos: backend `Relyon-Business-AI/economizaai` and frontend
  `Relyon-Business-AI/economiza-ai-front` (canonical org path — `polyf/...` redirects).
- **Contract is the seam.** The backend's OpenAPI (`/v3/api-docs`, Swagger) + `API.md` +
  `CHANGELOG.md` are the source of truth the FE depends on. On any endpoint/response-shape
  change, update `API.md`/`CHANGELOG.md` **and** check the FE's `src/services/*` +
  `src/types` for drift (missing/renamed fields, removed endpoints). A `contract-sync`
  check (backend OpenAPI vs FE usage) surfaces mismatches; run it before calling a
  contract change "done".
- **Linked PRs.** A cross-repo change opens a PR in **each** repo, cross-referencing the
  other in the body, and they ship together (respecting each side's deploy rules —
  backend deploy window; FE OTA).
- **Direction of truth.** Backend defines the contract; FE adapts. If the FE needs a shape
  the backend doesn't provide, that's a backend change first (propose it), not an FE hack.

## Autonomous vs GATED

| Area | ✅ AI does autonomously | 🔴 GATED — owner clicks the button |
|---|---|---|
| **Bug fixes** | Reproduce-first JS/Java fixes, tests, refactors | — |
| **Tests** | Add/run unit + e2e (web/Android/iOS harness) | — |
| **Deploys — backend** | Merge fixes to `development` (dev auto-deploys) within the fix contract | Anything shipped as a "feature" outside deploy windows; **prod release** (merge `development` → `main` + push — auto-deploys prod) |
| **Deploys — frontend OTA** | `eas update` to **preview/dev** channels | `eas update` to **production** (user-facing) |
| **Store** | Prep `eas build` + submission bundle | `eas submit` to App Store / Play Store, store metadata |
| **Database** | Write + test Flyway migrations on a branch | Merging/deploying a migration to prod |
| **Native app changes** | Implement + build/test on simulators | Anything needing a new **store binary** (new native module, permission, SDK/runtime bump) |
| **Docs** | Keep CLAUDE.md/HELP.md/API.md/CHANGELOG.md/MEMORY.md/ledgers current | — |
| **Product & money** | *Suggest* features, pricing, B2B ideas | *Decide* scope, pricing, tiers, spend |
| **Infra/secrets** | Use existing secrets/APIs | Create/rotate credentials, change infra topology |

**Rule of thumb for "GATED":** anything **irreversible, user-facing at scale, costs
money, touches the stores, or is a product/monetary decision** → propose + wait for the
owner. Everything else → do it, then report.

## Escalation

When the AI hits a gate or can't proceed, it **does not guess** — it writes a clear
"needs-owner" note (what, why, the one action needed) to the relevant ledger/PR and
pings the owner. Precedent: the `NEEDS-HUMAN` tags in `AUTONOMOUS_FIXES.md`.

## Deploy windows

Backend pushes to `development` auto-deploy and blip availability. Fixes may ship
anytime; **features batch for the night / an explicit go** (see CLAUDE.md → Git Workflow).
Backend **prod** deploys from `main` (Render `economizai-app-prod`, auto-deploy): the
release IS the push — merge `development` → `main` only on the owner's go. See
INFRASTRUCTURE.md → "Environments & release flow".
Frontend OTA has no downtime, but **production** OTA is still gated (user-facing).

## Environment & continuity

The AI's "continuous knowledge" lives in **files + scheduled agents + a persistent host**,
not in any chat session. The brain:

| Doc | Purpose |
|---|---|
| `OPERATOR.md` (this file) | The constitution — roles, gates, loop |
| `CLAUDE.md` | Conventions both repos follow |
| `HELP.md` | Architecture + session log |
| `MEMORY.md` (+ memory dir) | Durable facts across sessions |
| `TESTING.md` (frontend) | The web/Android/iOS harness |
| `AUTONOMOUS_FIXES.md` / `AUTOFIX_LESSONS.md` | Fix ledger + learned lessons |
| `API.md` / `CHANGELOG.md` | FE-facing contracts + change diary |

**Secrets** (must live on the persistent host / CI, never a laptop, never committed):
| Secret | Where | Purpose | Status |
|---|---|---|---|
| `RENDER_API_KEY` | backend Actions | logs, deploy status | ✅ set |
| `CLAUDE_CODE_OAUTH_TOKEN` | backend Actions | the fixer agent | see AUTOFIX.md |
| `EXPO_TOKEN` | frontend Actions | `eas update`/`eas build` unattended | ⛔ **owner must add** (EAS access token) |
| `E2E_ADMIN_EMAIL` / `_PASSWORD` | both | harness/e2e login | optional |
| `SUBSCRIPTION_ENFORCE` | Render (dev/prod) | flips the paywall ON (default false → gates inert) | ⛔ owner flips when charging |
| `BILLING_WEBHOOK_SECRET` | Render (prod) | provider-agnostic subscription webhook auth | ⛔ owner sets with the web provider |
| `REVENUECAT_WEBHOOK_AUTH` | Render (prod) | RevenueCat (iOS/Android) webhook auth | ⛔ owner sets when store billing goes live |
| `ECOMMERCE_*` (ML client-id/secret/affiliate) | Render | e-commerce comparison provider — inert until set | ⛔ owner sets (see DEV_NOTES / docs/ECOMMERCE_COMPARISON.md) |
| `META_ADS_*` / `META_CAPI_*` | Render | ad-spend sync + conversions API — inert until set | ⛔ owner sets |

## Build plan (frontend operator — to add)

1. **Harness in CI** — GitHub Action running the Playwright web lane on every PR (cheap,
   no device); native lanes on demand / bundled builds.
2. **OTA deploy** — `eas update --channel preview` autonomously on merge to `master`;
   `--channel production` **only via a gated manual approval** (the owner's button).
3. **Monitor** — surface FE runtime errors (Sentry/console) into a triage → fix loop,
   mirroring the backend log-sweep.

Requires `EXPO_TOKEN` in the frontend repo secrets first.
