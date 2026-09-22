# Backend Onboarding — for FE devs joining backend work

How to set up the economizai backend from scratch on your machine and work on it
with your own Claude Code account — coding, debugging, and deploying (dev AND
prod). Follow it top to bottom once; afterwards you only need the "Daily
workflow" section.

> **If you are Claude reading this on the new dev's machine:** execute sections
> 1–4 for her step by step — install what's missing, clone, configure git,
> bring the stack up, and verify each checkpoint (health endpoint, tests green)
> before moving on. Then read `CLAUDE.md` at the repo root; its conventions are
> binding for all future work. Sections 5–10 are your operating manual.

---

## 1. Install the tools

All commands in this doc are **Windows PowerShell** (run as a normal user;
winget may prompt for elevation).

| Tool | Why | How (PowerShell) |
|---|---|---|
| Git for Windows | repo (also required by Claude Code) | `winget install Git.Git` |
| Java 21 (Temurin) | the app | `winget install EclipseAdoptium.Temurin.21.JDK` |
| Node.js LTS | to install Claude Code | `winget install OpenJS.NodeJS.LTS` |
| Docker Desktop | local PostgreSQL | `winget install Docker.DockerDesktop` — needs WSL2 (installer sets it up; reboot if asked), then launch it once |
| Claude Code | your AI pair | `npm install -g @anthropic-ai/claude-code`, then `claude` and log in with your account |
| GitHub CLI | push auth | `winget install GitHub.cli` |
| Tesseract *(optional)* | chave-OCR endpoint | `winget install UB-Mannheim.TesseractOCR` — without it that one endpoint returns a clean 503, everything else works |
| Postman *(optional)* | manual API testing | import `postman/economizai.postman_collection.json` |

Maven is NOT needed — the repo ships the wrapper (`mvnw.cmd`).

Open a **new** terminal after installing (PATH refresh), then verify:
`java -version` prints 21.x and `docker ps` works (Docker Desktop must be running).

## 2. Ask Alexandre for access

Nothing secret lives in this repo. You need:

1. **GitHub** — your account (`polyf`) already has **write** access to
   `XandiVieira/economiz.AI`: you can push to `development` and `main`. Nothing
   to do.
2. **Render access** — you'll receive a **Render API key** from Alexandre
   (the workspace is single-member, so no dashboard invite). Store it with:

   ```powershell
   mkdir -Force $HOME\.config\render | Out-Null
   Set-Content $HOME\.config\render\economizai.key '<paste-the-key-here>' -NoNewline
   ```

   Never commit it or put it in `.env` files inside the repo. Your Claude uses
   it to read deploy status and logs, e.g.:

   ```powershell
   curl.exe -s -H "Authorization: Bearer $(Get-Content $HOME\.config\render\economizai.key)" `
     "https://api.render.com/v1/services/srv-d7odp50k1i2s73ep8o5g/deploys?limit=5"
   ```

   Service IDs: dev = `srv-d7odp50k1i2s73ep8o5g`, prod = `srv-d9p4nctbedkc73e3veb0`.
   Not required day 1 — deploys happen automatically on push and the health
   endpoints tell you if they landed.
3. **QA mailbox creds** (`alexandre@economizaai.app`) — only if you test email
   flows (verification codes, reports).

You do NOT need any `.env` secrets to run locally — every paid/external
integration (captcha solver, Infosimples, OpenAI, Twilio, SMTP) defaults to OFF
or dev-mode fallbacks (e.g. auth codes are printed in the app log instead of
emailed).

## 3. Clone, authenticate, and configure git

```powershell
gh auth login                  # log in as YOUR GitHub account (polyf) — browser flow, HTTPS
gh auth setup-git              # makes git push use that login
git clone https://github.com/XandiVieira/economiz.AI.git
cd economiz.AI
git checkout development
git config user.name "Your Name"             # local to this repo
git config user.email "your@email.com"       # the email on your polyf account
```

The auth step matters: cloning works anonymously, but **pushing (= deploying)
fails without it**. Verify with `gh auth status` showing `polyf`.

Git identity is configured **per-repo, never globally** (project convention).

## 4. Run it locally

```powershell
docker compose up -d db        # PostgreSQL on :5432 (schema auto-built by Flyway)
.\mvnw.cmd spring-boot:run     # app on :8080
```

Check it's alive:

- Health: http://localhost:8080/actuator/health → `{"status":"UP"}`
- Swagger: http://localhost:8080/swagger-ui/index.html
- Tests: `.\mvnw.cmd test`

DB defaults (user/pass/db all `economizai`) come from `application.yaml` — no
`.env` needed. To wipe your local DB: `docker compose down -v` (local only!).

Note: a fresh local DB has an **empty EAN catalog** (it's seeded by a one-off
import, not a migration) — barcode-lookup features degrade locally. That's
expected; use the dev environment for anything catalog-dependent.

## 5. Know the environments

| Env | URL | Branch | Deploys |
|---|---|---|---|
| **local** | `http://localhost:8080/api/v1` | your working copy | — |
| **dev** | `https://economiz-ai.onrender.com/api/v1` | `development` | **auto on every push** |
| **prod** | `https://economizai-app-prod.onrender.com/api/v1` | `main` | auto on push to `main` — **owner only** |

