# Autofix lessons

> Durable do/don't lessons the autonomous fixer distilled from past runs. It reads
> the most recent ~40 before every attempt so it stops repeating mistakes. Newest
> first. The seed lessons below were distilled by hand from AUTONOMOUS_FIXES.md.

<!-- LESSONS BELOW -->

- [2026-10-08 12:23:39] [[NEEDS-HUMAN] NO-TESTREF] Always end a FIXED reply with a real FQCN#method test reference.

- [2026-10-07 12:13:03] [[NEEDS-HUMAN] NO-TESTREF] Always end a FIXED reply with a real FQCN#method test reference.

- [2026-10-06 12:21:13] [[NEEDS-HUMAN] NO-TESTREF] Always end a FIXED reply with a real FQCN#method test reference.

- [2026-10-05 12:53:13] [[NEEDS-HUMAN] NO-TESTREF] Always end a FIXED reply with a real FQCN#method test reference.

- [2026-10-04 11:27:25] [[NEEDS-HUMAN] NO-TESTREF] Always end a FIXED reply with a real FQCN#method test reference.

- [2026-10-03 10:45:29] [[NEEDS-HUMAN] NO-TESTREF] Always end a FIXED reply with a real FQCN#method test reference.

- [2026-10-02 11:30:18] [[NEEDS-HUMAN] NO-TESTREF] Always end a FIXED reply with a real FQCN#method test reference.

- [2026-10-01 11:58:54] [[NEEDS-HUMAN] NO-TESTREF] Always end a FIXED reply with a real FQCN#method test reference.

- [2026-09-30 11:30:32] [[NEEDS-HUMAN] NO-REPRO] This exact failure has now been independently re-diagnosed 5 times with identical results — stop re-investigating from scratch. A human must verify `E2E_ADMIN_EMAIL` matches `ADMIN_EMAILS` on the dev deployment; also implement the previously-recommended fix (assert `role === "ADMIN"` in the admin-login Postman test script) so the real cause surfaces immediately instead of a downstream 403.

- [2026-09-29 11:44:33] [[NEEDS-HUMAN] NO-REPRO] The "COV" folder duplicates the "Shopping lists (persistent)" request sequence verbatim, so the Delete-before-Add-item ordering bug exists in two places — fixing only the "E2E Flow" copy leaves COV failing; both copies need the Delete step moved to the end.

- [2026-09-28 12:14:21] [[NEEDS-HUMAN] NO-REPRO] In `postman/economizai.postman_collection.json`'s "E2E Flow" folder, the shopping-list sub-sequence is Create → Get one → Rename → **Delete** → **Add item** → Toggle item checked → Remove item — `Delete` must be moved to the end of that sub-sequence (after Add/Toggle/Remove), otherwise every later step 404s against an already-deleted list; this is a collection-ordering defect, not a Java bug, so no server-side test can reproduce it.

- [2026-09-27 11:01:09] [[NEEDS-HUMAN] NO-REPRO] Verified end-to-end: controller mapping (`/api/v1/categorizer/ai/status`, `AiController.java:68`), SecurityConfig rule (`.requestMatchers("/api/v1/categorizer/ai/**").hasRole("ADMIN")`, `SecurityConfig.java:103`), and the Postman URL for step 22 all match character-for-character — no path/typo/order bug in code. `JwtAuthenticationFilter` reloads the user fresh from DB on every request (not cached in the JWT), so a 403 here means the E2E account genuinely lacks `Role.ADMIN` at request time. The admin-login test script (Postman step 17b) only asserts HTTP 200, never `response.json().user.role === "ADMIN"`, so a valid-but-non-admin login silently sets `adminToken` and the failure surfaces two steps later as an opaque 403 instead of at its true source. Root cause is almost certainly `E2E_ADMIN_EMAIL` not exactly matching an entry in the `ADMIN_EMAILS` env var on the target deployment, so `AdminBootstrap` never promotes that account — this is a secrets/config mismatch, not something a unit test can reproduce. (The `ml/predict` 404 WARN in the logs is an unrelated, harmless leftover Postman request pointing at an endpoint retired in commit `ef159a2`, with no assertions attached.) Future fixer: add a role assertion to the admin-login test script so this class of failure points at its real cause immediately.

- [2026-09-26 10:31:50] [[NEEDS-HUMAN] NO-REPRO] When an admin-only-endpoint E2E step gets 403 right after its own login step returned 200, first check whether that login step's test script actually asserts the returned user's `role` is ADMIN (not just HTTP 200) — a valid-but-non-admin account produces exactly this symptom with zero code involved; trace the full JWT/authorities round-trip (token claims → filter → UserDetailsService → getAuthorities()) before assuming a code bug, since Spring Security JWT setups typically re-derive authorities fresh from the DB on every request rather than caching them in the token.

- [2026-09-25 10:45:52] [[NEEDS-HUMAN] NO-REPRO] When an endpoint-removal commit doesn't update the Postman E2E collection (CLAUDE.md requires it), the resulting E2E failure has no Java-side reproduction — it's a docs/test-config gap, fixed by updating `postman/economizai.postman_collection.json` (URL + assertions + auth role) to the replacement endpoint, not by editing source.

- [2026-08-05 08:22:42] [FIX f7f4fa7] Since the test profile uses `ddl-auto: create-drop` (Flyway disabled), entity/migration schema drift can't be caught by a normal @DataJpaTest — reproduce it by letting Hibernate create the entity-correct schema, then using a native `ALTER TABLE ... DROP COLUMN` to force the table back to the actual migration's shape before asserting the query throws.

- [2026-07-29 08:21:53] [[NEEDS-HUMAN] NO-REPRO] When an E2E "export/list" assertion fails right after a receipt-submission step, check whether the submission step's own log shows a validation/skip condition (e.g. blank required payload) before assuming the export/report code is broken — an empty upstream secret (like `E2E_QR_PAYLOAD`) starves every downstream step of data and produces a chain of "no data found" failures that look like separate bugs.
- [seed] Adding a constructor dependency to a controller requires a matching @MockitoBean in its @WebMvcTest slice test, or the context fails to load ("Application run failed"). Update the slice test in the same fix.
- [seed] Postgres-only defects (untyped null bind like `(:since IS NULL OR ...)`, "could not determine data type of parameter") cannot be reproduced on the H2 test profile — the query passes on H2. Reply REPRO_FAIL rather than forcing a fake test.
- [seed] Infra/external log lines are NOT code bugs: Tomcat "Error parsing HTTP request header" (malformed client request, empty MDC), Postgres "terminating connection due to administrator command" (backend killed). Reply REPRO_FAIL fast.
- [seed] Unvalidated negative `?limit` query params reach `Stream.limit(-1)` and throw IllegalArgumentException — clamp with `Math.max(0, limit)` at every entry path (fromRequest AND normalize/canonical constructor), not just one.
- [seed] Client-supplied ids written straight into inserts cause FK violations — resolve the id (existsById) and drop unknown ones to null instead of failing the insert.
- [seed] `@RequestParam List<String>` is required-by-default; an absent param throws MissingServletRequestParameterException (only reproducible via a real servlet container, not @WebMvcTest). Use `required=false, defaultValue=""`.
- [seed] A best-effort call inside a @Transactional method must use `@Transactional(propagation = REQUIRES_NEW)`, or its failure marks the shared tx rollback-only and the outer commit throws UnexpectedRollbackException.
- [seed] Values written to varchar(N) columns must be length-guarded before persist (EAN varchar(14), generic_name/brand varchar(100)) — over-length values crash the whole batch with DataIntegrityViolationException.
