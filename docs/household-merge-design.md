# Household merge / split — data provenance design

> **Status update (2026-09-22): mostly SHIPPED, not a proposal anymore.**
> - **Phase 0 (stop data loss): DONE** — `Receipt.originHousehold` provenance (V48/V67),
>   `HouseholdScoped` on all movable entities, deletion guard for empty households owning data.
> - **Phase 1 (merge + restore): DONE** — `HouseholdMergeService` (generic, transactional,
>   host-wins collisions) + per-category `MergeCategory` selection. Gated behind
>   `economizai.households.merge-enabled` (default false).
> - **Phase 2 (mutual consent): PARTIAL** — `DataShareConsentService` + `data_share_consent`
>   table exist; the full block/proceed/backfill flow isn't fully wired.
> - **Known TODOs before enabling in prod:** merge copies the receipt HEADER only (item-level
>   deep-copy is out of scope in v1 — decide if needed); `LeaveScope` implements
>   `ORIGINAL_ONLY` but `ORIGINAL_PLUS_SHARED`/`BOTH` need a per-membership `joined_at`.
> The design below is still accurate as the reference; treat "PROPOSAL" as historical.

Status: PROPOSAL (for review, not yet implemented)
Context: today `join()` moves the *person* but not their *data*; a joining user's
receipts keep their old `household_id` and get orphaned/deleted. We want a couple
to optionally merge histories, and on split restore each person to their original
data, with a 3-way choice on what to take.

## The mental model (the user's words)
Two people use the app solo → become a couple (one joins the other's household) →
maybe merge their data → eventually split → each goes back to their own data, with
a choice of what to keep.

## Key facts in the current schema (what we build on)
- `Receipt` has BOTH `user_id` (who scanned it — IMMUTABLE) and `household_id`
  (which household it currently belongs to — REWRITTEN on merge). All history
  queries scope by `household_id`.
- `UNIQUE (household_id, chave_acesso)` (V18): the same NF-e may exist in different
  households, but not twice in one. This is the collision point when merging.
- `user_id` is the natural provenance anchor: "my original data" = receipts I scanned.

## What actually moves — full household-scoped data inventory
Receipts are only ONE of ~10 household-scoped entity types. Moving only receipts
would orphan all the rest (same bug, wider). Each entity, and how it should behave:

| Entity | Has user_id? | Per-household UNIQUE | Merge / restore behavior |
|---|---|---|---|
| `Receipt` | ✅ user_id | `(household, chave_acesso)` | Move by provenance; collision = keep-one-remember-both |
| `ShoppingList` | createdBy | — | Move with creator; no collision (free-form) |
| `ManualPurchase` | ✅ user | `(household, product_id)` | Move by user; collision = keep-one-remember-both |
| `ConsumptionSnooze` | ❌ | `(household, product_id)` | Household-level pref, not personal — see Q6 |
| `HouseholdProductCategoryOverride` | ❌ | `(household, product_id)` | Household pref — Q6 |
| `HouseholdCustomCategory` | ❌ | `(household, name)` | Household pref — Q6 |
| `HouseholdProductAlias` | ❌ | `(household, product_id)` | Household pref — Q6 |
| `HouseholdMarketAlias` | ❌ | `(household, market_cnpj)` | Household pref — Q6 |
| `ManualBrandPreference` | ❌ | `(household, generic_name)` | Household pref — Q6 |
| `PriceObservation` + `...Audit` | ❌ (by design, LGPD) | — | **DO NOT move per-user.** No user provenance; it's the anonymized shared index. Stays put. |

**Two new problems this surfaces:**
1. **5+ more per-household UNIQUE constraints** collide on merge exactly like chave
   does (`(household, product_id)` ×3, `(household, name)`, `(household, generic_name)`,
   `(household, market_cnpj)`). The keep-one-remember-both policy (D3) must apply to
   ALL of them, not just receipts.
2. **Provenance only works for entities WITH a user_id** (`Receipt`, `ManualPurchase`,
   `ShoppingList.createdBy`). The 6 household-pref entities have NO personal owner —
   they belong to the household as a unit, so "restore to original person" is
   ill-defined for them. See Q6.

## Decisions to lock (each needs your ✅)

### D1. Merge is OPT-IN, per joining user
On `join`, the joiner chooses `bringData: true|false`.
- `false` → only their membership moves; their receipts stay behind on their
  original household (NOT deleted — see D4). They see only the shared/target
  household's data going forward.
- `true` → their receipts are reassigned into the target household (subject to D3
  collision handling).

### D2. Provenance must survive so we can restore on leave
We need to know, for every receipt, **which household it originally belonged to**,
independently of where it currently lives. Two options:

- **D2a (recommended): add `origin_household_id` to `receipt`** — set once at scan
  time (= the scanning household), never rewritten. `household_id` is the "current"
  location; `origin_household_id` is "home". Restore = move receipts whose
  `origin_household_id` is mine back. Cheap, one column, no new table.
- D2b: a separate `receipt_household_history` audit table (every move logged).
  More flexible (full timeline) but heavier; overkill for a 2-person couple case.

Going with **D2a** unless you want the full audit trail.

### D3. Collision: same NF-e on both accounts (the `UNIQUE` landmine)
When merging, if the joiner has a chave the target household already has:
- Keep the target household's existing row (it's already "home" there).
- The joiner's duplicate is NOT inserted as a second row (would violate the unique
  constraint). Instead we **tag it as shadowed**: keep the row on its origin
  household, mark it so it isn't double-counted, so on split it can still go home.
