# Frontend implementation report

Handoff notes for the **frontend** Claude — backend deltas that need FE work,
with concrete endpoints and UX guidance. Newest at the top. For the full API
contract see [API.md](./API.md); for the running diary see [CHANGELOG.md](./CHANGELOG.md).

- **API base:** `https://economizai.economizai.workers.dev/api/v1` (self-hosted)
- **Swagger:** `https://economizai.economizai.workers.dev/swagger-ui/index.html`
- Auth: `Authorization: Bearer <jwt>` on every call below. Send `Accept-Language: pt`.

---

## Implementation status

| Feature | Date | FE status |
|---|---|---|
| ETags + caching on `/dashboard` and `/insights/spend` | 2026-06-06 | ✅ Done — `requestConditional`, `homeCache` stores ETags, cache-bust on mutations |
| Items by category screen | 2026-06-06 | ✅ Done — `ItemsByCategoryScreen` + `itemsService` |
| Price alerts screen | 2026-06-06 | ✅ Done — `PriceAlertsScreen` + `alertsService` |
| Category chip on receipt items | 2026-05-04 (BE) / 2026-06-06 (FE) | ✅ Done — `ReviewScreen` reads `category` from `ReceiptItemResponse` |
| `PRICE_DROP` notification type | 2026-06-06 | ✅ Done — `NotificationsScreen` TYPE_CONFIG includes it |

---

## 2026-06-07 — Custom categories + migration (new screen)

Users can create their own categories and move products into them. Household-scoped (only that household sees it). Exactly the flow you described:

```
GET    /api/v1/categories            → [{id:null,name:"GROCERIES",custom:false}, ..., {id,name:"Frutas",custom:true}]
POST   /api/v1/categories            { "name":"Frutas" }            → 201 {id,name,custom:true}
DELETE /api/v1/categories/{id}                                       → 204 (its overrides revert)
POST   /api/v1/categories/migrate    { "productIds":[...],
                                        "targetCategory":"GROCERIES" | null,
                                        "targetCustomCategoryId":"<uuid>" | null }  → {migrated, skipped}
```

**Migration screen flow:**
1. User picks a source category (e.g. GROCERIES) → list its items with `GET /items?category=GROCERIES`.
2. Render each item with a **checkbox** + a **select-all/none** toggle.
3. User creates/picks a target category (`POST /categories` or chooses an existing custom one).
4. On "Migrate": `POST /categories/migrate` with the checked `productIds` + the target (one of `targetCategory` enum OR `targetCustomCategoryId`).
5. View the custom category's items afterwards with `GET /items?customCategoryId=<uuid>`.

Notes:
- **`ReceiptResponse.items[*].productId` is now populated** (`uuid` | `null`) — so you can also run migration straight from the receipt review screen using each item's `productId`, not just from `/items`.
- `migrate` needs **exactly one** of `targetCategory` / `targetCustomCategoryId` (else `400`).
- After migration the item's `category` field reads back as the **custom category name** (it's a display string now, not always an enum).
- Category chips: globals use the enum→PT label map; custom categories use their `name` as-is.

---

## 2026-06-07 — User category correction (new, on the review screen)

Let the user fix a wrong category on a receipt item. It's **household-scoped** — changes only what this household sees; the global product is untouched.

```
PUT /api/v1/receipts/{id}/items/{itemId}/category   body: { "category": "MEAT_DAIRY" }
→ 200 ReceiptResponse (with the corrected category applied)
→ 400 if the item has no linked product yet (rare; only fully-unmatched items)
```

- Category values: `GROCERIES, BEVERAGES, PRODUCE, MEAT_DAIRY, BAKERY, CLEANING, PERSONAL_CARE, OTHER` (reuse your existing category labels). No custom categories yet.
- After the call, the corrected category shows everywhere this household reads the product: `GET /receipts/{id}` and `GET /items`. (Insights/dashboard aggregates still use the global category for now.)
- UX: on the review screen, tapping an item's category opens a picker → `PUT` the chosen enum. Treat the returned receipt as the source of truth for the new category.

