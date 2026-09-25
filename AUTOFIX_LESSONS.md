# Autofix lessons

> Durable do/don't lessons the autonomous fixer distilled from past runs. It reads
> the most recent ~40 before every attempt so it stops repeating mistakes. Newest
> first. The seed lessons below were distilled by hand from AUTONOMOUS_FIXES.md.

<!-- LESSONS BELOW -->

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
