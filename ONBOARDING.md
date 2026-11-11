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

1. **GitHub** — **write** access for your own GitHub account to
   `Relyon-Business-AI/economizaai` (ask Alexandre to add you). You push task branches
   and open PRs; `development` and `main` only change through merged PRs (section 7).
2. **Render access** — you'll receive a **Render API key** from Alexandre
   (the workspace is single-member, so no dashboard invite). Store it with:

   ```powershell
   mkdir -Force $HOME\.config\render | Out-Null
   Set-Content $HOME\.config\render\economizaai.key '<paste-the-key-here>' -NoNewline
   ```

   Never commit it or put it in `.env` files inside the repo. Your Claude uses
   it to read deploy status and logs, e.g.:

   ```powershell
   curl.exe -s -H "Authorization: Bearer $(Get-Content $HOME\.config\render\economizaai.key)" `
     "https://api.render.com/v1/services/srv-dbklrqnf3r2c73f93cdg/deploys?limit=5"
   ```

   Service IDs: dev = `srv-dbklrqnf3r2c73f93cdg`, prod = `srv-d7odp50k1i2s73ep8o5g`.
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
gh auth login                  # log in as YOUR GitHub account — browser flow, HTTPS
gh auth setup-git              # makes git push use that login
git clone https://github.com/Relyon-Business-AI/economizaai.git
cd economizaai
git checkout development
git config user.name "Your Name"             # local to this repo
git config user.email "your@email.com"       # the email on your GitHub account
```

The auth step matters: cloning works anonymously, but **pushing fails without it**.
Verify with `gh auth status` showing your account.

Git identity is configured **per-repo, never globally** (project convention). Anything
specific to you or your machine (accounts, tokens, local paths) goes in `CLAUDE.local.md`
(gitignored), never in `CLAUDE.md`.

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
| **dev** | `https://api-dev.economizaai.app/api/v1` | `development` | **auto on every merge** (Render Free plan: sleeps after 15 min idle, first request may take ~3 min) |
| **prod** | `https://api.economizaai.app/api/v1` | `main` | auto on merge of the release PR — **with Alexandre's go** |

> ⚠️ Use the custom domains above, NOT the `*.onrender.com` URLs — they're
> misleadingly named: **prod**'s is `economiz-ai.onrender.com` and the **dev**
> service's is `economizaai-api-dev-free.onrender.com` (see `INFRASTRUCTURE.md`).

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

Same process as the frontend — `DEPLOY.md` is the short version, with a diagram.

```powershell
git checkout development
git pull --rebase origin development        # start from the latest
git checkout -b feat/my-task                # ONE branch per task
# ... work with Claude, commit locally as pieces complete ...
.\mvnw.cmd test                             # must be green — the Render build does NOT run tests
git pull --rebase origin development        # again, right before pushing
git push -u origin feat/my-task             # pushing a task branch deploys nothing
gh pr create --base development             # ONE PR for the whole task
```

Merge the PR only when the task is complete. The merge deploys dev by itself; validate
there (section 9).

### The rules that bite (all enforced, not suggestions)

1. **Never push straight to `development`.** Everything arrives through a PR. Every merge
   redeploys dev, which is down for a few minutes each time, and back-to-back merges cancel
   each other — so one merge per task, not per commit. Commit locally as much as you want.
2. **`main` is prod.** Never commit to it and never cherry-pick into it — it only receives
   the release PR `development` → `main`, merged with Alexandre's go (section 9).
3. **Always `git pull --rebase origin <branch>` before pushing** — an autonomous bot also
   pushes fixes; racing it means rejected pushes.
4. **Never mention Claude/AI in commit messages.** No `Co-Authored-By`, no
   "generated with" lines. Atomic commits, one logical change each. (Your Claude
   may try to add a co-author trailer by default — tell it not to, or amend.)
5. **Every endpoint change** updates the Postman collection (including the E2E
   Flow folder), `API.md`, and `CHANGELOG.md`.
6. **Test emails/users**: always `@economizaai.app` with a randomized local part
   (`test92843@economizaai.app`). Never gmail/example.com — analytics cleanup
   filters on that domain. If a test must *receive* mail, use the shared QA
   account `alexandre@economizaai.app`.
7. **Flyway**: never edit an existing migration — add a new `V{n}__*.sql`, and check the
   number is not already taken on `development` (parallel branches collide; the tests do
   not catch it, only the deploy does).
8. **i18n**: every user-facing string goes in both `messages_pt.properties` and
   `messages_en.properties`.

## 8. Debugging

- **Local**: app logs to stdout. Every request line carries MDC tags —
  `req=<id> user=<email> rcpt=<id> item=<id>`. To trace one receipt end-to-end,
  grep the log by `rcpt=<first-8-chars>`.
- **Dev server**: logs live in the Render dashboard (service `economizaai-api-dev`) —
  same MDC grep applies. Health: `https://api-dev.economizaai.app/actuator/health`.
- **Reproduce against dev** via Swagger or the Postman collection (it has a
  sequential E2E Flow folder that sets up its own data).
- When investigating a bug with Claude: ask for **root cause first** — the
  project convention is diagnosis before fixes, no workarounds until the cause
  is confirmed.

## 9. Deploying

- **Dev**: merging your task PR into `development` — that's it, Render builds and deploys
  (a few minutes; the service may also need to wake up). Then hit
  `https://api-dev.economizaai.app/actuator/health` and validate your change there.
- **Prod**: a release is a PR `development` → `main`, opened once the change is
  **validated on dev**, and merged **with Alexandre's go** — that merge IS the prod deploy,
  hitting real users. No direct commits and no cherry-picks into `main`.

  ```powershell
  gh pr create --base main --head development --title "release: <what is going out>"
  # wait for Alexandre's ok, then merge the PR
  ```

  After the deploy: check `https://api.economizaai.app/actuator/health`
  and smoke-test the released change on prod.
- **Front depends on a backend change?** The backend goes to prod first.

## 10. Cross-repo changes (FE + backend)

The backend defines the contract; the FE adapts. If the FE needs a shape the
backend doesn't provide, that's a backend change first. For changes touching
both repos: one PR on each side referencing the other, `API.md` +
`CHANGELOG.md` updated, and release the backend before the frontend.
