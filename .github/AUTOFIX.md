# Autonomous bug fixer (GitHub Actions)

The autonomous fixer that used to run on the local Windows box (`auto-fix-watchdog.ps1`)
now runs in GitHub Actions against the live Render dev server. Same contract:
reproduce-first, `mvnw test` gate, push to `development`, wait for the Render deploy +
health, auto-revert on failure. It also **learns** over time.

## Pieces
| File | Role |
|---|---|
| `.github/workflows/e2e-daily.yml` | Nightly (06:00 UTC) newman run of the Postman **E2E Flow** against the live dev API. On failure → fixer. |
| `.github/workflows/log-sweep.yml` | Every 2h: pulls recent Render logs, finds a NEW app error (deduped vs the ledger) → fixer. |
| `.github/workflows/observation-audit.yml` | Weekly (Mon 02:00 BRT): asserts orphaned price observations (deleted-account leftovers) aren't accumulating — i.e. the nightly confirm→purge cleanup works. Read-only; not wired to the fixer. |
| `.github/workflows/chaos-weekly.yml` | Weekly (Sun 01:00 BRT): N k6 virtual users hit dev concurrently, contending on shared catalog products, to surface races/deadlocks/pool exhaustion. Throwaway users self-delete; one persistent user (`alexandre+chaospersist@`) accumulates history to cover returning-user scenarios. Goes red on any 5xx (which the log-sweep also feeds to the fixer). |
| `.github/workflows/autofix.yml` | Reusable fixer job (reproduce → test → push → deploy-wait → health → revert). |
| `.github/workflows/log-sweep-prod.yml` | Every 2h (offset from dev sweep): same detection against the **prod** service (`srv-d7odp50k1i2s73ep8o5g`), **alert-only** — opens a deduped `prod-error` issue, never calls the fixer. |
| `.github/workflows/e2e-prod.yml` | Nightly (04:30 BRT): the same self-cleaning E2E Flow against **prod**, **alert-only** — red run + `prod-error` issue on failure. Uses `E2E_PROD_ADMIN_EMAIL/_PASSWORD` (optional; dev admin creds don't exist on prod). |
| `.github/scripts/autofix.py` | The fixer + learning loop. |
| `.github/scripts/log_sweep.py` | Render Logs API scan + detection rules. |
| `.github/scripts/prod_alert.py` | Prod alert half: dedups against `prod-error` issues (open+closed — closing acks a signature permanently) and opens a new issue with the signature + context. |
| `.github/scripts/e2e_context.py`, `run-e2e.sh` | Newman run + failure-context builder. |
| `AUTONOMOUS_FIXES.md` | Audit ledger — one block per action (FIX / NEEDS-HUMAN / ROLLBACK / HALT). |
| `AUTOFIX_LESSONS.md` | The memory: durable lessons read into every fixer prompt. |

## How it learns
Before each attempt the fixer prompt is injected with (a) the most recent ~40 lessons
from `AUTOFIX_LESSONS.md` and (b) every prior ledger entry touching the *same* error
signature. After each outcome it distills a new one-line lesson (Claude may also emit an
explicit `LESSON <...>`) and appends it. A signature that was "fixed" but recurs is
flagged so the fixer looks for the real root cause instead of re-applying the same change.
No-fix outcomes are committed with `[skip render]` so recording never triggers a deploy.

## Safety rails
- Reproduce-first: no production change without a test that first FAILS on the bug, and
  the fixer independently checks that test is in the diff.
- `mvnw test` (full suite) must pass or the fix is discarded.
- After deploy, if `/actuator/health` isn't UP the commit is auto-reverted.
- Circuit breaker: halts after 3 fixes in one hour.

## Required GitHub secrets
| Secret | Set? | Notes |
|---|---|---|
| `RENDER_API_KEY` | ✅ set | Render logs + deploy-status polling. |
| `CLAUDE_CODE_OAUTH_TOKEN` | ⛔ **you must add** | `claude setup-token` on the machine logged into the fixer's Claude account, then paste the token. |
| `E2E_ADMIN_EMAIL` / `E2E_ADMIN_PASSWORD` | optional | Admin E2E steps skip without them. |
| `E2E_QR_PAYLOAD` | optional | A real NFC-e QR string; receipt-scan E2E steps skip without it. |

## E2E coverage
The nightly E2E Flow exercises every endpoint as a **no-5xx coverage probe** (asserts
`status < 500` — catches crashes even when data is empty), plus the real scan→confirm→
**purge**→delete cleanup so it leaves zero garbage in the community price index. One live
scan uses a public GO NFC-e QR (from `RealGoiasFixtureTest`); all test mail goes to
`alexandre@economizaai.app`.

The nightly E2E also includes a **FUZZ** section — boundary/edge-case inputs (inverted date
ranges, huge/zero/negative limits, 500-char + unicode/emoji strings, absurd quantities, bad
EANs) all no-5xx gated, so a crash on weird-but-valid input surfaces as a real bug.

**Deliberately excluded from the nightly run** (they'd corrupt shared dev data, spend
money, or break the sequential flow — all covered by the unit/integration suite instead):
- Global-catalog / LLM / paid mutations: `categorizer/*/import`, `promote-consensus`,
  `DELETE learned|consensus`, `brands/derive-from-catalog`, admin `products/merge`,
  `refresh-brands`, `recategorize`, `markets/classify-segments`, `llm/*/resolve`.
- Auth-flow breakers: `logout`, `refresh`, `forgot/verify/reset-password`, `verify-email`,
  `auth/google|apple` (need real provider tokens).
- Cross-entity destructive: `households/join|leave`, `DELETE members`, admin `DELETE users`,
  `DELETE products`, `PUT subscription-tier`, `notifications/test`.
- Multipart uploads (`receipts/*photo`, `profile-picture` POST) and `phone/verify` (needs an SMS code).

## Trigger manually
`gh workflow run e2e-daily.yml` or `gh workflow run log-sweep.yml`.