---

## 2026-06-06 — Categorization debug endpoint (for reporting bad categories)

If you spot a wrong category (e.g. "Milho" showing as Higiene), check it directly:
`GET /api/v1/categorizer/classify?description=Milho&description=Lays` → per-term result with
the deciding `source` (DICTIONARY vs ML) + the dictionary/ML breakdown. No persist. Use this
to give the backend exact terms + what they resolve to instead of screenshots.

---

## 2026-06-06 — Caching + ETags on /dashboard and /insights/spend (FE can lean on it)

> ✅ **FE implemented 2026-06-06.** See `src/services/api.ts` (`requestConditional`), `src/services/homeCache.ts` (ETag storage + `invalidateDashboardAndInsights`), `src/services/dashboardService.ts`, `src/services/insightsService.ts`, `src/screens/HomeScreen.tsx` (sends cached ETag, skips update on 304), `src/screens/ReviewScreen.tsx` (busts cache after confirm/reject/delete).

The backend now caches these two heavy calls and supports conditional requests. **No contract change** — same endpoints, same response shapes. What changed is how fast/cheap they are:

1. **Send `If-None-Match`.** Store the `ETag` header from each `/dashboard` and `/insights/spend` response; on the next call send it back as `If-None-Match`. If unchanged you get **`304 Not Modified` with no body** — keep your last good payload and reuse it (don't try to parse the empty 304 body).
2. **You can keep your own TTL cache** (2 min / 5 min) — it now stacks with the server's. But you can also be less conservative: after a mutation (confirm/reject/delete a receipt, add/edit an item) the server cache is invalidated instantly, so a refetch returns fresh data immediately — no need to wait out your TTL. Recommended: **bust your local cache for dashboard+insights right after those mutations** and refetch.
3. **Unread badge is always live** — `dashboard.unreadNotificationCount` is never cached, so it's correct even on a cache hit.
4. Minor: the dashboard's `communityPromosNearby` can be up to ~2 min stale (network-wide data). Everything from your own actions is instant.

---

## 2026-06-06 — Filter items by category (new screen)

> ✅ **FE implemented 2026-06-06.** `ItemsByCategoryScreen` with horizontal category chip strip, infinite-scroll FlatList, PROMO badge, empty state. `itemsService.getItems(filters, token)` builds the query params handling multi-value arrays. Entry point: tapping a category row in `CategoryBreakdown` on `HomeScreen` (`onCategoryPress` prop). `PriceAlertsScreen` is reachable from `SettingsScreen`.

**Goal:** user taps a category (e.g. "Carnes e Laticínios") → sees every item
they've bought in that category, across all receipts.

**Endpoint:** `GET /api/v1/items` — returns a paginated list of purchased line
items. All filters optional; combine freely.

```
GET /api/v1/items?category=MEAT_DAIRY&page=0&size=20
```

Other filters you can pass (same query for many screens — don't make new calls):
`from`, `to` (ISO datetime), `marketCnpj` (repeatable), `marketCnpjRoot`,
`category` (repeatable → OR), `productId` (repeatable), `ean` (repeatable),
`minReceiptTotal`, `maxReceiptTotal`, `page`, `size`.

**Response:** Spring page.
```json
{
  "content": [
    {
      "itemId": "uuid",
      "productId": "uuid|null",
      "category": "MEAT_DAIRY|null",
      "displayDescription": "Leite Italac 1L",   // use this for the row title
      "rawDescription": "LEITE ITALAC 1L",
      "friendlyDescription": "Leite Italac 1L|null",
      "ean": "7891234567890|null",
      "quantity": 1.000,
      "unit": "UN",
      "unitPrice": 5.49,
      "totalPrice": 5.49,
      "nfcePromoFlag": false,
      "receiptId": "uuid",        // deep-link to the receipt detail
      "marketName": "Zaffari",
      "marketCnpj": "93015006005182",
      "purchasedAt": "2026-05-15T10:00:00"
    }
  ],
  "totalElements": 42, "totalPages": 3, "number": 0, "size": 20
}
```

**Category chips** — the API uses these enum values; map to PT labels for display:

| enum (send this) | label (show this) |
|---|---|
| `GROCERIES` | Mercearia |
| `BEVERAGES` | Bebidas |
| `PRODUCE` | Hortifrúti |
| `MEAT_DAIRY` | Carnes e Laticínios |
| `BAKERY` | Padaria |
| `CLEANING` | Limpeza |
| `PERSONAL_CARE` | Higiene e Cuidado Pessoal |
| `HEALTH` | Saúde |
| `OTHER` | Outros |

**UX / behavior notes:**
- Scope is **confirmed purchases only** (excluded items are dropped server-side) — no client filtering needed.
- Default sort is newest first (`purchasedAt` desc). No sort param yet — ask backend if you need one.
- Empty result is an **empty page** (`content: []`, `totalElements: 0`), not a 404 — render an empty state.
- Row title: prefer `displayDescription`. Show `marketName` + `purchasedAt` as subtitle, `totalPrice`/`unitPrice` on the right. `nfcePromoFlag: true` → "promoção" badge. `category: null` means the item isn't categorized yet (uncategorized products); it won't appear under any category filter.
- **Drill-down pattern:** the existing `/insights/query` returns aggregate buckets with a `key` (a productId, category, or CNPJ). Tap a bucket → call `/items` with that key as a filter to list the underlying items. Same data, two granularities — reuse one list component.

**Pagination:** standard Spring — `?page=&size=`, read `totalElements`/`totalPages`/`number`.

---

## 2026-06-06 — Price alerts ("avise-me quando") (new screen)

> ✅ **FE implemented 2026-06-06.** `PriceAlertsScreen` lists alerts (GET), deletes (DELETE), and creates via bottom-sheet modal with debounced product search + threshold price entry (POST). `alertsService` wraps all three endpoints. Entry from `SettingsScreen` → "Alertas de Preço". `PRICE_DROP` notification type added to `NotificationsScreen` TYPE_CONFIG.

Lets a user be notified when a product drops below a price.

```
POST   /api/v1/alerts      { "productId": "<uuid>", "thresholdPrice": 5.99, "radiusKm"?: 5, "active"?: true }  → 201
GET    /api/v1/alerts      → list of the user's alerts
DELETE /api/v1/alerts/{id} → 204
```

- One rule per (user, product) — re-`POST`ing the same product **updates** it (safe to use as upsert).
- `radiusKm` optional (from the user's home); omit for "anywhere".
- When a rule fires it creates a notification of type **`PRICE_DROP`** — it shows up in the existing notifications inbox (`GET /notifications`) and is pushed if a push token is registered. No separate fetch needed beyond the inbox you already have.
- Full contract: API.md §10e.

---

## 2026-05-04 — `category` on receipt items (FE catch-up)

> ✅ **FE implemented 2026-06-06.** `ReceiptItemResponse.category: string | null` added to the type. `ReviewScreen` reads it and renders a small colored chip (using `CATEGORY_CONFIG`) on the quantity line of each item row. Items with `category: null` or that are excluded show no chip.

The backend has been returning `category` on `ReceiptResponse.items[*]` since 2026-05-04. This is the item-level `ProductCategory` (`GROCERIES`, `BEVERAGES`, …) or `null` when the item hasn't been canonicalized yet. The FE was not yet reading or displaying it.

---

## Notes
- Swagger is auto-generated from the live build — if an endpoint isn't in
  `/v3/api-docs`, the server is running an older image (rebuild on the host).
- No data-model/breaking changes in the above — these are additive read/CRUD endpoints.
