# Product comparison — history, location cascade, per-screen ordering

Design doc for the comparison/scan-history feature set. Phase 1 (backend contracts)
is **built**; Phase 2 (smart sort + value judgment) is **documented, not built**.

## 1. Location origin cascade (`ViewerLocationService`)

Every "perto de você" read resolves ONE origin point via an explicit cascade, most
precise first — the first stage that yields a point wins:

| Priority | `locationSource` | Source | Inferred? |
|---|---|---|---|
| 1 | `DEVICE` | real-time device GPS passed on the request (`lat`/`lng`, all-or-nothing) | no |
| 2 | `PROFILE` | the user's profile-set home (`home_latitude`/`home_longitude`) | no |
| 3 | `RECEIPTS_MEDIAN` | median of the household's geocoded receipt-market coordinates | **yes** |
| 4 | `UF_FALLBACK` | centroid (median) of geocoded markets in the household's majority UF | **yes** |
| 5 | `NONE` | nothing (no coordinates, maybe no UF) | — |

The majority UF rides along in every case (state-level fallback). `isInferred()` is true
for `RECEIPTS_MEDIAN`/`UF_FALLBACK` so the FE can nudge "confirme sua localização".

Two entry points, to not break callers: `resolve(user)` (no device fix) and
`resolve(user, deviceLat, deviceLng)` (device-first). `ViewerLocation` carries
`latitude`, `longitude`, `source`, `uf`.

## 2. Markets list (10 km ∪ watched) — "Mercados" screen

`GET /api/v1/markets?scope=ALL|MINE&lat&lng`. `ALL` = markets within the nearby radius
(`bestMarketsNearbyRadiusKm`, 10 km) of the resolved origin UNION the household's watched
markets; `MINE` = only watched. Sorted by `distanceKm` ascending, nulls last — so a market
from a receipt scanned far away (another state, a trip) sinks to the end instead of
flooding the top (this fixed the "markets 3 000 km away flood the list" bug). Watched-but-far
markets are KEPT, never dropped. Each row: `cnpj`, `name` (full legal razão social),
`friendlyName`, `address`, `city`, `state`, `distanceKm` (nullable), `visited` (hasBought),
`watching` (isWatched).

## 3. Per-screen ordering (`sort=CHEAPEST|NEAREST`) + persistent badges

Best-markets and generic-comparison accept `sort` (default `CHEAPEST`):
- `CHEAPEST` = price ascending (historical behavior; best-markets keeps watched-first).
- `NEAREST` = `distanceKm` ascending, nulls last.

The radius/scope cascade (NEARBY → STATE → NATIONAL) is unchanged — `sort` only reorders
the result set. Independently of the order, each offer/row carries persistent
`isCheapest`/`isNearest` flags (decided over the whole result set), so the FE shows
"Mais barato"/"Mais perto" badges regardless of the active sort. A null distance is never
"nearest".

No value judgment ("vale a pena a viagem?") — that is Phase 2 below.

## 4. Scan-history snapshot + delta