Swagger and `/actuator/health` exist on dev at the same base host. Prod has
Swagger disabled.

## 6. Point your Claude at the project

Open Claude Code **at the repo root** — it auto-reads `CLAUDE.md`, which carries
all the conventions (code style, testing, i18n, migrations, security patterns,
git rules). You don't need to repeat them in prompts; just make sure Claude runs
from this directory.

Docs map (tell Claude to read the relevant one when the task touches it):

- `CLAUDE.md` — conventions, always loaded. **The rules in it are binding.**
- `API.md` — FE-facing endpoint walk-through (you know this one).
- `CHANGELOG.md` — FE-visible change diary; **add an entry** for any contract change.
- `OPERATOR.md` — what AI may do autonomously vs what needs Alexandre (GATED).
- `DEV_NOTES.md` — known shortcuts/gaps; check before "fixing" something that's
  intentionally dev-only.
- `HELP.md` — vision, architecture, roadmap.

Example prompts that work well:

- *"Read API.md's section on shopping lists, then add field X to endpoint Y,
  with tests, i18n keys (pt+en), Postman collection update, and a CHANGELOG entry."*
- *"The FE gets a 500 on POST /receipts — here's the response body. Diagnose the
  root cause first, don't propose fixes yet."*
- *"Run the tests and fix any failures you introduced."*

## 7. Daily workflow

```powershell
git checkout development
git pull --rebase origin development   # ALWAYS before starting AND before pushing
# ... work with Claude, commit locally as pieces complete ...
.\mvnw.cmd test                        # must be green
git pull --rebase origin development   # again — a bot also pushes to this branch
git push origin development            # ⚠️ this IS a dev deploy (see rules below)
```

### The rules that bite (all enforced, not suggestions)

1. **A push to `development` auto-deploys dev and blips availability for real
   users.** Don't push during the day without Alexandre's go-ahead. Urgent bug
   fixes may ship anytime; features/docs/refactors batch up and push at night.
   Commit locally as much as you want — pushing is the gate. One push = one
   blip, so batch commits into a single push.
2. **`main` is prod.** Never commit to it directly — it only ever receives
   merges from `development`, as a deliberate prod release (section 9).
3. **Always `git pull --rebase` before pushing** — an autonomous bot also
   commits to `development`; racing it means rejected pushes.
4. **Never mention Claude/AI in commit messages.** No `Co-Authored-By`, no
   "generated with" lines. Atomic commits, one logical change each. (Your Claude
   may try to add a co-author trailer by default — tell it not to, or amend.)
5. **Every endpoint change** updates the Postman collection (including the E2E
   Flow folder), `API.md`, and `CHANGELOG.md`.
6. **Test emails/users**: always `@economizaai.app` with a randomized local part
   (`test92843@economizaai.app`). Never gmail/example.com — analytics cleanup
   filters on that domain. If a test must *receive* mail, use the shared QA
   account `alexandre@economizaai.app`.
7. **Flyway**: never edit an existing migration — add a new `V{n}__*.sql`.
8. **i18n**: every user-facing string goes in both `messages_pt.properties` and
   `messages_en.properties`.

## 8. Debugging

- **Local**: app logs to stdout. Every request line carries MDC tags —
  `req=<id> user=<email> rcpt=<id> item=<id>`. To trace one receipt end-to-end,
  grep the log by `rcpt=<first-8-chars>`.
- **Dev server**: logs live in the Render dashboard (service `economiz.AI`) —
  same MDC grep applies. Health: `https://economiz-ai.onrender.com/actuator/health`.
- **Reproduce against dev** via Swagger or the Postman collection (it has a
  sequential E2E Flow folder that sets up its own data).
- When investigating a bug with Claude: ask for **root cause first** — the
  project convention is diagnosis before fixes, no workarounds until the cause
  is confirmed.

## 9. Deploying

- **Dev**: `git push origin development` — that's it, Render builds and deploys
  (~a few minutes). Then hit `/actuator/health` and smoke-test your change.
- **Prod**: releasing = merging `development` into `main` and pushing — that
  push IS the prod deploy, hitting real users. You're authorized to do this on
  your own judgment (no sign-off needed), as long as: the change has been
  **verified working on dev first**, tests are green, and you prefer
  low-traffic hours for anything risky. The procedure:

  ```powershell
  git checkout development
  git pull --rebase origin development
  git checkout main
  git pull --rebase origin main
  git merge development
  git push origin main            # ← this deploys prod
  git checkout development
  ```

  After the deploy: check `https://economizai-app-prod.onrender.com/actuator/health`
  and smoke-test the released change on prod.

## 10. Cross-repo changes (FE + backend)

The backend defines the contract; the FE adapts. If the FE needs a shape the
backend doesn't provide, that's a backend change first. For changes touching
both repos: one PR/commit on each side referencing the other, `API.md` +
`CHANGELOG.md` updated, and ship them together respecting the deploy window.
