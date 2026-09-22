# economizai - Project Conventions

## Overview
economizai is a collaborative price-intelligence app built on Brazilian NFC-e
receipts — users scan their grocery receipt QR codes, the system parses every
line item, and the anonymized result powers both personal dashboards and a
shared price index. Built with Java 21 + Spring Boot 4. See `HELP.md` for
vision, architecture, and roadmap; `MONETIZATION.md` for revenue strategy.

GitHub repo: `economizaai` (https://github.com/XandiVieira/economizaai.git)

## Communication Style
- Keep responses SHORT and concise. Avoid verbose explanations, code dumps, or excessive context.
- When drafting team messages or Slack/PR comments, default to brief and direct — the user will ask for more detail if needed.
- For explanations, omit code snippets and IDs unless explicitly requested.

## Tech Stack
- Java 21, Spring Boot 4.0.6, Maven
- PostgreSQL
- Flyway (database migrations)
- Spring Security + JWT (authentication)
- Lombok (boilerplate reduction)
- SLF4J (logging)
- ZXing (server-side QR decoding when needed)
- Tess4J/Tesseract (OCR of the printed chave de acesso; native lib on the host — see DEV_NOTES.md)
- Jsoup (HTML parsing of SEFAZ NFC-e pages)
- springdoc-openapi (Swagger UI)

## Code Style

### General
- Use `var` for local variables
- Prefer functional style (streams, lambdas, Optional chaining) when reasonable
- Minimal comments — only when logic is non-obvious
- No unnecessary abstractions or premature generalization
- Domain language is Brazilian Portuguese where it's a legal/domain term (NFC-e, CNPJ, CPF, SEFAZ, chave de acesso) — do not translate these
- **No inline fully-qualified names.** Always import the class at the top of the file and reference it by simple name. Avoid `org.foo.bar.Baz.method(...)` or `@org.foo.bar.SomeAnnotation` mid-code. The only exception is when two imported classes would collide (e.g. you're using both `java.sql.Date` and `java.util.Date` in the same file) — then qualify only the conflicting one.
  - **This applies to test code too**, including Mockito/JUnit args: write `.thenReturn(Map.of(...))` after `import java.util.Map;`, never `.thenReturn(java.util.Map.of(...))`. Same for `List.of`, `Arrays.asList`, `Stream.concat`, `Collectors.toMap`, etc.
  - **JPQL `@Query` strings:** don't bake an FQN into the query. Use a string literal for enum comparison (`r.status = 'CONFIRMED'` — the established pattern in this codebase), which Hibernate coerces to the enum. Reserve a fully-qualified enum reference only where JPQL type inference genuinely needs it (rare).
- **Meaningful names, atomic methods.** No single-letter or cryptic-abbreviation variables anywhere, including lambda params (`receipt`, not `r`; `notificationPayload`, not `p`). A method does ONE thing at one level of abstraction — orchestration methods read as a list of named steps; detail lives in small private methods named for what they do. A method name must tell the whole truth: no hidden saves behind a `get`/`check`/`resolve` name.

### Transactions & Long-Running Work
- **Never hold a DB transaction across an outbound HTTP call** (SEFAZ fetch, captcha solve, SMTP/Expo/Twilio dispatch, geocoding). Each in-flight call pins a Hikari connection and starves the app. Pattern: do reads in a short tx (or plain repository calls), run the HTTP work untransacted, persist results in a short `TransactionTemplate` block (see `ReceiptIngestionService`).
- `TransactionTemplate` commits inside the calling method, so commit-time failures (constraint violations at flush) land in your catch block instead of escaping the `@Transactional` proxy — prefer it over `@Transactional` whenever you need to react to persistence failures.
- **Async state machines need a sweeper.** Any row a background job is supposed to transition (e.g. PROCESSING receipts) must have a scheduled sweeper that force-fails rows older than a timeout — restarts, pool rejections, and unrecordable failures WILL strand rows otherwise (see `ProcessingReceiptSweeper`).
- **Async dispatch after commit must handle pool rejection** — the row is already committed; catch the rejection and mark the row failed, or the client polls forever.

### Fallbacks to Paid APIs
- Gate paid fallbacks (Infosimples, captcha solvers) on failure types they can plausibly rescue (portal down, solver unavailable). Deterministic failures (bad input, missing markup) must propagate WITHOUT spending money. Catch specific exception types, never a blanket `RuntimeException`.

### SEFAZ Portal Adapters (captcha & scraping)
- **Captcha is never 100% on the first try.** Any captcha-gated adapter (MS reCAPTCHA, SC Turnstile) MUST wrap its fetch in a retry loop that RE-SOLVES a fresh token on rejection — a solved token is occasionally rejected by the portal, and without retry a valid receipt fails and forces a needless rescan. Bound it (`*-max-attempts`, default 3) so a hostile portal can't grind forever (each attempt costs a paid solve). See `MsDfePortalAdapter` / `SantaCatarinaNfcePortalAdapter` for the shape. When adding a 5th captcha state, copy this pattern.
- **Never hardcode portal form field IDs.** JSF/ASP.NET portals renumber auto-generated component IDs (`j_idtNN`) without notice — hardcoding them silently posts empty values and every receipt for that state breaks (MS did exactly this). Locate fields by stable attributes instead (the chave input by `maxlength=44`, dropdowns by `<select>`, etc.).
- **Retry classification**: transient (5xx, timeouts, empty body, captcha-rejected) → retry; deterministic (4xx bad chave, missing sitekey/viewstate, no solver configured, invalid QR) → propagate immediately. Distinguish by exception type in the retry loop's catch.
- **Each new state needs one real NF to verify** — the QR URL shape (full signed URL vs bare chave) AND the item/EAN semantics. Bare-chave re-consult does NOT work on the `dfe-portal.svrs.rs.gov.br` QR endpoint (needs the QR's signature params); real QR scans do. **Exception (validated 2026-09-22):** the legacy RS portal `sefaz.rs.gov.br/ASP/AAE_ROOT/NFE/SAT-WEB-NFE-NFC_*.asp` DOES render the full DANFE (items incl. qty/unit/price) from the **bare 44-digit chave**, public/logged-out, server-side — GET `_1.asp?chaveNfe=<44>` (sets session cookie) → POST `_2.asp` `HML=false&chaveNFe=<44>&Action=Avançar`. This unblocks mass import-by-chave for RS (see `docs/ONBOARDING_IMPORT.md`). Store a fixture under `src/test/resources/fixtures/sefaz/<state>/`.

### Security Patterns
- **Client IP**: always via `ClientIpResolver` — it trusts `CF-Connecting-IP` / the LAST `X-Forwarded-For` hop. Never read the first XFF hop (client-spoofable).
- **Short verification codes** (password reset, OTP): store only a hash, compare constant-time (`MessageDigest.isEqual`), count failed attempts against the active code and lock after a small budget. Never rely on the code space alone.
- **Endpoints that mutate GLOBAL state** (canonical products, categorizer catalogs/dictionaries) are ADMIN-only in `SecurityConfig` from day one — "any authenticated user" is only acceptable for household-scoped data.

### Fail-Fast Validation
- Validate everything decidable synchronously AT SUBMIT (unsupported UF, caps, duplicates) and return a localized 4xx. Never accept work into an async pipeline that is guaranteed to fail — the user gets a worse, slower error and the machine key leaks into the response.
- User-facing failure fields carry BOTH the machine key (`parseErrorReason`) and a localized message (`parseErrorMessage`) — the FE never renders raw keys.

### Logging
- Use SLF4J (`org.slf4j.Logger` / `org.slf4j.LoggerFactory`) via `@Slf4j` Lombok annotation
- Every feature must have relevant log entries (info for business events, debug for flow, warn/error for failures)
- Use parameterized messages: `log.info("Receipt {} parsed for user {} with {} items", receiptId, userId, itemCount)`
- Never log raw CPF, full receipt access keys, or JWTs — mask if needed
- **MDC correlation:** every request log line carries `req=<8-char>`, `user=<email>`, plus `rcpt=<8-char>` and `item=<8-char>` when the flow touches a receipt or item. To trace one receipt end-to-end, grep by `rcpt=<id>`. Set MDC via `MDC.put(MdcContextFilter.RECEIPT_ID, ...)` etc. — `MdcContextFilter` clears it at request end.
- **One INFO line per decision, not per loop iteration.** Use the format `<event>.<outcome> key1=value1 key2=value2`. Examples: `item.created_from_ean`, `item.matched_by_alias`, `item.unmatched`, `submit ok`, `confirm ok`. Aggregates (`canonicalize done matched=X created=Y`) come at the end as a separate INFO.

### Testing
- **Test mail goes to `alexandre@economizaai.app` — never the main inbox.** The point is the RECIPIENT: emails generated by test activity (contact/beta submissions, admin alerts, verification codes) must land in the dedicated QA mailbox, not `contato@economizaai.app`. Two halves:
  - **Dev environment recipients**: `CONTACT_RECIPIENT` and `BETA_RECIPIENT` on the Render dev service point to `alexandre@economizaai.app` (QA mailbox changed from `developer@economizaai.app` on 2026-08-26). Never point them back at the main box on dev. Prod keeps the real recipients.
  - **Test account addresses — domain is ALWAYS `@economizaai.app`.** Every test user created (E2E flows, autofix/operator, seed data, manual) MUST be a **randomized local part on `@economizaai.app`** — e.g. `test92843@economizaai.app`, `e2e-<random>@economizaai.app`. Randomize the name; keep the domain constant. **Why (FE dev, 2026-08-20):** the marketing/analytics dashboard is cleaned by filtering the `economizaai.app` domain to bulk-delete test data, so test users must never land on any other domain (no gmail/example.com/fake domains). CAVEAT: the mailbox provider (Titan/GoDaddy) doesn't deliver to arbitrary/plus-aliased addresses — mail to them bounces silently. When a test must actually RECEIVE the e-mail (report delivery, verification codes), use the shared QA account registered with the exact address `alexandre@economizaai.app` instead of registering a new one.
- All new code must be covered by relevant unit tests
- Tests live in `src/test/java` mirroring the main package structure
- Use JUnit 5 + Mockito for unit tests
- Use `@DataJpaTest` for repository tests, `@WebMvcTest` for controller tests
- Receipt parsers must be covered by tests with real SEFAZ HTML fixtures (saved under `src/test/resources/fixtures/sefaz/<state>/`)

### API & Postman
- All APIs are versioned: `/api/v1/...`
- A Postman collection is maintained at `postman/economizai.postman_collection.json`
- Every endpoint change (create, update, remove) must update the Postman collection
- The collection includes an **E2E Flow** folder — a sequential test suite that runs all requests in logical order, each setting data for the next. This must also be updated on any endpoint change.
- REST endpoints follow standard conventions: plural nouns, proper HTTP methods

### Internationalization (i18n)
- All user-facing messages use i18n via Spring `MessageSource`
- Message files: `src/main/resources/i18n/messages_en.properties`, `messages_pt.properties`
- Exceptions extend `DomainException` with a `messageKey` and optional `arguments`
- `LocalizedMessageService` translates using `LocaleContextHolder` (resolved from `Accept-Language` header)
- Default locale: Portuguese (pt) — this is a Brazilian product
- Add new message keys to both `_en` and `_pt` properties files

### Database
- Flyway migrations in `src/main/resources/db/migration`
- Migration naming: `V{number}__{description}.sql`
- Never modify existing migrations — create new ones
- Money fields use `NUMERIC(12,2)` (R$). Quantity fields use `NUMERIC(12,3)`.
- Timestamps in `TIMESTAMP WITH TIME ZONE`, default `now()`
- Computed money values are `setScale(2, HALF_UP)` BEFORE persisting, so the in-memory value matches what `NUMERIC(12,2)` stores
- Associations are `FetchType.LAZY` always; `@OneToMany` collections read by list endpoints get `@BatchSize(size = 50)` so a page never issues one item-query per row
- Explicit Hikari sizing lives in `application.yaml` (`maximum-pool-size`, `leak-detection-threshold`) — revisit when adding thread pools

### Privacy & Anonymization (LGPD)
- `PriceObservation` (the collaborative index atom) **must never carry user_id** in its primary table
- Audit trail (which receipt produced which observation) lives in a separate, internal-only join table
- Aggregate queries exposed publicly or to B2B clients enforce **k-anonymity** (start K=3) at the query layer
- CPF, when present on receipts, is stripped before raw HTML is persisted (regex sweep on the snapshot)
- Any new endpoint that exposes aggregated data must have a test asserting k-anonymity holds

### Monetization Hooks
- Every feature scoped to FREE vs PRO must respect `User.subscriptionTier`
- New paywalls go through a single gating service — never inline `if (user.tier != PRO)` in controllers
- See `MONETIZATION.md` for the tier matrix

### Project Conventions
- Primary language is Java (Spring Boot). Follow existing patterns in the codebase — do not remove old methods or deviate from established patterns without explicit approval.
- Make minimal changes for migrations/refactors; preserve existing method signatures unless asked otherwise.

### Git & Commits
- **Atomic commits** — each commit should represent one logical change (one feature, one fix, one refactor)
- Do not bundle unrelated changes in the same commit
- **Never mention Claude, AI, or any co-author in commit messages** — no `Co-Authored-By` lines, no references to AI assistance
- This is a personal project on a professional MacBook — git user is configured locally per-repo to avoid mixing accounts
- Local config: `user.name = Alexandre Vieira`, `user.email = xandivieira@gmail.com`
- Remote: `https://github.com/XandiVieira/economizaai.git`
- Never touch the global git config

## Git Workflow
- **Clean 2-env split (cutover done 2026-09-22).** Two Render services on one repo:
  - **prod** = `economizai-api-prod`, branch **`main`**, domain **`api.economizaai.app`**, DB `economizai-db-prod` (the REAL data users hit).
  - **dev** = `economizai-api-dev`, branch **`development`**, domain **`api-dev.economizaai.app`**, DB `economizai-db-dev` (stale copy = test data).
  - Both auto-deploy on push to their branch. **Pushing `development` = deploy to DEV only (safe, no real users).** Prod ships ONLY by merging `development` → `main` + push, on the owner's explicit go. Never commit directly to `main`. Full topology in `INFRASTRUCTURE.md`.
- **GitHub account: ALWAYS `XandiVieira` for anything economizai** — if another account is active/cached (keychain may serve `wuupsuser`), override before acting: `GH_TOKEN=$(gh auth token --user XandiVieira) gh …`, or a one-off credential helper for `git push`.
- **Deploy windows:** pushing `development` now only redeploys the **dev** service — safe to push anytime (no real-user impact). The daytime-blip caution now applies to the **prod release** (merge → `main`): that redeploys `api.economizaai.app` and blips real users, so gate prod releases on the owner's go (batch, prefer night). `main` == `development` in code before a release so prod doesn't regress.
- **ALWAYS pull before push.** Before any `git push`, run `git pull --rebase origin <branch>` first so the push lands on the current tip. This repo has an autonomous watchdog that also pushes — racing it causes rejected pushes and rebase conflicts. Pull-rebase-then-push every time.
- Before reviewing or analyzing a branch, ALWAYS run `git fetch` and confirm with the user which branch to work on if there's any ambiguity.
- Do NOT propose code fixes when the user is asking for understanding/diagnosis only — wait for explicit fix request.
- When the user says "revert", confirm exactly what state they want before acting.

## Diagnosis Before Fixes
- When investigating bugs, identify root cause first. Do not propose workarounds (e.g., cleanup scripts, defensive checks) until root cause is confirmed.
- Ask before making code edits during investigation sessions.

## Project Documentation
- `OPERATOR.md` is the **AI-owned SDLC constitution** — roles (AI operator vs owner), the autonomous-vs-GATED split (what the AI ships vs what needs the owner's button), the continuous loop, and the secrets/environment map. Read it before any autonomous/scheduled action; update it when the guardrails change.
- `HELP.md` is the project development log — vision, architecture, phased roadmap, session log. Update on significant progress, decisions, or architectural changes.
- `MONETIZATION.md` is the living revenue strategy. Update when pricing, tiers, or B2B plans evolve.
- `API.md` is the FE-facing endpoint walk-through. Update when endpoints/payloads change.
- `CHANGELOG.md` is the FE-facing **diary** of meaningful changes — new endpoints, response-shape changes, behavior changes the FE needs to know about. **Newest entry always at the top.** Add an entry on every meaningful change (a feature shipped, a contract change, a bug the FE was likely tripping on). NOT every commit — only the user-visible / FE-visible deltas. Date stamp every entry. This is read by the FE's tooling to understand "what changed since I last looked."
- `DEV_NOTES.md` lists shortcuts/hacks that are fine for dev but must be revisited before prod (local-disk storage, stub services, weak secrets, etc). Add an entry whenever you ship a "good enough for dev" choice with a known prod gap.
- `INFRASTRUCTURE.md` is the single source of truth for the **self-hosted dev server** (Quick Links to API/Swagger/health/logs at the top; the full stack, auto-deploy, backups, monitoring, and a dev→prod migration map). Update when infra changes.

## Build & Run
```bash
./mvnw spring-boot:run        # run the app
./mvnw test                    # run tests
./mvnw clean package           # build jar
```