`household_product_snapshot` (V140) stores, per `(household_id, snapshot_key)`:
`product_id` (nullable), `generic_name_norm`, `last_best_unit_price`, `last_best_scope`,
`scan_count`, `last_consulted_at`. `snapshot_key` = `product_id` when a canonical product
resolved, else `type:<generic_name_norm>` — a stable non-null unique key (Postgres treats
NULLs as distinct, so a plain unique on `product_id` wouldn't dedupe type-only rows).
Indexed on `(household_id, last_consulted_at DESC)`.

Upserted (best-effort, short `REQUIRES_NEW` tx, never holding a tx across anything slow,
never propagating failure) when:
- the **best-markets** endpoint serves a comparison (price = cheapest row's median);
- the **generic-comparison** endpoint serves a comparison (price = cheapest offer);
- the **barcode scan log** fires (`GET /products/by-ean/{ean}`) — with NO price (just bumps
  `scan_count` + `last_consulted_at`); a null price never clobbers a price a comparison
  captured.

History: `GET /api/v1/price-index/scanned-products` returns the household's consulted
products, deduped per product/type, newest-consult first, capped at 100. Returns ONLY the
stored snapshot (instant, no live recompute, no N+1). The FE lazily fetches the live price
per visible item via the existing comparison endpoints and computes the delta client-side.

## 5. Admin metrics: user-level vs household

The admin user list/detail distinguishes the USER's own numbers from household context:
- `receiptCount`/`totalSpend` = the user's own receipts (`WHERE user_id = user`).
- `householdReceiptCount` (+ list `householdMemberCount`; detail `householdSpend`,
  `householdMemberCount`) = household context, clearly separate, never conflated.

Batch queries: `ReceiptRepository.countByUserIds`/`sumConfirmedTotalByUserIds`/
`statusBreakdownByUserId`/`sumConfirmedTotalByUserSince`; `UserRepository.countByHouseholdIds`.
The "Domicílio N" badge shows when `householdMemberCount > 1`.

---

## Phase 2 (NOT built)

Deferred by design. Documented here so the next session resumes without re-deriving it.

### 2a. "Recomendado" smart sort

A third `sort` value `RECOMMENDED` that balances price against distance instead of
optimizing one axis. Sketch: normalize price and distance within the result set (min-max
to [0,1]), then `score = normalizedPrice + distancePenalty · normalizedDistance`, ascending.
`distancePenalty` is a tunable weight (env-configurable, e.g. `comparison.distance-penalty`)
expressing "how many R$ of normalized price a unit of normalized distance is worth". Offers
with no distance fall back to pure price. Keep `isCheapest`/`isNearest` badges; optionally
add `isRecommended`.

### 2b. "Vale a pena?" value-judgment callout

A per-offer verdict answering "is the cheaper-but-farther store worth the trip?": compare the
saving (`referencePrice − offerPrice`, possibly ×typical basket size) against an estimated
travel cost (distance → fuel/time proxy). Surface a small callout ("economiza R$ X, mas fica
a Y km") — never auto-reordering, just advisory. Needs: a basket-size assumption, a
cost-per-km constant, and a confidence floor (don't advise on a single observation). This is
explicitly OUT of Phase 1.

## 3. Variant-aware canonicalization (the "same product?" guard)

Two receipt items canonicalize to ONE product only when they are the SAME VARIANT. A real
bug: "CHOC DIVINE 50% ZERO 70G" (placeholder EAN `00000001124676`) was absorbed into
"CHOCOLATE DIVINE 70G AO LEITE SGLUT" (real EAN `7899516211450`) because the metadata dedup
and fuzzy name match ignored the distinguishing tokens — a zero-sugar 50% bar and a
gluten-free milk bar are different products.

### Discriminator rule (`VariantDiscriminator`)

Each description yields a signature of distinguishing tokens, detected by PATTERN (not by
enumerating every value). Two strengths:

- **STRONG** — sugar/intensity (`\d+%`, `zero`, `diet`, `light`, `sem acucar`/`s/acucar`/
  `zero acucar`) and dietary claims (`sem gluten`/`sglut`, `sem lactose`/`zero lactose`).
  Rarely dropped from a receipt name, so STRONG present-on-one-side-only IS a real difference.
- **WEAK** — flavor words (`chocolate`, `morango`, `baunilha`, …) and soft descriptors
  (`integral`, `desnatado`, `tradicional`). A receipt often abbreviates these away
  ("LEITE ITAMBE INT" vs "…INTEGRAL"), so WEAK is a difference ONLY when BOTH sides carry a
  (different) weak token — never when one side simply omits it.

A merge is allowed when STRONG signatures are equal AND WEAK signatures don't directly
contradict. Applied at every name/metadata/fuzzy merge point in `CanonicalizationService`
(`canMergeIntoExisting`). EXACT real-EAN match is exempt — an identical GTIN IS the product.

**Variant/line tokens (2026-11-10, dictionary expansion).** A third STRONG class, tagged
`line_*`, covers sub-lines of one brand that are (almost) never dropped from a receipt
name, so presence-on-one-side-only IS a difference (same semantics as STRONG): sugar TYPE
(`demerara` vs the implicit refined), detergent performance lines (`platinum`, `titanium`),
and pasta SHAPES (`parafuso`, `pena`, `espaguete`/`spaghetti`, `tricolor`, `lasanha` —
owner-approved split of cuts). The WEAK flavor vocabulary also grew (Tang/juice flavors
guaraná/tangerina/framboesa/cacau/maçã, detergent perfumes proteína/lavanda/orquídea,
sauce/biscuit lines manjericão/pizza/tomate/queijo/bacon/provolone/crostini). Brand-side
mis-merges (coffees Bom Jesus/Pimpinela/Canecão/Preti collapsing to the generic "café",
etc.) are fixed in the `brand_registry_entries` seed (`V144`), not here — the split
signature is `brand (BrandExtractor) ∪ VariantDiscriminator tokens`, so a new brand splits
on its own. Residual cases beyond a deterministic dictionary (same-word brand vs flavor
collisions the extractor can't disambiguate from context) are left for a later AI pass.

### EAN authority (`EanAuthority`)

A REAL GTIN is the primary match key. A PLACEHOLDER/internal code (`^0{6,}\d*$`, all-zeros,
non-digit, or outside 8–14 digits) is NOT trusted: a placeholder-EAN item is never absorbed
into a product with a DIFFERENT real EAN on name alone (the discriminator must also pass).
Two items with DISTINCT real EANs never merge via name/metadata.

### Audit (report only, no mutation)

`GET /api/v1/admin/products/variant-conflicts` lists EXISTING products that look mis-merged: a
single product whose linked confirmed items carry MORE THAN ONE distinct REAL EAN, OR
inconsistent distinguishing-token signatures (the Divine case). Output = productId +
conflicting item names/EANs/signatures. A REPORT for the owner to review and split MANUALLY
(via the existing merge tools) — it does NOT auto-split or reassign anything.