- Policy when they differ (one CONFIRMED, one PENDING): prefer CONFIRMED; if both
  CONFIRMED, prefer the target's (arbitrary but deterministic). Log it.

### D4. Leaving the original household must not destroy data
Today `join`/`leave` delete a household when it hits 0 members. If we keep a
joiner's receipts parked on their original (now 0-member) household for later
restore, that delete would orphan them. So: **do NOT delete a household that still
owns receipts** (even with 0 members) — it's a dormant "home" until the user
restores. (Alternative: reassign parked receipts to a per-user holding household.)

### D5. Split (`leave`) offers a 3-way choice
On leave, user picks `takeData`:
1. `ORIGINAL_ONLY` — restore exactly the receipts whose `origin_household_id` was
   mine; leave everything else in the shared household.
2. `BOTH` — take my original + a COPY of everything from the shared household.
   ⚠️ This duplicates the partner's data into my new solo household. Needs a clear
   product decision (privacy: am I walking away with my ex's receipts?). Collisions
   resolved as in D3.
3. `ORIGINAL_PLUS_SHARED` — my original + receipts added DURING the shared period
   (scanned while we were one household), but NOT the partner's pre-merge history.
   "Shared period" = receipts on the shared household with `created_at` between my
   join and my leave. (Requires knowing the join instant — see open Q2.)

## DECIDED (2026, user)
- Provenance: **`origin_household_id` column** (D2a).
- Duplicate-on-merge: **keep one, remember both** (D3) — keep target's row (prefer
  CONFIRMED), park the joiner's dup shadowed on its origin so it restores on split.
- Split data scope: **consent-gated, mutual** (replaces the old D5/BOTH). See below.

## D6. Consent for cross-person data on split (MUTUAL)
A user may only take/keep receipts that ANOTHER person scanned if that person
consents. This works both directions:
- **Leaver wants the partner's data**: leaver requests; partner must approve before
  those receipts are copied into the leaver's new solo household.
- **Stayer wants to keep the leaver's data**: when someone leaves, the receipts they
  scanned would normally go home with them; for the stayer to KEEP a copy, the
  leaver must approve.
- Default with NO consent: each person ends up with exactly the receipts they
  scanned (by `user_id`) plus their pre-merge original. Nobody walks away with the
  other's scans unless explicitly approved.

Shape: a `data_share_consent` request (requester, grantor, scope, status
PENDING/APPROVED/DENIED, expiry). A leave that requests partner data is either:
(a) blocked until consent resolves, or (b) proceeds immediately with only the
leaver's own data, and the partner's data is copied later IF/when consent is
approved. **Need your call — see Q4.**

## LGPD note
- `PriceObservation` (anonymized index) is derived per-receipt and carries no
  user_id; merging/splitting receipts must not double-count or leak there. Confirm
  the canonicalization path keys on receipt id, not household, so a move is inert.

## D7. Granular per-category merge selection (user, 2026)
Join is opt-in AND itemized: the joiner first chooses merge yes/no, then ticks WHICH
data categories to bring (receipts, shopping lists, custom categories, aliases,
brand prefs, …). Best-effort merge of each selected category.
- **Conflict rule (global):** on any per-household UNIQUE collision, **the receiving
  (host) household's value WINS**; the joiner's conflicting row is parked shadowed
  (not lost — restorable on split). This is the single deterministic tie-breaker for
  receipts AND all the pref tables.
- Shopping lists: **copy to both** on split (collaborative; both keep shared-period
  lists). Subject to consent if it contains the partner's contributions (D6).

## D8. Phased rollout (ship safety first; merge later)
The merge/consent machinery is large and you may keep it OFF initially. Split it:
- **Phase 0 (do now, no behavior change for users):** stop the data loss. Add
  `origin_household_id` (backfill). Guard household deletion so a 0-member household
  that still owns data is NOT deleted. join/leave still move only membership, but
  nothing gets orphaned/deleted anymore — reversible foundation.
- **Phase 1:** join with `bringData` + per-category selection (D7); merge with the
  host-wins collision rule; restore-original on leave via `origin_household_id`.
- **Phase 2:** mutual consent (D6) for taking/keeping another person's data; the
  3-way split scope; copy-to-both shopping lists.
- A feature flag gates Phase 1/2 so they can ship dark and flip on when ready.

## Open questions for you
- Q2: We need a "joined-at" timestamp per (user, household) to define the shared
  period. Add `joined_at` to the user, or a small membership table?
- Q3: When BOTH partners scanned the SAME NF during the shared period, on split who
  keeps it? (Both get a copy? Whoever scanned first? It's shared-period so arguably
  both.)
- Q4: Does leave BLOCK on a pending consent request, or proceed with own-data-only
  and back-fill the partner's data later if approved?
- Q5: How are consent requests surfaced — reuse the existing notification system
  (in-app/email), or a dedicated pending-requests endpoint the FE polls?

## Rough implementation shape (once decisions land)
1. Migration: add `receipt.origin_household_id` (backfill = current household_id);
   add `joined_at` (or a membership table) if option 3 stays.
2. `join(bringData)`: if true, reassign receipts to target with D3 collision pass.
3. `leave(takeData)`: create solo household, move/copy receipts per the 3-way
   choice, restoring by `origin_household_id`.
4. Guard household deletion: never delete a household that still owns receipts.
5. New request DTOs + Swagger + Postman + CHANGELOG; tests for every branch incl.
   the collision and the "split after merge restores original" round-trip.
