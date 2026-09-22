# economizai — API guide for the FE

Practical walk-through of the backend, organized by user flow. For the
machine-readable contract, hit **`/swagger-ui`** on whichever environment
you're against (everything's annotated with descriptions and examples).

- **Production:** `https://economizai-app-prod.onrender.com`
- **Dev:** `https://economiz-ai.onrender.com` (also reachable via the legacy
  proxy `https://economizai.economizai.workers.dev`)
- **Local:** `http://localhost:8080`

Both environments run the same code (prod is deployed from the `production`
branch, promoted from `development`); dev is where features land first and
where test/screenshot data lives.

All `/api/v1/**` routes (except `/auth/*` and `/legal/*`) require a JWT in the
`Authorization: Bearer <token>` header. Access tokens expire after 24h —
exchange the long-lived refresh token (30d) for a new pair via
`POST /auth/refresh` instead of forcing the user back to the login screen. The
header `Accept-Language: pt` gives Portuguese error messages; default is `pt`,
fall back to `en` is supported.

> **Naming convention.** "Receipt" / "nota" / "NFC-e" all refer to the same
> thing. The literal Portuguese terms (chave de acesso, CNPJ, NFC-e, SEFAZ) are
> kept untranslated in payloads — they're legal identifiers.

---

## 0. App-open snapshot (use this on the home screen)

```
GET /api/v1/dashboard
```

Single round-trip returns:
- `currentMonth` — `{ year, month, total, discount, receiptCount, averageTicket }` (`total` is gross; `discount` is the month's receipt-level discount — net = `total − discount`)
- `recentReceipts` — last 5 confirmed receipts (newest first)
- `suggestedShoppingList` — top 5 RAN_OUT/RUNNING_LOW items
- `communityPromosNearby` — top 5 community promos (radius-aware, watched markets bypass)
- `unreadNotificationCount` — bell-badge value
- `generatedAt` — server timestamp

Each section silently degrades to empty/zero when there's nothing to show. Use this instead of fan-out calls on cold start (saves you ~5×30s on Render free tier).

```
GET /api/v1/home/availability
```

Cold-start map so you can render a "disponível em breve" **blur/lock** (with a
progress hint) on volume-gated sections instead of a bare empty screen. Returns
`features[]`, each `{ feature, available, reason, have, need }`:
- `feature` — `CONSUMPTION_PREDICTIONS | SUGGESTED_LIST | PERSONAL_PROMOS | PREFERENCES | COMMUNITY_DEALS | COMMUNITY_PROMOS | BEST_MARKETS | REFERENCE_PRICE`
- `available` — `have >= need` (past cold-start; data may still be sparse per item)
- `reason` — `AVAILABLE | NEEDS_MORE_RECEIPTS` (personal) `| NEEDS_COMMUNITY` (collaborative)
- `have`/`need` — progress. Personal: `have` = your confirmed-receipt count. Collaborative: `have` = distinct households contributing to the index; `need` = the k-anonymity minimum.

Use it to decide what's safe to show on day 1 vs. what to lock. Distinguishes "empty because you're new" (scan more) from "empty because the community is still growing".

```
POST /api/v1/contact   → 202 Accepted (public, no auth)
```

Contact / feedback form (doubt, criticism, suggestion, praise). Body:
`{ "name", "email", "phone"?, "message" }` — `name`, `email` and `message` required
(`email` must be valid, `message` ≤ 5000 chars), `phone` optional (max 30 chars, free-form).
Emails the message to support with the sender's address as Reply-To. **Public**, but
**rate-limited to 5/hour per IP** (429 with `Retry-After` beyond that). Returns `202`
with no body on success.

```
POST /api/v1/beta-signup   → 202 Accepted (public, no auth)
```

Beta-tester lead capture. Body: `{ "name", "email", "phone"? }` — `name` and `email`
required (`email` must be valid), `phone` optional (max 30 chars, free-form); **no
`message`**. Emails the lead to our beta inbox with a distinct subject
(`[economizai beta tester] …`) so it's separable from `/contact`. **Public**,
**rate-limited to 5/hour per IP** (own bucket, separate from `/contact`). Returns `202`
with no body on success.

```
GET /actuator/health   → public, returns `{"status":"UP"}` — for uptime monitors
```

---

## 1. Auth + onboarding

### Register

```
POST /api/v1/auth/register
{
  "name": "Maria Silva",
  "email": "maria@example.com",
  "password": "atLeast8chars",
  "acceptedTermsVersion": "1.0",
  "acceptedPrivacyVersion": "1.0",
  "platform": "WEB",               // optional: WEB | ANDROID | IOS
  "attribution": {                 // optional — marketing attribution, all fields optional strings
    "utmSource": "instagram", "utmMedium": "cpc", "utmCampaign": "black-friday",
    "utmContent": "video-a", "utmTerm": "mercado barato",
    "clickId": "<fbclid>", "referrer": "https://...", "landingPath": "/promo"
  }
}
→ 201 {
  "token": "...", "refreshToken": "...", "user": { ... },
  "signupPromoGranted": true,
  "signupPromoValidUntil": "2027-03-02T12:00:00"
}
```

**Signup promo banner ("até segunda ordem"):** while the promo is on, every brand-new
account is granted PRO automatically. `signupPromoGranted` is `true` only on the exact
`register` / `google` / `apple` call that just created the account **and** the promo was
active for it; `signupPromoValidUntil` is the PRO expiry to show ("grátis até DD/MM").
Both are always `false`/`null` on `/login` and `/refresh` — check them right after
register/social-login to fire a one-time banner, don't poll for them elsewhere. If the
promo is later turned off, new signups simply get `signupPromoGranted: false` again — no
contract change needed on your side.

`platform` is **optional** on all auth endpoints (`/register`, `/login`, `/google`,
`/apple`). Send `WEB`, `ANDROID`, or `IOS`. It records the **registration platform**
(first time only) and the **last-login time per platform**; unknown/absent values are
ignored (never a 400). The tracked values surface on `UserResponse` as
`registrationPlatform`, `lastPlatform`, and `lastWebLoginAt` / `lastAndroidLoginAt` /
`lastIosLoginAt`.

**`attribution` is optional** on all three signup calls (`/register`, `/google`,
`/apple`) — every field is an optional string. Forward what you can capture from the
landing URL on first visit: `utm_source`/`utm_medium`/`utm_campaign`/`utm_content`/
`utm_term` (query params), `fbclid` → `clickId`, `document.referrer` → `referrer`, and
the first-page path → `landingPath`. The backend derives an immutable
`acquisitionChannel` from it (`INSTAGRAM_PAID`/`GOOGLE_PAID`/`PAID_OTHER`/`REFERRAL`/
`ORGANIC`/`DIRECT`/`UNKNOWN`) and stores it on the user; **the `AuthResponse` shape does
not change**. Omitting the object is fine (→ `UNKNOWN`/`DIRECT`). Feeds the admin
acquisition dashboard (§10d).

The terms/privacy versions come from `GET /api/v1/legal/terms` and
`GET /api/v1/legal/privacy-policy`. Show the docs to the user, then send the
version they actually saw. Stale versions are rejected with 400.

A **household is auto-created** at register time. The new user is its only
member, and they get an `inviteCode` valid for 48h. They can:
- Stay in their solo household (single-person tracking).
- Share the code with a partner who joins (couple/family tracking).

### Login

```
POST /api/v1/auth/login
{ "email": "maria@example.com", "password": "...", "platform": "ANDROID" }  // platform optional
→ 200 { "token": "...", "refreshToken": "...", "user": { ... } }
```

- **Wrong email/password** → `401 auth.invalid.credentials` (same error for unknown email or wrong password — no account-enumeration).
- **Password login on a social account** → `409 auth.social_account` (the email belongs to a Google/Apple account with no password). The response carries the provider so you can point the user at the right button:
  ```
  { "status": 409, "message": "Esta conta usa login com Google...", "errors": { "provider": "GOOGLE" } }
  ```
  Read `errors.provider` (`GOOGLE`/`APPLE`) to highlight the matching "Continue with …" button; `message` is already localized.

### Social login (Google / Apple)

The mobile app runs the **native** Google / Apple sign-in SDK, gets the provider token, and sends it here. The backend verifies the token (RS256 against the provider's JWKS; issuer/expiry checks, and audience when client IDs are configured), then **finds-or-creates** the user and returns the **same `AuthResponse`** as password login.

```
POST /api/v1/auth/google   { "idToken": "<google id_token>", "platform": "ANDROID" }                 → 200 { token, refreshToken, user, signupPromoGranted, signupPromoValidUntil }
POST /api/v1/auth/apple    { "identityToken": "<apple identity_token>", "name": "Maria Silva", "platform": "IOS" } → 200 { token, refreshToken, user, signupPromoGranted, signupPromoValidUntil }
```

`signupPromoGranted`/`signupPromoValidUntil` behave exactly as in `/register` above —
`true`/set only when this call just created the account (not on a returning user's login).

- First-time social users get a **solo household**, `emailVerified=true` (the provider already verified it — no verification email is sent), and the current legal versions are accepted on their behalf (show terms in the app before the social button).
- If an existing **local** account has the same email, the provider is **linked** to it.
- `name` on `/apple` is only needed on the **first** Apple sign-in (Apple omits it afterward); forward whatever the Apple SDK gives you.
- Invalid/expired/forged tokens → `401 auth.oauth.invalid`.

### Refresh + logout

```
POST /api/v1/auth/refresh    { "refreshToken": "..." }
                              → 200 { "token": "...", "refreshToken": "...", "user": {...} }

POST /api/v1/auth/logout     { "refreshToken": "..." }   → 204 (idempotent)
```

The refresh token is **single-use** — every `/refresh` call returns a new pair, and the previous refresh token is marked consumed. If a consumed token is replayed, you get `400 auth.token.invalid` (a sign that someone may be replaying tokens). Refresh tokens live for 30 days; access tokens for 24h. Call `/refresh` proactively when an access token is close to expiring, or reactively on the first 401 you get.

`/logout` revokes the presented refresh token. Always returns 204, even when the token is unknown — keeps the endpoint idempotent. The access token is still technically valid until it expires (24h max), so the FE should also drop both tokens from storage.

### Profile

```
GET    /api/v1/users/me                 → current user
PUT    /api/v1/users/me                 { "name": "..." }
PUT    /api/v1/users/me/password        { "currentPassword": "...", "newPassword": "..." }
DELETE /api/v1/users/me                 → LGPD account deletion (cascades all data)
GET    /api/v1/users/me/export          → LGPD data export (ALL personal data: user + accountExtras + household + receipts + notificationRules + watchedMarketCnpjs + subscription + marketAliases + customCategories + categoryOverrides + manualPurchases + shoppingLists + notifications)
PATCH  /api/v1/users/me/contribution    { "contributionOptIn": false }   ← LGPD opt-out from collaborative panel
PATCH  /api/v1/users/me/location        { "latitude": -30.0277, "longitude": -51.2287 }
PATCH  /api/v1/users/me/push-token      { "pushDeviceToken": "<FCM>" }   ← null/empty to clear
PATCH  /api/v1/users/me/phone           { "phoneNumber": "+5551999999999" }  → 204 (E.164; sends a 6-digit OTP via SMS)
POST   /api/v1/users/me/phone/verify    { "code": "123456" }                 → 204 (400 on wrong/expired code)
GET    /api/v1/users/me/notification-preferences
PUT    /api/v1/users/me/notification-preferences
       { "preferences": [ { "type": "PROMO_PERSONAL", "channel": "PUSH" }, ... ] }

GET    /api/v1/users/me/digest-preferences   → { "frequency": "DAILY", "sendHour": 17 }
PUT    /api/v1/users/me/digest-preferences
       { "frequency": "DAILY|WEEKLY|OFF", "sendHour": 0-23 | null }
       ← frequency required; sendHour optional override (null = infer it).
         sendHour out of 0-23 → 400. frequency=OFF disables the digest entirely.

POST   /api/v1/users/me/profile-picture   ← multipart form, field name "file"
                                            JPG/PNG/WEBP, max 5MB
GET    /api/v1/users/me/profile-picture   ← returns the bytes (Content-Type matches the upload)
DELETE /api/v1/users/me/profile-picture
```

**Profile picture**: standard multipart upload. The response is JSON `{ "status": "ok" }` on success.

- **On upload**: server-side downscales JPEG/PNG to a 512px max dimension before storing (saves disk + bandwidth, no FE work).
- **On GET**: returns the raw image bytes. **Never 404s** — when no picture has been uploaded, the server generates a deterministic initials avatar (PNG, 256x256, color hashed from email) so `<img>` tags always render. Inspect the **`X-Profile-Picture-Fallback: true|false`** response header to distinguish a generated avatar from a user-uploaded photo (handy for "edit photo" vs "upload photo" copy).
- **WebP**: stored as-is (no resize). All other formats (JPEG/PNG) are normalized.
- Storage is local-disk in dev (ephemeral on Render free tier — see `DEV_NOTES.md` for the prod plan); the API contract won't change when we swap backends.

### Password reset + email verification

```
POST /api/v1/auth/forgot-password    { "email" }                          → 204  (step 1: emails a 6-digit code)
POST /api/v1/auth/verify-reset-code  { "email", "code" }                  → 204  (step 2: validate, doesn't consume)
POST /api/v1/auth/reset-password     { "email", "code", "newPassword" }   → 204  (step 3: consume + set password)
POST /api/v1/auth/verify-email       { "email", "code" }                  → 204  (6-digit code from the signup email)
POST /api/v1/users/me/email-verification/resend                           → 204
```

**Both flows are code-based** (mobile-friendly — no links anywhere). Password reset: step 1 emails a **6-digit code**; step 2 lets the app validate the code before showing the new-password screen (optional but recommended); step 3 sets the password. Email verification: signup (or resend) emails a 6-digit code; the app confirms it with `verify-email`. Codes are **single-use**, a new request **invalidates the previous** code, and **5 wrong attempts lock the code** (request a new one). Reset codes expire in **60 min**; verification codes in **24 h**. `verify-email` is idempotent for already-verified accounts.

`forgot-password` always returns 204 — even when the email isn't registered, to avoid leaking valid addresses. `verify-reset-code`, `reset-password`, and `verify-email` return **400** on an invalid/stale/used/locked code.

**Dev mode**: when SMTP isn't configured, the email body (incl. the code) is logged with a `[DEV-MODE]` prefix instead of being sent. The endpoints still return 204, so the flow works for FE testing — grab the code from logs.

`UserResponse` now includes `emailVerified` + `emailVerifiedAt`. You can gate features behind `emailVerified === true` if you want.

### Phone verification (for SMS / WhatsApp notifications)

```
PATCH /api/v1/users/me/phone         { "phoneNumber": "+5551999999999" }  → 204
POST  /api/v1/users/me/phone/verify  { "code": "123456" }                 → 204
```

`phoneNumber` must be E.164 (leading `+`, country code, 8–15 digits) — otherwise `400`. Setting the phone stores it as **unverified** and sends a 6-digit OTP (10-minute TTL) over SMS. Submit that code to `…/phone/verify`; a correct, unexpired code marks the phone verified (`204`). Wrong, expired, or missing code → `400`.

A verified phone is required for the **SMS** and **WHATSAPP** notification channels to actually deliver. **Dev mode**: when Twilio isn't configured, the OTP is logged with a `[DEV-MODE] phone OTP for …` prefix in the server logs instead of being texted — the endpoint still returns 204, so grab the code from the logs.

---

## 2. Households

Every user already has a household (auto-created at register). To merge two
users into one shared history:

```
POST /api/v1/households/join          { "inviteCode": "ABC123" }   ← case-insensitive
POST /api/v1/households/leave         → moves caller into a fresh solo household
GET  /api/v1/households/me            → current household + members + invite code + expiration
POST /api/v1/households/me/invite-code/regenerate   → rotates the code, extends TTL another 48h
DELETE /api/v1/households/me/members/{memberId}     → kicks a member; they get a fresh solo household
```

**Invite codes expire after 48h.** When that happens, `/join` returns 400 with
the same message as an unknown code (we don't tell the requester whether the
code was wrong vs. stale — both look the same to a malicious user). The owner
regenerates and shares the new one.

When the last member leaves a household, the household row is deleted
automatically.

### Data-share consents (household split)

When a member leaves and wants to take a **copy of another member's data** with
them, the other member must consent first. The grantor sees pending requests
and approves or denies:

```
GET  /api/v1/households/consents/pending              → List<ConsentResponse> where I am the grantor
POST /api/v1/households/consents/{consentId}/approve  → 200 ConsentResponse (requester gets the copy)
POST /api/v1/households/consents/{consentId}/deny     → 200 ConsentResponse (no data is copied)
```

`ConsentResponse`: `{ id, requesterId, requesterName, grantorId, grantorName,
scope, status, expiresAt }`. Approve/deny return 400 when the request isn't
mine, was already resolved, or expired.

---

## 3. Receipts (the core flow)

### Submit a scanned NFC-e

```
POST /api/v1/receipts
{
  "qrPayload": "<whatever the camera scanned>"
}
→ 201 ReceiptResponse with status="PENDING_CONFIRMATION"
```

**`qrPayload` accepts five shapes** — they're all parsed into the 44-digit chave
de acesso server-side. Pass exactly what the QR scanner returned, or the chave
typed/pasted manually:

1. SVRS landing URL: `https://www.sefaz.rs.gov.br/NFCE/NFCE-COM.aspx?p=<chave>|3|1`
2. Direct portal URL: `https://dfe-portal.svrs.rs.gov.br/Dfe/QrCodeNFce?p=<chave>|3|1`
3. Bare pipe payload: `<44-digit-chave>|3|1`
4. Bare 44-digit chave (no spaces): `50260677863223012709650190004048511190344086`
5. Bare chave with spaces (printed format): `5026 0677 8632 2301 2709 6501 9000 4048 5111 9034 4086`

Shapes 4 and 5 support the **"enter manually" fallback** when the QR code can't
be scanned. The 44-digit code is always printed on the receipt under "CHAVE DE
ACESSO" in groups of 4. Spaces are stripped server-side — send whatever the user
typed or pasted.

**Suggested UX for manual entry:** a single text field that accepts paste, strips
spaces/hyphens on submit, and validates length = 44 digits before sending. A
grouped input (11 × 4-digit fields with auto-advance) reduces transcription
errors but is optional — the backend handles both.

### Submit a receipt the app fetched on-device (blocked states, e.g. PE)

```
POST /api/v1/receipts/prefetched
{
  "qrPayload": "<whatever the camera scanned>",
  "rawContent": "<the raw HTML/XML the app fetched from the QR's URL on the device>"
}
→ 201 ReceiptResponse with status="PROCESSING"
```

Some state portals (Pernambuco today) serve the nota to residential/mobile IPs
but **block our datacenter server** — so the backend can't scrape it. The **native
app** fetches the QR's own SEFAZ URL on the device (the phone's IP is accepted,
following the `http`→`https:444` redirect), and posts the raw page here. The
backend parses it with the exact same pipeline as `POST /receipts` — no scraping
from our IP, no paid fallback. Same validation, caps, and `ReceiptResponse`.

- `rawContent` **must contain the chave** of `qrPayload` (integrity guard) — else
  the receipt goes `FAILED_PARSE`.
- **Native only.** On web the browser blocks the cross-origin fetch (CORS); use
  the normal `POST /receipts`. The bundled FE (`receiptService.submitReceipt`)
  already routes automatically: native + blocked UF (PE) + URL payload → this
  endpoint; everything else → `POST /receipts`.

### Bulk import receipts from access keys (chaves) — onboarding (RS only)

Fill a new user's history from the **chaves** they export from the Nota Fiscal
Gaúcha portal, without scanning each nota. Each eligible **RS** chave (NFC-e 65 /
NF-e 55) is reconsulted on a public SEFAZ portal and ingested through the normal
pipeline.

```
POST /api/v1/receipts/import
{ "chaves": ["43260593015006005182651200000076311055456577", ...] }   (max 500)
→ 202 ReceiptImportResponse

POST /api/v1/receipts/import/nfg-csv   (multipart/form-data, field "file")
  file = the raw CSV exported from Nota Fiscal Gaúcha (chaves extracted server-side)
→ 202 ReceiptImportResponse
```

`ReceiptImportResponse`:
```
{
  "received": 12,
  "queued": 9,
  "queuedReceiptIds": ["<uuid>", ...],   // poll each via GET /receipts/{id}
  "rejected": 3,
  "rejectedChaves": [
    { "chave": "4326...", "reason": "receipt.import.duplicate", "reasonMessage": "..." }
  ]
}
```

- Each queued id is a `PROCESSING` receipt — **poll `GET /receipts/{id}`** until it
  reaches `PENDING_CONFIRMATION` (then confirm as usual) or `FAILED_PARSE`. Same
  monthly cap as `POST /receipts` (over the cap → the rest come back as
  `receipt.import.cap_reached`).
- Rejection `reason` keys: `receipt.import.invalid_chave`, `receipt.import.unsupported`
  (not RS / unsupported model), `receipt.import.duplicate`, `receipt.import.merchant_unsupported`,
  `receipt.import.cap_reached`. `reasonMessage` is already localized.
- **RS only** today; other UFs → `receipt.import.unsupported`. E-commerce notas
  (NF-e 55, e.g. Amazon) land in personal history but stay **out of the collaborative
  index** (non-grocery segment).

### Submit from a PHOTO of the QR code

For users who can't scan live (web version, or a saved picture in the gallery):

```
POST /api/v1/receipts/photo        (multipart/form-data, field "file")
→ 201 ReceiptResponse with status="PROCESSING"   (same flow as a live scan)
```

JPG/PNG only, max 5 MB. The QR is decoded server-side (ZXing) and the payload
enters the exact same submit pipeline — same polling, same error paths, same
monthly cap and rate limit as `POST /receipts`. Extra error:

| Response | When |
|---|---|
| 400 `receipt.photo.qr.unreadable` | No decodable QR in the photo (blur/glare/too far). Ask for a closer, well-lit retake. |
| 400 `receipt.photo.invalid.type` / `receipt.photo.empty` / `receipt.photo.too.large` | Upload validation. |

On mobile keep scanning client-side (faster, no upload) — this endpoint is the
no-camera fallback.

### Extract the chave from a PHOTO (OCR fallback)

When the QR itself is damaged but the printed 44-digit "CHAVE DE ACESSO" is
visible:

```
POST /api/v1/receipts/chave/photo  (multipart/form-data, field "file")
→ 200 { "chaveAcesso": "5026...4086", "uf": "MS" }
```

This does **NOT** submit — OCR can misread, so show the extracted chave to the
user (ideally in the grouped 11×4 layout) and let them confirm, then send it as
`qrPayload` through the normal `POST /receipts`. The backend only returns a
chave whose **mod-11 check digit and UF prefix are valid**, so garbage reads
come back as an error, not a wrong chave:

| Response | When |
|---|---|
| 400 `receipt.photo.chave.unreadable` | No valid 44-digit chave found in the photo. Offer manual typing. |
| 503 `receipt.ocr.unavailable` | OCR engine not available on the host. Offer manual typing. |

Note the existing per-state rule still applies at submit time: a bare chave is
rejected (`receipt.manual-chave.unsupported`) for any state that can't be
consulted without the QR's signature params — always RS, and every
signature-gated or experimental state while the paid by-chave fallback is off.

### State coverage — every UF is accepted

Since 2026-07-16 **all 27 UFs are accepted at submit**. Verified states
(RS, PR, SP, SC, MS, GO — plus CE when Infosimples is on) use their dedicated
adapters; every other state goes through an **experimental fallback chain**
(GET the QR's own portal URL + shared DANFE parser, then Infosimples when
enabled). If every layer fails, the receipt lands in `status=FAILED_PARSE`
with `parseErrorReason=receipt.state.experimental_failed:<UF>` and a localized
"ainda não conseguimos processar notas desse estado, estamos trabalhando
nisso" in `parseErrorMessage` — render that; the team is auto-notified with
the evidence needed to add support.

### Merchant support — recurring food retail only

Since 2026-07-16 the product gates by establishment type (from the CNPJ's CNAE):

- **Supported**: supermercados/minimercados, farmácias, and food retail —
  padarias, açougues, bebidas, hortifrútis, lojas de conveniência (a posto de
  gasolina whose CNPJ lists the conveniência counts).
- **Blocked**: restaurantes, bares, lanchonetes (CNAE 56xx). Known CNPJ →
  submit returns **400 `receipt.merchant.unsupported`**; first-ever scan →
  the receipt fails during processing with
  `parseErrorReason=receipt.merchant.unsupported:` and stores **no items and
  no rawHtml** (only the market name/CNPJ so the message makes sense).
- **Grey zone** (any other CNAE, e.g. an informal padaria): processed normally
  for the user, but its prices stay out of the collaborative index until an
  admin reviews the merchant (admin is notified automatically).

### Error paths

| Response | When |
|---|---|
| 400 `receipt.qr.invalid` | Couldn't extract a 44-digit chave from the input |
| 400 `receipt.state.unsupported` | Only if the experimental chain is disabled server-side (kill-switch) |
| 400 `receipt.merchant.unsupported` | Known restaurant/bar CNPJ (or admin-blocked). Also arrives via polling for first-time merchants. |
| — `receipt.contingency.pending` | Via polling (`FAILED_PARSE`): the note was issued in contingency (printed "EMITIDA EM CONTINGÊNCIA") and SEFAZ doesn't have it yet — user should rescan later. |
| — `receipt.sefaz.rejected_qr:<code>` | Via polling (`FAILED_PARSE`): SEFAZ portal refused the QR with a numbered rejection (e.g. 227 DigestValue mismatch). |
| — `receipt.state.experimental_failed` | Via polling (`FAILED_PARSE`): experimental state, every fallback layer failed. Team auto-notified. |
| 503 `receipt.captcha.unavailable` | State requires CAPTCHA but solver isn't enabled (shouldn't happen in prod) |
| 502 `receipt.captcha.failed` | CAPTCHA solver ran but failed after retries (e.g. balance exhausted) |
| 502 `receipt.sefaz.fetch.failed` | SEFAZ portal didn't respond / 5xx'd — **only after the server already retried** (up to 5 attempts: immediate, then 5s/5s/5s). Call can take up to ~15s+ when the portal is down. |
| 400 `receipt.parse.failed` | We fetched HTML but the parser couldn't extract items. **The receipt is still saved with `status=FAILED_PARSE` + `rawHtml`** so ops can patch the parser without you re-scanning. |
| 409 `receipt.already.ingested` | This chave is already in **your household's** history. Delete it via `DELETE /receipts/{id}` to free the slot. |

Since the async submit flow, most of these surface via polling rather than as
HTTP errors: the receipt lands in `status=FAILED_PARSE` and the new
**`parseErrorReason`** field on `ReceiptResponse` carries the machine key
(e.g. `receipt.parse.failed:no-items-found`). It's `null` for non-failed
receipts and is intended for debugging/support — don't render it raw to users.

### Review + confirm

After submit, the user sees `status=PENDING_CONFIRMATION` with parsed items.
They can:

```
GET    /api/v1/receipts/{id}                         → full receipt with items
GET    /api/v1/receipts/export?from=&to=&format=&delivery= → CSV (flat) | XLSX (charted multi-sheet report) | PDF (styled report) of CONFIRMED history (one row per
                                                        non-excluded item; includes chave de acesso;
                                                        `;` separator + comma decimals + UTF-8 BOM for
                                                        pt-BR Excel; localized headers; PRO-gated —
                                                        dormant while enforcement is off)
PATCH  /api/v1/receipts/{id}/items/{itemId}          → fix typos / qty / toggle excluded / set friendlyDescription
                                                        (nota fields — PENDING_CONFIRMATION only)
PATCH  /api/v1/receipts/{id}/items/{itemId}/personal  → household "personal layer" — ANY status (incl. CONFIRMED):
                                                        { excludedFromPersonal, friendlyDescription, paidTotalPrice }
PUT    /api/v1/receipts/{id}/items/{itemId}/category  → correct the category { "category": "MEAT_DAIRY" }
POST   /api/v1/receipts/{id}/items                   → add a missing item (PENDING_CONFIRMATION only)
POST   /api/v1/receipts/items-photo                  → multipart photo of the printed receipt; vision-LLM
                                                        extracts items into a PENDING_CONFIRMATION receipt
                                                        (origin=PHOTO — personal history only, never feeds
                                                        the price index; counts toward the FREE monthly cap;
                                                        per-user daily photo cap; 503 when the LLM layer is off)
POST   /api/v1/receipts/{id}/confirm                 → commit. Optional body { excludedItemIds: [uuid, ...] }
                                                        Returns { receipt, personalPromos: [...] }
POST   /api/v1/receipts/{id}/reject                  → discard. Receipt stays as REJECTED in history.
DELETE /api/v1/receipts/{id}                         → hard delete. Frees the chave so it can be re-imported.
                                                        Removes the receipt + its items + audit-trail rows
                                                        that link the household to anonymized observations.
                                                        Anonymized PriceObservation rows themselves stay in
                                                        the community price index — by design (LGPD: delete
                                                        personal data, preserve aggregates). 404 if the
                                                        receipt doesn't belong to the caller's household.
```

**Per-item display name (`friendlyDescription`)** — NFC-e descriptions are noisy ("ARROZ TIO J TP1 5KG"). The user can rename an item for display via `PATCH /receipts/{id}/items/{itemId}` with `{ "friendlyDescription": "Arroz Tio João 5kg" }`. The original `rawDescription` stays untouched (it's the legal audit text from SEFAZ — immutable).

**Per-item manual discount (`paidTotalPrice`)** — the NFC-e only carries a single receipt-level discount (`discountTotal`), never per item. When a line was bought on promotion the user can record what they actually paid via `PATCH /receipts/{id}/items/{itemId}` with `{ "paidTotalPrice": 49.90 }` (optionally `paidUnitPrice`; if omitted it's derived from `paidTotalPrice ÷ quantity`). The original `totalPrice`/`unitPrice` stay as-printed (shelf price, still the price-index baseline); the paid price is stored separately. `paidTotalPrice` must not exceed `totalPrice` (`400` `receipt.item.paid.price.exceeds.original`); send `null` to clear. Only while `PENDING_CONFIRMATION`. Every item response (`GET /receipts/{id}`, `GET /items`) carries `paidUnitPrice`, `paidTotalPrice`, and the derived `promotional` flag (`true` when `paidTotalPrice` is present and below `totalPrice`) — render the discount badge off `promotional` and strike through `totalPrice`.

**Household "personal layer" — editable after confirmation** — once a receipt is `CONFIRMED` the nota is frozen (`PATCH .../items/{itemId}` returns `400` `receipt.not.editable`), but the household can still adjust how it accounts a line via **`PATCH /receipts/{id}/items/{itemId}/personal`** (works at ANY status). All fields optional:
- **`excludedFromPersonal: true`** — "not mine" (shared purchase / someone else's item): drops the line from the household's personal spend / consumption / savings / reports and from `householdTotalAmount`, **but the price still feeds the collaborative index** (a real price is real). This is DISTINCT from `excluded` (the full-edit PATCH flag), which removes the line from *everything* including the index — use that for junk/non-product lines. New boolean `excludedFromPersonal` on every item response.
- **`friendlyDescription`** and **`paidTotalPrice`/`paidUnitPrice`** — same semantics as the full PATCH, but allowed **after confirmation** here. Paid price only shifts the household's personal total; the printed `totalPrice`/`unitPrice` (index baseline) stay untouched. `paidTotalPrice` must not exceed `totalPrice` (`400` `receipt.item.paid.price.exceeds.original`).

Returns the updated receipt. The immutable nota fields (quantity, shelf price, EAN, adding items) remain `PENDING_CONFIRMATION`-only via the full PATCH.

**Per-item category correction** — `PUT /receipts/{id}/items/{itemId}/category` with `{ "category": "MEAT_DAIRY" }`. This is **household-scoped "evidence, not truth"**: it changes the category *this household* sees for that product everywhere (`GET /receipts/{id}`, `GET /items`) but does **not** mutate the global product, so other households are unaffected. `400` if the item isn't linked to a canonical product yet. Returns the updated receipt with the override applied. (Aggregates/insights honor this override in the **HOUSEHOLD** category lens, the default — pass `categoryView=GLOBAL` on `/items`, `/insights/query`, and `/insights/categories/top` to ignore overrides and use the global category.)

The response always includes both:
- `rawDescription` — original NFC-e text, never changes
- `friendlyDescription` — user override, null when not set
- `displayDescription` — derived: `friendlyDescription` if set, else `rawDescription`. Use this for rendering.
- `productId` — the linked canonical product (`uuid`, or `null` when the item isn't matched yet). Use it to drive category migration straight from the review screen (`POST /categories/migrate` with the checked items' `productId`s — see §4c).

**Household memory** — when the user names an item that's linked to a Product, the name is remembered household-wide. Future receipts that contain the same Product (matched by EAN or alias) will inherit `friendlyDescription` automatically — the user only types it once. Different households can have different names for the same product.

**Per-item exclusion** — when the household shopped together with someone outside (a friend, a roommate's purchase) and only some items are theirs, the user can mark items as excluded. Excluded items:

- Stay on the receipt for audit (the original NF is a legal document, we don't rewrite it)
- Don't count toward `householdTotalAmount`
- Don't contribute to the collaborative price index
- Don't feed consumption-cadence predictions
- Don't appear in spend totals or category insights

Two ways to mark an item excluded:

1. **At confirm time**: `POST /receipts/{id}/confirm` with `{ "excludedItemIds": ["uuid-1", "uuid-2"] }` in the body. Items in the list get marked excluded *before* downstream processing.
2. **Per-item PATCH** (works on PENDING_CONFIRMATION receipts): `PATCH /receipts/{id}/items/{itemId}` with `{ "excluded": true, ... }`.

`ReceiptResponse` now exposes:
- `totalAmount` — the amount actually paid ("Valor a pagar"). Never changes. Use this for "what we spent".
- `householdTotalAmount` — sum of non-excluded **item** prices. Items are stored gross-as-printed (we no longer distribute discounts across them), so this can be **higher than `totalAmount`** when the receipt had a discount.
- `discountTotal` — the receipt-level "Descontos R$" as printed, or `null` when none. Relationship: `sum(item totals) ≈ totalAmount + discountTotal`. Render it as a separate "desconto" line; don't expect summed item prices to equal what was paid. (`ReceiptSummaryResponse` carries it too.)

**Approximate-tax fields (new)** — `ReceiptResponse` now also carries the IBPT-source tax disclosure required by Lei 12.741/2012:
- `approxTaxFederal` — federal portion (BigDecimal, nullable)
- `approxTaxEstadual` — estadual portion (BigDecimal, nullable)
- `approxTaxTotal` — sum, derived; `null` when both source fields are null

`ReceiptSummaryResponse` exposes `approxTaxTotal` only (federal/estadual breakdown lives on the detail endpoint to keep list payloads lean).

These are **estimates from the IBPT national table** (taxes embedded in the retail prices: ICMS, IPI, PIS, COFINS, IOF) — NOT taxes the consumer paid separately, NOT what the merchant remitted. Always label as "imposto aproximado" / "estimativa IBPT" in the UX. The line is mandatory by law but not always present (some Simples Nacional micro-merchants skip it or declare R$ 0,00) — fields are `null` when absent. Aggregations like "% pago em impostos esse mês" must filter `WHERE approxTaxTotal IS NOT NULL`, otherwise missing-data receipts dilute the average.

**Item-level promo flag (new)** — each `ReceiptResponse.items[*]` now carries `nfcePromoFlag: boolean`. True when the SEFAZ HTML signaled the line was on promo / discount (a discount cell was present, or the description carries stems like "OFERTA", "PROMO", "DESCONTO", "COMBO", "LEVE 3"). Use it for visual emphasis ("oferta!" badge) on receipt detail cards. Backend behavior: flagged items are excluded from baseline calcs in community-promo detection so we don't compare promos to historic promos.

**Item-level category (new)** — each `ReceiptResponse.items[*]` now carries `category: string | null`. Resolved from the linked `Product` (when the item has been canonicalized). Values: `GROCERIES`, `BEVERAGES`, `PRODUCE`, `MEAT_DAIRY`, `BAKERY`, `CLEANING`, `PERSONAL_CARE`, `OTHER`. `null` when the item hasn't been linked to a Product yet, or the Product has no category set. Use it to show a category chip per line on the ReviewScreen / receipt detail without a follow-up `GET /products/{id}` call.

Confirm is what triggers downstream side effects:
- Item canonicalization (raw text → canonical Product)
- Anonymized contribution to the collaborative price index (skipped if `contributionOptIn=false`)
- Personal promo detection (returned in the response)
- Geocoding of new markets (async, doesn't block)

`personalPromos` in the confirm response is the list of items the user paid
notably less for than usual — surface as "Você economizou" cards.

### List + filter

```
GET /api/v1/receipts
    ?from=2026-04-01T00:00:00
    &to=2026-04-30T23:59:59
    &marketCnpj=83261420003255
    &category=GROCERIES               ← multi-value: repeat for OR (?category=GROCERIES&category=CLEANING)
    &status=CONFIRMED                  ← optional: PENDING_CONFIRMATION|CONFIRMED|REJECTED|FAILED_PARSE (invalid → 400; absent → all)
    &q=leite condensado
    &page=0&size=20
→ Page<ReceiptSummaryResponse>  (each row has marketName, issuedAt, totalAmount, householdTotalAmount, discountTotal, approxTaxTotal, itemCount, status)
```

Default sort is `issuedAt DESC`. All filters optional. `category` accepts one or many (same as `/items` and `/insights/query`).

**Content search (`q`)** — case-insensitive substring match against `rawDescription`, `friendlyDescription`, the linked product's normalized name, AND the receipt's market name. So `q=leite` finds every receipt that includes a milk item OR was issued by a market with "leite" in the name. Combine with `from`/`to`/`category` freely.

---

## 4. Insights / dashboards

> **Caching (perf):** `GET /dashboard` and `GET /insights/spend` are cached server-side (2 min / 5 min, per household) and **auto-invalidated on any receipt mutation** (confirm/reject/delete/reparse/add/edit item) — so they're fast but never stale after your own action. Both return an `ETag`; send it as `If-None-Match` to get `304 Not Modified` (no body) when nothing changed. The dashboard's `unreadNotificationCount` is always live (not cached).

```
GET /api/v1/insights/spend?from=&to=
```
Returns:
```json
{
  "from": "2026-04-01T00:00:00",
  "to": "2026-04-30T23:59:59",
  "total": 1234.50,
  "totalDiscount": 18.40,
  "byMonth": [{ "year": 2026, "month": 4, "total": 1234.50, "discount": 18.40, "receiptCount": 5 }],
  "byWeek":  [{ "year": 2026, "week": 17, "total": 312.00, "discount": 4.00, "receiptCount": 1 }, ...],
  "byMarket": [{ "cnpj": "...", "marketName": "...", "total": 850.00, "discount": 12.40, "receiptCount": 3 }, ...],
  "byCategory": [{ "category": "GROCERIES", "total": 600.00, "itemCount": 18 }, ...]
}
```
- **Spend totals are gross** (sum of item prices). The receipt-level discount is reported **alongside**, not subtracted: `totalDiscount` for the period, and `discount` per `byMonth`/`byWeek`/`byMarket` bucket. **Net spend = `total − discount`** — compute on the FE. `byCategory` has **no** `discount` (a receipt-level discount can't be split across categories); use `totalDiscount` there.
- **Ticket médio** is `total / receipts.count` — compute on the FE.
- **Empty state** = `total: 0`, `totalDiscount: 0` + all arrays empty. No errors.

```
GET /api/v1/insights/markets/top?limit=5
GET /api/v1/insights/markets/top-discounts?from=&to=&limit=5
GET /api/v1/insights/categories/top?limit=5&categoryView=HOUSEHOLD
GET /api/v1/insights/products/{productId}/price-history?from=&to=
```

`markets/top-discounts` ranks the household's markets by **discount received** (biggest first), excluding markets that gave none. Each row: `{ cnpj, marketName, marketFriendlyName, grossTotal, discount, discountRate, receiptCount }` — `discount` in R$, `discountRate` = `discount / grossTotal` (0–1 fraction, format as %). Personal to your household, so it works even without community volume.

`categories/top` takes the same **category lens** as `/items` via `categoryView` (default `HOUSEHOLD`). In the HOUSEHOLD lens each product is counted **once** under its effective category, so a product moved by an override is removed from its old enum bucket and added to the override target (custom name → its own bucket). Each `CategoryBucket` carries `category` (the global enum, **null** for custom-category buckets), `label` (always present — the display name), `total`, and `itemCount`. Pass `categoryView=GLOBAL` to group purely by `Product.category` (the pre-lens behavior).

`price-history`'s top level carries `productName` **and `friendlyDescription`**
(the household's rename, null when never renamed — prefer it for the chart
title). It returns chronological points, **each tagged with marketCnpj +
marketName** so the FE can color the points to differentiate stores of the same
chain (Zaffari Hipica vs Zaffari Centro both render as "ZAFFARI" but have
different CNPJs).

### Flexible spend slicer (use this for any cross-filtered chart)

When the four endpoints above don't compose the slice you need (e.g., "spend on GROCERIES at Zaffari last month, by week"), reach for the unified query endpoint instead:

```
GET /api/v1/insights/query
    ?from=2026-04-01T00:00:00
    &to=2026-04-30T23:59:59
    &marketCnpj=93015006005182        ← repeat for OR: ?marketCnpj=A&marketCnpj=B
    &marketCnpjRoot=93015006          ← chain-level (8-digit CNPJ root), list-typed
    &category=GROCERIES               ← repeat for OR: ?category=GROCERIES&category=BEVERAGES
    &productId=<uuid>                 ← list-typed
    &ean=7891234567890                ← list-typed
    &minReceiptTotal=100.00           ← receipt-total range
    &maxReceiptTotal=500.00
    &groupBy=WEEK                     ← see list below
    &limit=100                        ← bucket cap (default 100, max 500)
    &categoryView=HOUSEHOLD           ← category lens (default HOUSEHOLD; only affects groupBy=CATEGORY)
```

**Category lens (`categoryView`)** — only affects `groupBy=CATEGORY`. In `HOUSEHOLD` (default), spend is re-bucketed by each household's **effective** category (override custom name / corrected enum / global enum), counting each product **once** — no double-counting. The bucket `key`/`label` is the effective category name; `receiptCount` is a correct distinct-receipt count per effective bucket. `GLOBAL` groups purely by `Product.category` (pre-lens behavior). Other `groupBy` dimensions ignore this param.

**`groupBy`** is a single dimension (one at a time):
- **Temporal:** `DAY`, `WEEK`, `MONTH`, `YEAR` — sorted ascending
- **Categorical:** `MARKET` (full CNPJ), `CHAIN` (CNPJ root), `CATEGORY`, `PRODUCT` — sorted descending by total
- **`NONE`** (default) — return only the summary, no buckets

**Response shape:**
```json
{
  "filters": {
    "from": "2026-04-01T00:00:00",
    "to": "2026-04-30T23:59:59",
    "marketCnpjs": ["93015006005182"],
    "marketCnpjRoots": null,
    "categories": ["GROCERIES"],
    "productIds": null,
    "eans": null,
    "minReceiptTotal": null,
    "maxReceiptTotal": null
  },
  "summary": {
    "total": 234.50,
    "totalDiscount": 6.40,
    "receiptCount": 3,
    "itemCount": 18,
    "averageTicket": 78.17
  },
  "groupBy": "WEEK",
  "buckets": [
    { "key": "2026-W14", "label": "2026-W14", "total": 80.00, "discount": 2.40, "receiptCount": 1, "itemCount": 6, "averageTicket": 80.00 },
    { "key": "2026-W15", "label": "2026-W15", "total": 154.50, "discount": 4.00, "receiptCount": 2, "itemCount": 12, "averageTicket": 77.25 }
  ]
}
```

**Conventions:**
- All filters are **optional** — empty filter = no constraint on that dimension.
- Repeated query params = OR within the same dimension (`?category=A&category=B`). Different dimensions are AND'd together.
- `key` is the canonical machine value (CNPJ, UUID, "GROCERIES", "2026-04"). `label` is the human-friendly version (market name, product name) — use it for display.
- **Spend is gross**; the receipt-level discount is reported alongside. `summary.totalDiscount` is the discount for the whole slice. Each `bucket.discount` is the per-bucket discount for **receipt-level** groupings (`DAY`/`WEEK`/`MONTH`/`YEAR`/`MARKET`/`CHAIN`); it is **`null`** for `CATEGORY`/`PRODUCT` (a receipt's discount can't be attributed to one category/product bucket) — use `summary.totalDiscount` there. Net = `total − discount`.
- Empty result is `summary.total = 0` + `buckets = []`, NOT a 404.

**Examples for common dashboards:**
| Question | Query |
|----------|-------|
| Weekly spend in April | `?from=2026-04-01T00:00:00&to=2026-04-30T23:59:59&groupBy=WEEK` |
| Top 5 markets this year | `?from=2026-01-01T00:00:00&groupBy=MARKET&limit=5` |
| Spend per chain (collapse store-level CNPJs) | `?groupBy=CHAIN` |
| Category breakdown at one specific market | `?marketCnpj=93015006005182&groupBy=CATEGORY` |
| Top 20 products in groceries OR beverages | `?category=GROCERIES&category=BEVERAGES&groupBy=PRODUCT&limit=20` |
| Big shopping trips only (R$200+) by month | `?minReceiptTotal=200.00&groupBy=MONTH` |
| Total spend on milk EAN | `?ean=7891234567890` (no groupBy = just summary) |

The legacy `/insights/spend`, `/markets/top`, `/categories/top`, `/products/{id}/price-history` endpoints stay around for the existing dashboards. Use `/insights/query` for anything custom.

---

## 4b. Items (purchased line items)

When you want the **actual items**, not aggregates — e.g. "tap a category → list every item I bought in it". This is the item-level companion to `/receipts` (receipt rows) and `/insights/query` (aggregates): **same filter vocabulary**, but it returns raw line items, paginated, newest first.

```
GET /api/v1/items
    ?from=2026-04-01T00:00:00
    &to=2026-04-30T23:59:59
    &marketCnpj=93015006005182        ← list-typed (repeat for OR)
    &marketCnpjRoot=93015006          ← chain-level, list-typed
    &category=MEAT_DAIRY              ← list-typed (repeat for OR)
    &productId=<uuid>                 ← list-typed
    &ean=7891234567890                ← list-typed
    &minReceiptTotal=100.00           ← receipt-total range
    &maxReceiptTotal=500.00
    &categoryView=HOUSEHOLD           ← category lens (default HOUSEHOLD; or GLOBAL)
    &page=0&size=20                   ← standard Spring pagination
```

**Category lens (`categoryView`)** — each product belongs to **exactly one** category for the household (no double-counting). The lens controls how `&category=<ENUM>` filtering resolves:
- `HOUSEHOLD` (**default**): filter by the **effective** category — the household's override (a corrected enum) when set, else the global `Product.category`. `?category=GROCERIES` returns products whose *effective* category is GROCERIES and **excludes** any product the household moved to a custom category or to a different enum.
- `GLOBAL`: ignore overrides; filter purely by the global `Product.category` (the pre-lens behavior).

Each row carries **both** categories: `category` is the effective label (override custom name / corrected enum / global enum), `globalCategory` is always the global enum (or null when the item has no canonical product).

Returns a Spring `Page<PurchasedItemResponse>`:
```json
{
  "content": [
    {
      "itemId": "uuid",
      "productId": "uuid|null",
      "category": "MEAT_DAIRY|Laticínios|null",
      "globalCategory": "MEAT_DAIRY|null",
      "displayDescription": "Leite Italac 1L",
      "rawDescription": "LEITE ITALAC 1L",
      "friendlyDescription": "Leite Italac 1L|null",
      "ean": "7891234567890|null",
      "quantity": 1.000,
      "unit": "UN",
      "unitPrice": 5.49,
      "totalPrice": 5.49,
      "nfcePromoFlag": false,
      "receiptId": "uuid",
      "marketName": "Zaffari",
      "marketCnpj": "93015006005182",
      "purchasedAt": "2026-05-15T10:00:00"
    }
  ],
  "totalElements": 42, "totalPages": 3, "number": 0, "size": 20
}
```

**Conventions** (same as `/insights/query`):
- All filters optional; multi-value filters OR within a dimension, AND across dimensions.
- Scope is **CONFIRMED receipts, excluded items dropped** — i.e. real purchases (matches the analytics scope).
- Default sort: `purchasedAt` desc (receipt issue date), then item id. Empty result is an empty page, not a 404.
- The category enum values: `GROCERIES, BEVERAGES, PRODUCE, MEAT_DAIRY, BAKERY, CLEANING, PERSONAL_CARE, HEALTH, OTHER`. The FE maps these to PT labels (e.g. `MEAT_DAIRY` → "Carnes e Laticínios", `HEALTH` → "Saúde").
- Natural drill-down target: take a bucket `key` from `/insights/query` (a productId, a category, a CNPJ) and pass it here to list the underlying items.
- Extra filter `&customCategoryId=<uuid>` lists the items the household has migrated into one of its **custom categories** (see §4c). Combine with other filters as usual; unknown id → empty page.
- `categoryView` (default `HOUSEHOLD`) decides whether `&category=<ENUM>` filters by the household's effective category or the global one — see the lens note above.

---

## 4c. Categories + product migration

Households can define their own categories ("Frutas", "Bebê", …) and move products into them. Everything here is **household-scoped** — the global product/catalog is never mutated, mirroring the per-item category correction (§3).

```
GET    /api/v1/categories                → categories this household can use
POST   /api/v1/categories                { "name": "Frutas" }   → 201, idempotent on name
DELETE /api/v1/categories/{id}           → 204, removes a custom category (its overrides revert)
POST   /api/v1/categories/migrate        { "productIds":[...], "targetCategory":..., "targetCustomCategoryId":... }
```

`GET /categories` returns the 9 global enums **and** the household's custom ones:
```json
[
  { "id": null, "name": "GROCERIES", "custom": false },
  { "id": null, "name": "BEVERAGES", "custom": false },
  { "id": "uuid", "name": "Frutas", "custom": true }
]
```

`POST /categories/migrate` moves the selected products into the target. Provide **exactly one** of `targetCategory` (a global enum) or `targetCustomCategoryId` (a custom-category uuid) — `400 customcategory.migration.invalid` otherwise.
```json
{ "productIds": ["uuid","uuid"], "targetCategory": null, "targetCustomCategoryId": "uuid" }
→ 200 { "migrated": 2, "skipped": 0 }
```

**Migration screen flow:** list a category's items with `GET /items?category=GROCERIES`, check the ones to move (with a select-all toggle), then `POST /categories/migrate` with those `productIds` and the target. View a custom category's contents with `GET /items?customCategoryId=<uuid>`. After migration each moved item's `category` reads back as the custom-category **name** (a display string, no longer always an enum); the item's `globalCategory` keeps the underlying enum. In the default **HOUSEHOLD** category lens, `/items?category=<enum>` and the `CATEGORY` insights paths count the moved product under its new (custom/corrected) category and drop it from the old enum — no double-counting. Pass `categoryView=GLOBAL` to fall back to the global category everywhere.

---

## 5. Products

```
GET   /api/v1/products?query=arroz&page=0&size=20    → GLOBAL catalog search by name or exact EAN (autocomplete)
GET   /api/v1/products/mine                          → products MY household has actually bought
GET   /api/v1/products/{id}/markets[?includeNearby=&radiusKm=] → where this product is, and at what price
GET   /api/v1/products/{id}                          → single product
POST  /api/v1/products                               → create canonical product (rare; usually auto-created on confirm)
PATCH /api/v1/products/{id}                          → set category/brand/etc
POST  /api/v1/products/{id}/aliases                  { "rawDescription": "<raw NFC-e text>" }
GET   /api/v1/products/unmatched                     → review queue: receipt items the system couldn't match
POST  /api/v1/products/{id}/view                     → 204; records "user opened this product's detail screen"
GET   /api/v1/products/recently-viewed?limit=10      → List<ProductResponse>, newest first
```

**Recently viewed:** fire `POST /products/{id}/view` whenever the detail screen
opens; `GET /products/recently-viewed` powers a "vistos recentemente" shelf
(per user, not household). `GET /products?lastProducts=N` returns the same list
wrapped in the paged search shape, if that's more convenient.

**Friendly name in search results (2026-08-27):** `ProductResponse` carries
`friendlyDescription` — the household's friendly name for the product, null
when it has none. It's populated in `GET /products` **search results** (so
the "Adicionar item" screen shows the name the user knows). Since 2026-08-28
it's also populated in `GET /products/{id}`, `GET /products/by-ean/{ean}`
(the tracked-product branch) and `GET /products/recently-viewed`. Prefer
`friendlyDescription ?? normalizedName` for display.

**Resolution order (2026-09-02):** explicit alias (`household_product_aliases`,
set via `POST /products/{id}/aliases` or auto-remembered whenever the user
renames a linked item pre-confirmation) → the household's friendly name from
its most recent **confirmed** receipt for the same product. The second tier
matters because some items get a `friendlyDescription` without ever creating
an explicit alias (e.g. an item added via `POST /receipts/{id}/items`, which
sets it directly on the item) — before this, those names showed up correctly
on the shopping list and receipt screens but not in product search/detail,
since those endpoints only checked the alias table. Both tiers are per
household, so different households can still see different names for the
same catalog product.

`GET /products` stays the **global** catalog (for autocomplete when creating alerts/rules etc.). For "the products I buy", use the two household-scoped endpoints:

- **`GET /products/mine`** → `List<HouseholdProductResponse>` = products your household has bought (confirmed, non-excluded), newest purchase first. Each: `{ productId, name, friendlyName, brand, category, timesBought, lastBoughtAt, lastUnitPrice, lastMarketCnpj, lastMarketName, lastMarketFriendlyName }`. `friendlyName` is your household's own rename of the product (null when never renamed) — prefer it for display. Display `lastMarketFriendlyName` (your custom market name when set, else the original `lastMarketName`).
  - **Search matches the rename too** (2026-08-26): the optional `query` filters by product name, brand **and `friendlyName`**. Same for `GET /products?query=` (returns products you renamed to something matching, even when the catalog name doesn't match) and `GET /receipts?q=`.
- **`GET /products/{id}/markets`** → `List<ProductMarketPriceResponse>` = where to buy this product, cheapest first. Scope: your **watched markets** always; nearby markets only when `includeNearby=true` (`radiusKm` from home). Each: `{ cnpj, cnpjRoot, marketName, friendlyName, price, priceType, communityMinPrice, sampleCount, distinctHouseholds, distanceKm, watched, visited, observedAt }`. Display `friendlyName` (custom-or-original).
  - **`priceType`** drives the privacy model: `OWN_LAST` = your household's own exact last paid price at a market you shopped at (your data). `COMMUNITY_MEDIAN` = the **k-anonymity-guarded** median from the collaborative index — only present when **≥3 distinct households** contributed (`economizai.collaborative.min-households-for-public`); below that the market is omitted, never shown with a single-source price. **Why:** the price index is anonymized (no user/household FK); exposing a lone contributor's single price would re-identify them (a market with one contributor = that person's purchase). K=3 guarantees no individual purchase is exposed. The threshold is configurable, so the rule can be relaxed later if the privacy/legal stance changes — without code edits.

The "review queue" (`/unmatched`) is the workflow for messy receipts: items that didn't auto-
match show up here, the user picks the right product, and the alias is
automatically backfilled to all matching items.

---

## 6. Markets + watchlist

```
GET    /api/v1/markets[?radiusKm=10]                 → catalogue (visited + watched + nearby)
GET    /api/v1/markets/watched                       → "Meus mercados"
POST   /api/v1/markets/watched/{cnpj}                → pin
DELETE /api/v1/markets/watched/{cnpj}                → unpin
PUT    /api/v1/markets/{cnpj}/name                   { "name": "Zaffari de casa" } → set a household-only custom name
DELETE /api/v1/markets/{cnpj}/name                   → revert to the global name
```

Each row carries `visited` (household has shopped here) and `watching` (user
pinned it) flags. Watched markets bypass the home-radius filter in price
intelligence — useful for "the market on my commute is far from home but I want
its promos anyway".

**Custom market name (household-only):** `PUT /markets/{cnpj}/name` saves a rename for your household. It does **not** overwrite the global name — instead every market-bearing response carries a separate **`friendlyName`** field (alongside the original `name`/`marketName`) that defaults to the original and is replaced by your rename when set. The FE should **display `friendlyName`**. It applies everywhere a market appears for your household (markets list, product-markets, receipts, items, insights, price history, notifications); the global name and other households are never affected. `DELETE` clears it (so `friendlyName` falls back to the original).

---

## 7. Collaborative price index

K-anonymity protected — queries return empty (or `kAnonBlocked: true`) until
≥ 3 distinct households contributed.

```
GET /api/v1/price-index/products/{productId}/markets/{cnpj}/reference
→ {
    "medianPrice": 28.50,
    "minPrice":    24.00,
    "maxPrice":    32.00,
    "sampleCount": 12,
    "distinctHouseholds": 4,
    "mostRecentAt": "2026-04-28T10:00:00",
    "kAnonBlocked": false
  }
```

When `kAnonBlocked: true`, `medianPrice` is `null` but `sampleCount` /
`distinctHouseholds` are still visible — show the "poucas amostras" warning.

```
GET /api/v1/price-index/products/{productId}/best-markets?limit=10&radiusKm=5[&lat=&lng=]
GET /api/v1/price-index/promos?radiusKm=5
```

`promos` returns currently-detected community promos in the user's area
(recent median ≥ 15% below baseline, k-anon protected).

`best-markets` measures distance from the user's HOME location by default; pass
`lat`/`lng` to measure from the current position instead (barcode scan inside a
store — see the flow below).

### Barcode scan flow (scan → price nearby)

```
GET /api/v1/products/by-ean/{ean}      → { known, product, catalogPreview }
```

Scan the product barcode, call `by-ean`:
- `known: true` → `product` is populated; follow up with
  `GET /price-index/products/{product.id}/best-markets?lat=&lng=&radiusKm=` for
  the cheapest nearby markets (k-anon protected).
- `known: false` → `catalogPreview` has `{ean, name, brand, category}` from the
  EAN catalog: we know the product but have no price data yet — show the
  preview and invite the user to scan a receipt containing it.
- `404` → barcode unknown to both. Non-digits in the path are stripped
  server-side; anything under 8 digits is a 404.

---

## 7b. Deals — "Ofertas pra você"

The active, ranked list of discounts currently relevant to the household: for
every product the household has bought, the best **currently-observed** community
price at a relevant market that beats what the household last paid by a
meaningful margin. This is the screen a future digest push will deep-link into.

```
GET /api/v1/deals?includeNearby=false&radiusKm=5&limit=20
→ [
    {
      "productId":        "…",
      "productName":      "Leite Integral 1L",
      "friendlyDescription": "Leite das crianças",  // household rename, null when never renamed
      "category":         "MEAT_DAIRY",
      "marketCnpj":       "12345678000199",
      "marketName":       "Atacadão (Centro)",   // household friendly name
      "currentPrice":     7.00,                   // k-anon community median
      "lastPaidPrice":    10.00,
      "savingsAmount":    3.00,
      "savingsPct":       30.00,
      "discountFraction": 0.3000,
      "distinctHouseholds": 4,
      "distanceKm":       2.3,                    // nullable
      "isWatched":        true,
      "observedAt":       "2026-06-01T18:00:00"
    }
  ]
```

Params (all optional): `includeNearby` (default `false` — watched markets only;
`true` also considers markets within `radiusKm` of home), `radiusKm`,
`limit` (default 20; `limit <= 0` returns `[]`).

Ranking: by savings (discount fraction) desc, tie-broken by purchase frequency.
The discount bar is **progressive** — ~20% required on a R$1 item, ~5% on a
R$200 one — so trivial drops don't surface. K-anonymity protected: the community
price is disclosed only when ≥ 3 distinct households contributed; below that the
market is dropped. Returns `[]` when the collaborative index is off or nothing
qualifies.

**Relevance feedback (2026-06-12):** the `DISMISSED` / `MUTED` events the app
reports (§10b) feed a per-user suppression filter on this list and the digest —
mute hides the product everywhere for 180 days, dismissal for 14 days. A
`DISMISSED` **with `marketCnpj`** hides only that (product, market) pair;
without it, the whole product. Currently in SHADOW (measured, not applied);
once validated it flips ON server-side with no API change — so always send
`productId` (+ `marketCnpj` for dismissals) on these events.

### How the daily deals digest works (push)

The deals screen above is always-on and read-only. The **digest** is the push
that nudges the user back to it. A scheduler runs hourly and, for each user who
is *due that hour* and hasn't already received a digest *today* (1/day hard cap),
recomputes their deals and sends **one** `DEALS_DIGEST` push — but only when at
least one deal is **newsworthy** (brand-new, discount improved by ≥ 5 p.p. /
crossed a stricter relevance step, or it lapsed past the collaborative lookback
window). Standing, unchanged deals never re-notify.

- **Preferences**: `GET`/`PUT /api/v1/users/me/digest-preferences` (above).
  `frequency` `OFF` = never; `WEEKLY` = once a week (currently Thursdays);
  `DAILY` = at most once a day. `sendHour` (0-23) overrides the send time;
  leave it `null` and the backend infers it from the household's typical
  shopping hour, falling back to ~16:00. Timezone is America/Sao_Paulo (v1).
- **The push** lands in the inbox as a `DEALS_DIGEST` `Notification` (see §
  Notifications). Body: e.g. `"Café 22% mais barato — e mais 3 ofertas pra
  você"`. Its `payload` carries `deeplink: "economizai://deals"`, `screen:
  "deals"`, `newsworthyCount`, and `bestProductId` / `bestMarketCnpj` /
  `bestDiscountFraction`.
- **FE on tap**: open the deals screen and fire `PUSH_OPENED` via
  `POST /api/v1/notifications/events` (with the notification id), then proceed
  with the normal `SCREEN_OPENED` / `DEAL_VIEWED` / `DEAL_TAPPED` flow.

This endpoint emits **no** telemetry. The app reports `SCREEN_OPENED` on open and
`DEAL_VIEWED` / `DEAL_TAPPED` on interaction itself, via
`POST /api/v1/notifications/events` (§10b), keyed by `productId` + `marketCnpj`.

### Savings — "você economizou com as dicas"

```
GET /api/v1/users/me/savings
```

Returns the household's realized R$ savings attributable to deals we surfaced
(the product's north-star). Use it for a "você economizou R$ X com nossas dicas"
card.

```json
{ "totalSavings": 42.50, "conversions": 7, "last30DaysSavings": 15.00 }
```

- `totalSavings` — lifetime R$ saved across all attributed purchases.
- `conversions` — lifetime count of attributed purchases.
- `last30DaysSavings` — same as `totalSavings`, restricted to the trailing 30 days.
- Scoped to the household (sums every household member's attributed savings).
  Empty history → all zeros. 401 if unauthenticated.

**How attribution works (server-side, automatic):** when a user confirms a
receipt, the backend checks each purchased line against the deals we recently
surfaced to that household. If a product was surfaced as a deal at that market
within the **attribution window (14 days)** and hasn't already been attributed,
it counts as a **conversion**: we record a server-side `CONVERTED` telemetry
event carrying the realized savings `(previousLastPaid − paidUnitPrice) ×
quantity` (counted only when positive; `previousLastPaid` is the household's last
paid unit price *before* this receipt, falling back to the surfaced deal's
baseline when there's no prior purchase). Attribution is best-effort and
correlational, never causal — and it never blocks or delays a confirm.

### Subscription status — "PRO until …"

```
GET /api/v1/users/me/subscription
```

Self-serve view of the user's billing state. Purchases happen **FE-side**
(App Store / Play Store via RevenueCat; web provider later) and are reflected
into the backend by webhooks — this endpoint is what the app reads to render
the paywall state and a "manage subscription" screen.

```json
{ "tier": "PRO", "status": "ACTIVE", "provider": "revenuecat", "currentPeriodEnd": "2026-07-12T10:00:00" }
```

- `tier` — `FREE` | `PRO` (same value as in `GET /users/me`).
- `status` — provider lifecycle: `ACTIVE` | `CANCELED` | `EXPIRED`, or `null`
  if the user never had a subscription record (plain FREE).
- `provider` — e.g. `revenuecat`, `manual` (admin grant), `null` for FREE.
- `currentPeriodEnd` — when the paid period lapses; the hourly expiry sweep
  downgrades to FREE after this. `null` for FREE.
- Route "manage subscription" by `provider`: store-bought subs are managed in
  the store (App Store / Play), not via our API.

---

## 8. Consumption intelligence

Per-product purchase prediction + suggested shopping list, derived from
confirmed receipts + manual purchases.

```
GET /api/v1/consumption/predictions
→ list of ConsumptionPredictionResponse, sorted by daysUntilNextPurchase ASC
```

Each prediction carries `productName` **and `friendlyDescription`** (the
household's rename, null when never renamed — prefer it for display), plus
`status` (`OK` / `RUNNING_LOW` / `RAN_OUT`),
`daysUntilNextPurchase` (negative when overdue), `confidence` (LOW/MEDIUM/HIGH),
`averageQuantityPerPurchase`. **Volume gate**: products with fewer than 2 prior
purchases are silently skipped. Returns empty array, never an error.

```
GET /api/v1/consumption/suggested-list[?includeUpcoming=true&upcomingLimit=5]
→ { "items": [ ... ], "generatedAt": "..." }
```

By default, only `RAN_OUT` + `RUNNING_LOW` items. With `includeUpcoming=true`,
also includes the next N `OK`-status items so the empty state can read "Você
está bem abastecido — próximos a vencer:" instead of being literally empty.

```
POST   /api/v1/consumption/snooze/{productId}        { "days": 7 }   ← "Não preciso agora"
DELETE /api/v1/consumption/snooze/{productId}        → clear snooze
POST   /api/v1/consumption/manual-purchase           { "productId": "...", "quantity": 1 }   ← "Já comprei sem nota"
```

Snoozes and manual purchases are per-household. A manual purchase implicitly
clears any active snooze on that product (the user just took action).

---

## 9. Shopping list optimizer (PRO-52)

```
POST /api/v1/shopping-list/optimize
{
  "items": [
    { "productId": "<uuid-1>", "quantity": 2 },
    { "productId": "<uuid-2>", "quantity": 1 }
  ]
}
→ {
    "marketPlans": [
      {
        "marketCnpj": "...",
        "marketName": "...",
        "subtotal": 35.40,
        "itemCount": 2,
        "items": [ { "productId": "...", "productName": "...", "friendlyDescription": null, "quantity": 2, "estimatedUnitPrice": 12.50, "estimatedSubtotal": 25.00, "priceSource": "LOCAL_HISTORY" } ]
      }
    ],
    "estimatedTotal": 35.40,
    "unpriced": [ { "productId": "...", "productName": "...", "friendlyDescription": null, "reason": "no observed price (local or community)" } ]
  }
```

Greedy heuristic: per item, pick the cheapest known market. Price source
priority: (1) household's own most-recent purchase, (2) community-index
median, (3) unpriced (FE shows "preço indisponível" badge).

No travel-time modeling yet — V1 picks lowest-cost regardless of how many
markets the user would have to visit.

`items[*]` and `unpriced[*]` carry `productName` **and `friendlyDescription`**
(the household's rename, null when never renamed — prefer it for display).

---

## 10. Household preferences (Phase 2.6)

```
GET    /api/v1/preferences
       → list of HouseholdPreferenceResponse (one per generic the household buys regularly)

PUT    /api/v1/preferences/brand/{genericName}
       { "brand": "Itambé", "strength": "MUST_HAVE" }    → 204
DELETE /api/v1/preferences/brand/{genericName}            → 204
```

Auto-derived from purchase history. Surfaces the typical pack size + dominant brand per generic. **Volume gate** for derived entries: silent until the household has 5+ confirmed purchases of a given generic.

`brandStrength`: `PREFERRED` (top brand 60–85% share) or `MUST_HAVE` (>85%). FE can render as a soft hint ("você costuma comprar Italac") or a hard filter on suggested-list views.

**Manual brand override** — `PUT /preferences/brand/{genericName}` lets the user explicitly say "for milk, my brand is Itambé". The override **wins over derived** in `GET /preferences`: the row will carry the user's chosen brand + strength, but the underlying `brandDistribution`, `sampleSize`, and pack-size fields stay derived so the user still sees the historical signal. Manual entries can also surface generics the household hasn't bought yet (sample size will be 0). `DELETE` to clear and fall back to derived.

---

## 10b. Notifications inbox

```
GET  /api/v1/notifications?page=0&size=20    → paginated list, newest first
GET  /api/v1/notifications/unread-count      → { "unread": N }
POST /api/v1/notifications/{id}/read         → mark single as read
POST /api/v1/notifications/mark-all-read     → { "marked": N }
```

Each `NotificationResponse` carries `payload` — the JSON we attached when we generated the notification (`receiptId`, `productId`, `savingsPct`, etc) so cards can deep-link. `type` is one of `PROMO_PERSONAL`, `PROMO_COMMUNITY`, `CHEAPER_MARKET`, `DEALS_DIGEST`, `DIGEST`, `PRICE_DROP`, `STOCKOUT`, `BUDGET`, `SYSTEM` — what triggers each and how to toggle it is in §10f.

Each response also carries `destination` — a routing hint derived from `type` so the FE knows which screen to open when the card is tapped (the concrete id it needs is already in `payload`). Values:

| `destination`   | screen                 | types that map to it                                          |
|-----------------|------------------------|--------------------------------------------------------------|
| `DEALS`         | deals / offers screen  | `PROMO_PERSONAL`, `PROMO_COMMUNITY`, `CHEAPER_MARKET`, `DEALS_DIGEST`, `DIGEST` |
| `REPLENISHMENT` | replenishment / stock  | `STOCKOUT`                                                    |
| `PRODUCT`       | product detail         | `PRICE_DROP` (use `payload.productId`)                        |
| `BUDGET`        | budget screen          | `BUDGET`                                                      |
| `INBOX`         | generic inbox          | `SYSTEM` (and any future/unmapped type)                      |

> **Real-time vs digest:** the only notifications pushed in **real time** (on receipt confirm) are the user's explicit alerts — `PRICE_DROP` and `BUDGET`. The **discovery** notifications (`PROMO_PERSONAL` / `PROMO_COMMUNITY` / `CHEAPER_MARKET`) are now delivered **only via the daily deals digest** (§ "How the daily deals digest works"), not in real time.

### Report a telemetry event (client → server)

```
POST /api/v1/notifications/events            → 202 Accepted
```

Body:
```json
{ "type": "DEAL_TAPPED", "notificationId": "uuid?", "productId": "uuid?", "marketCnpj": "..?" }
```

The app fires these to record how the user engages with notifications and deals — this behavioral signal is logged now and will power relevance-ranked / smarter notifications later. Only `type` is required; the rest are optional context.

**Client-reportable `type` values** (anything else → `400`):
- `PUSH_OPENED` — user opened a push notification
- `SCREEN_OPENED` — user opened the deals/notifications screen (no originating push needed)
- `DEAL_VIEWED` — a surfaced deal entered the viewport
- `DEAL_TAPPED` — user tapped a deal
- `ADDED_TO_LIST` — user added the deal's product to a shopping list
- `DISMISSED` — user dismissed/swiped a deal or notification
- `MUTED` — user muted this product/market/type

Server-only lifecycle types (`SENT`, `DELIVERED`, `CONVERTED`) are rejected with `400` if posted by a client. Unknown types → `400`. Scoped to the authenticated user (JWT). Authenticated endpoint — no token → `401`.

---

## 10c. Persistent shopping lists

For one-shot ad-hoc optimization, see §9. For build-edit-shop workflows, use these:

```
GET    /api/v1/shopping-lists                                          → list (newest first)
GET    /api/v1/shopping-lists/sole                                     → 200 + list if exactly 1 exists; 404 otherwise
POST   /api/v1/shopping-lists                                          → create
       { "name": "...", "items"?: [ { "productId"? | "freeText"?, "quantity"? } ] }
GET    /api/v1/shopping-lists/{id}                                     → detail with items
PATCH  /api/v1/shopping-lists/{id}                                     → rename
       { "name": "..." }
DELETE /api/v1/shopping-lists/{id}                                     → delete (cascades items)

POST   /api/v1/shopping-lists/{id}/items                               → add item
       { "productId"? | "freeText"?, "quantity"? }
POST   /api/v1/shopping-lists/{id}/items/{itemId}/toggle               → toggle checked
DELETE /api/v1/shopping-lists/{id}/items/{itemId}                      → remove item
```

Each item is **either** linked to a canonical `Product` (auto-suggestion-friendly) **or** free text — the request must include exactly one. Free-text entries can be upgraded later by replacing the row with a productId-bound one.

`ShoppingListResponse.items[*].displayName` is the resolved label, in precedence order: the household's **`friendlyDescription`** (their own rename of the product, when set) → `productName` (catalog name, when linked) → `freeText`. `friendlyDescription` is also exposed as its own nullable field on each item (2026-08-27), matching `ReceiptItemResponse`.

---

## 10d. Admin (ROLE_ADMIN only — not consumed by the FE)

```
GET    /api/v1/admin/users?q=&page=&size=&sort=   → Page<AdminUserSummaryResponse> (sort: createdAt|name|subscriptionTier|receiptCount|totalSpend, e.g. receiptCount,desc)
GET    /api/v1/admin/users/{id}              → AdminUserDetailResponse
DELETE /api/v1/admin/users/{id}              → 204 (deletes account + data; 400 for ADMIN accounts)
GET    /api/v1/admin/receipts?from=&to=&marketCnpj=&category=&q=&householdId=&uf=&status=&parseErrorReason=&page=&size=
                                              → Page<ReceiptSummaryResponse>
GET    /api/v1/admin/receipts/{id}            → ReceiptResponse
POST   /api/v1/admin/receipts/{id}/reparse    → 200 ReceiptResponse
POST   /api/v1/admin/notifications/test       → 202 Accepted
GET    /api/v1/admin/notifications/sent?days=30&page=&size=  → Page<AdminNotificationSummaryResponse> (sent notifications, newest first)
GET    /api/v1/admin/products?page=&size=&q=&category=&source= → Page<ProductResponse> (catalog; optional filters: name/EAN substring, category e.g. OTHER, categorizationSource)
GET    /api/v1/admin/products/missing-brand    → Page<MissingBrandProductResponse>
PATCH  /api/v1/admin/products/{id}/brand       → 200 ProductResponse
PATCH  /api/v1/admin/products/{id}/category    → 200 ProductResponse — body {"category":"PET_SUPPLIES"}; sets GLOBAL category + locks it (source=USER; recategorize/LLM won't override)
GET    /api/v1/admin/products/duplicates       → List<DuplicateProductGroupResponse>
POST   /api/v1/admin/products/{id}/merge       → 200 ProductMergeResultResponse
GET    /api/v1/admin/products/recategorize        → RecategorizeReportResponse (dry-run, read-only)
POST   /api/v1/admin/products/recategorize?includeMl=false → RecategorizeResultResponse (apply)
POST   /api/v1/admin/products/refresh-brands       → BrandBackfillResponse (fill missing brands)
POST   /api/v1/admin/markets/classify-segments     → SegmentClassificationSummary (CNAE-classify pending markets)
GET    /api/v1/admin/merchants/grey                → List<GreyMerchantResponse> (grey-zone review queue, ranked by scan volume)
GET    /api/v1/admin/llm/report                    → LlmReportResponse (LLM labels, cost, override-rate KPI, disagreement queue)
POST   /api/v1/admin/llm/disagreements/{id}/resolve?accept=true|false → 204 (accept applies suggestion as source USER)
PUT    /api/v1/admin/merchants/{cnpj}/support      → SupportOverrideResult — body {"override":"SUPPORTED"|"BLOCKED"|null}; SUPPORTED backfills the price index from the merchant's confirmed receipts
DELETE /api/v1/admin/products/{id}?force=false     → 200 ProductDeletionResponse (prune test/junk catalog rows)
GET    /api/v1/admin/analytics/acquisition?days=30&includeInternal=false → AcquisitionAnalyticsResponse (signup funnel + timeline by channel + byPlatform + campaign breakdown + Meta ad-spend / cost-per-signup)
GET    /api/v1/admin/analytics/subscriptions?includeInternal=false       → SubscriptionAnalyticsResponse (users by tier, paying-active, promo-granted)
GET    /api/v1/admin/analytics/retention-cohorts?weeks=8&includeInternal=false → RetentionCohortResponse (weekly cohort triangle + per-channel pooled retention curve)
POST   /api/v1/admin/analytics/ad-spend/sync       → {"rowsSynced": n} — run the Meta ad-spend sync now instead of waiting for the daily cron (0 when Meta is not configured)
GET    /api/v1/admin/costs?days=30                 → CostReportResponse (paid-API spend: total + by service + by state + today vs budget)
GET    /api/v1/admin/state-coverage                → StateCoverageResponse (per-UF: VERIFIED/EXPERIMENTAL + per-layer success/failure telemetry from real scans)
GET    /api/v1/admin/ingestion-health?days=30      → IngestionHealthResponse (pipeline health: status mix, parse success rate, stuck/timeout counts, per-UF outcomes, top parseErrorReason keys — from the receipts table)
GET    /api/v1/admin/notifications/relevance-report?days=30 → RelevanceReportResponse (deal-suppression shadow-mode KPI before flipping relevance ON)
DELETE /api/v1/admin/receipts/{id}/observations    → PurgeObservationsResponse (pull one receipt's contributions out of the price index)
GET    /api/v1/admin/ecommerce/offers?ean=&page=&size= → Page<CuratedOfferResponse> (curated online offers, newest edits first)
POST   /api/v1/admin/ecommerce/offers              → CuratedOfferResponse — body CuratedOfferRequest {ean, productId?, provider, title, externalUrl?, affiliateUrl?, imageUrl?, price, freight?, currency?, inStock?, active?}
PUT    /api/v1/admin/ecommerce/offers/{id}         → CuratedOfferResponse
DELETE /api/v1/admin/ecommerce/offers/{id}         → 204
GET    /api/v1/admin/leaderboard/discount-hunters?days=30 → LeaderboardResponse (all households, emails as handles)

# ── User-facing e-commerce + leaderboard ──
GET    /api/v1/receipt-items/{id}/offer?cep=       → EcommerceOfferResponse (best online offer vs what the user paid) | 204 when no offer for the item's EAN
GET    /api/v1/leaderboard/discount-hunters?days=30 → LeaderboardResponse (public: opted-in households only, + your own `me` standing)
PATCH  /api/v1/leaderboard/opt-in                  → 204 — body {"optIn": true|false} (appear in the public leaderboard)
GET    /api/v1/admin/observations/orphaned-count   → OrphanedObservationsResponse (deleted-account leftovers in the index)
DELETE /api/v1/admin/observations/orphaned         → PurgeObservationsResponse (bulk-purge orphans; DEV-ONLY — gated by DEV_CLEANUP_ENABLED)
POST   /api/v1/admin/dev/seed-discounted-receipt?targetEmail= → ReceiptResponse (DEV-ONLY, gated by ECONOMIZAI_ADMIN_DEV_SEED_ENABLED: plants a realistic PENDING receipt with 3 promotional lines for QA/screenshots; targetEmail seeds another account; re-seed replaces earlier seeds)
```

- **Delete product** — removes a product and its CASCADE dependents (aliases, observations, category overrides, alerts, snoozes, shopping-list items). Receipt items that referenced it are detached (`product_id` → null), so confirmed purchase history survives as unmatched rows. Refuses with `409 product.deletion.referenced` when the product still backs confirmed purchases unless `force=true`. Returns `{ productId, receiptItemsDetached }`.

- **Users list** — `q` does substring match on email + name (case-insensitive). Sorted by `createdAt` desc by default.
- **User detail** — bundles `householdId`, `householdMemberCount`, `receipts` counts (PENDING_CONFIRMATION / CONFIRMED / REJECTED / FAILED_PARSE), and `spendLast30Days`. Useful for triaging "why is X seeing Y".
- **Receipts list** — same content-search semantics as `GET /receipts` (substring on raw + friendly description, product name, market name) but cross-household. `householdId` is an additional optional filter to scope to one household. Includes `FAILED_PARSE` rows (useful for parser triage).
- **Receipts get** — bypasses the per-household ownership check.
- **Reparse** — re-runs parsing on the receipt's stored raw HTML and replaces its items with the freshly-parsed ones. Resets `status` to `PENDING_CONFIRMATION` and clears `confirmedAt` — the owner re-confirms to commit. Useful when a parser fix lands. 400 if `rawHtml` is missing (e.g. legacy rows from before we persisted it).
- **Notifications test** — body `{ "email": "...", "title"?, "body"?, "type"? }`. Resolves the user by email and dispatches a payload through `NotificationService`. Useful to verify push/SMTP wiring on demand. Returns 202 even if the channel is stubbed; check the inbox (`GET /notifications`) or the device to confirm delivery. `type` defaults to `SYSTEM`.
- **Products missing brand** — paginated list of products whose brand wasn't auto-extracted (`Product.brand IS NULL`). Each entry includes up to 5 sample `rawDescription` strings from the product's aliases — gives the curator context for what name to assign. Sorted by `normalizedName` so paging is stable.
- **Set product brand** — body `{ "brand": "Tio João" }`. Lightweight PATCH that only sets `brand`. Returns the updated `ProductResponse`. Use after the missing-brand listing to fill catalog gaps; subsequent canonicalization for items of this product (and the metadata-dedup gate) become more accurate as soon as brand is set.
- **Products duplicates** — returns groups of products that share an exact `(genericName, brand, packSize, packUnit)` profile and are therefore probable duplicates. Each group: `{ genericName, brand, packSize, packUnit, category, products: [ProductResponse] }`. Only products with all four metadata dimensions populated are eligible. Within a group, the oldest product appears first — a natural default survivor.
- **Merge product** — body `{ "absorbedId": "<uuid>", "dryRun": false }`. The `{id}` in the path is the **survivor**. Migrates all aliases, receipt items, price observations, manual purchases, shopping-list items, household aliases (drops where survivor already has one for the household), and consumption snoozes (same conflict logic) from `absorbed` to the survivor; deletes `absorbed`. Set `dryRun: true` to get the migration counts without applying any change — the only undo is to **not** apply. Returns `ProductMergeResultResponse` with per-table counts.
- **Recategorize catalog** — re-runs the extraction cascade over every product's stored description and compares to what's stored. `GET` = dry-run report `{ totalProducts, mismatchCount, applicableFromDictionary, mlSuggestions, skippedUserOverrides, mismatches:[{productId, normalizedName, ean, currentCategory, currentSource, suggestedCategory, suggestedSource, userOverride}] }`. `POST` = apply: by **default applies only trusted dictionary suggestions** (the ML layer is currently unreliable), skipping manual (`source=USER`) categories and null suggestions. Pass `?includeMl=true` to also apply ML suggestions. Use after editing the dictionary to fix already-ingested products (categorization otherwise only runs once per product, at creation).

- **Acquisition analytics** — `GET /admin/analytics/acquisition?days=30` (window default 30). Powers the marketing dashboard from the immutable `acquisitionChannel` derived at signup (see §1 `attribution`) plus the Meta ad-spend rollup. Shape:
  ```
  {
    "windowDays": 30, "from": "...", "to": "...",
    "funnel": { "signups": N, "verified": N, "activated": N, "proTier": N,
                "verifiedRate": 0..1, "activatedRate": 0..1, "proRate": 0..1 },
    "timeline": [ { "date": "2026-09-14", "total": N, "byChannel": { "INSTAGRAM_PAID": N, ... } } ],
    "byChannel": [ { "channel": "INSTAGRAM_PAID", "signups": N, "verified": N, "proTier": N } ],
    "byCampaign": [ { "source": "instagram", "medium": "cpc", "campaign": "black-friday",
                      "signups": N, "verified": N, "proTier": N,
                      "adSpend": 123.45, "costPerSignup": 4.56 } ],
    "adSpend": { "configured": false, "currency": "BRL", "totalSpend": 0, "paidSignups": N,
                 "costPerSignup": null, "costPerActivated": null,
                 "byCampaign": [ { "campaignId": "...", "campaignName": "...",
                                   "spend": 0, "clicks": N, "impressions": N } ],
                 "note": "..." }
  }
  ```
  `acquisitionChannel` ∈ `INSTAGRAM_PAID | GOOGLE_PAID | PAID_OTHER | REFERRAL | ORGANIC | DIRECT | UNKNOWN`. Rates are 0..1 fractions. **Meta ad-spend is inert until env vars are set** — when unconfigured `adSpend.configured` is `false`, spend is 0 and the cost-per-* fields are `null` (see DEV_NOTES "Meta Ads spend sync").
- **Subscription analytics** — `GET /admin/analytics/subscriptions`. `{ totalUsers, byTier: { FREE, PRO }, payingActive, promoGranted, note }` — a quick tier snapshot for the dashboard (paying-active vs promo-granted PRO). `payingActive` counts only real payment-provider subscriptions — promo/admin grants (`provider = "manual"` or null) fall under `promoGranted`.
- **Trigger ad-spend sync** — `POST /admin/analytics/ad-spend/sync` → `{ "rowsSynced": n }`. Runs the Meta sync on demand (the dashboard otherwise refreshes via the daily cron). Returns `0` when Meta is unconfigured.
- **`includeInternal` (both analytics GETs, default `false`)** — when false, excludes admins (`role = ADMIN`), test accounts (`email LIKE %@economizaai.app`), Google Firebase Test Lab robots (`email LIKE %@cloudtestlabaccounts.com` — Play Console pre-launch/review accounts), and any account flagged `excludedFromMetrics`. Pass `true` to include everyone.
- **Exclude an account from metrics** — `PATCH /admin/users/{id}/metrics-exclusion` body `{ "excluded": true|false }` → `AdminUserDetailResponse`. Hides a store-review / robo test account from ALL metrics without deleting it. `excludedFromMetrics` also appears on `AdminUserSummaryResponse` (the list) and `AdminUserDetailResponse`. NOTE: `@icloud.com` / `@privaterelay.appleid.com` are **real** "Sign in with Apple" domains — NOT auto-excluded; flag a specific Apple reviewer account manually if needed.
- **Admin user list ranking** — `AdminUserSummaryResponse` carries `receiptCount` (nº de notas do domicílio) and `totalSpend` (soma das notas confirmadas, R$). `/admin/users?sort=` accepts `receiptCount,desc` / `totalSpend,desc` on top of the entity sorts (`createdAt`, `name`, `subscriptionTier`) — the aggregate sorts are resolved server-side.
- **Ops error drill-down** — `/admin/receipts?parseErrorReason=<key>&status=FAILED_PARSE` lists the receipts behind one "erro mais comum" (the machine key before `:` in the ingestion-health breakdown). Matches the exact key or `key:details`.
- **Market intel by chain** — `/admin/market-intel` returns `topMarketsByChain` (grouped by the **CNPJ root** = first 8 digits of the company registration, so every branch of a chain like Zaffari collapses into one row) alongside `topMarkets` (one row per full CNPJ). Same `{ cnpj, name, scans, spend }` shape; on the by-chain list `cnpj`/`name` are one representative unit of the chain.
- **Sent notifications** — `GET /admin/notifications/sent?days=30&page=&size=` → `Page<AdminNotificationSummaryResponse>` `{ id, userEmail, type, channel, title, body, delivered, deliveredAt, failureReason, read, createdAt }`, newest first — the "notificações enviadas" list.
- **E-commerce "vale a pena online?"** — INERT by default (see DEV_NOTES). `GET /receipt-items/{id}/offer` returns the cheapest online offer for the item's EAN (`EcommerceOfferResponse`: `provider, title, price, freight, total, currency, externalUrl, affiliateUrl, imageUrl, inStock, curated, worthIt, paidPrice, savings`); `worthIt` = savings ≥ the configured margin; **204** when no offer matches. With no provider configured it serves only admin-**curated** offers — CRUD at `/admin/ecommerce/offers` (`CuratedOfferRequest`, EAN → online product + price + link). Providers are pluggable (Mercado Livre included, dark until credentials).
- **Discount-hunter leaderboard** — `GET /leaderboard/discount-hunters?days=30` → `LeaderboardResponse` `{ windowDays, entries:[{ rank, handle, finds, savings, isMe }], me }`. Public view lists only opted-in households (first-name handles) but always returns the caller's own `me`. Opt in/out via `PATCH /leaderboard/opt-in` `{ optIn }`. Admin view `/admin/leaderboard/discount-hunters` lists everyone with email handles (`me` null). Metric = confirmed items bought below the community average unit price (product seen by ≥2 households).
- **`byPlatform` (acquisition response)** — `[{ platform: "WEB"|"ANDROID"|"IOS"|"UNKNOWN", signups }]` — where the signup happened (from the immutable `registrationPlatform`); breaks down the `UNKNOWN`-channel signups (`UNKNOWN` = client didn't send a platform).
- **`adSpend` budget/status** — `adSpend.budgetRemaining` (aggregate R$) and, per `adSpend.byCampaign[]`: `status` (Meta effective_status), `lifetimeBudget`, `budgetRemaining`, `endsAt` (YYYY-MM-DD), `ended` (bool). Synced from Meta by the ad-spend job; all null until Meta is configured + synced.
- **`revenue` (acquisition response)** — money view. `realizedRevenue` is **real** provider money in the window (from RevenueCat purchase/renewal events → `RevenueEvent`; starts ~0, grows). Everything else is **modeled** from the configured PRO price until real payments accumulate (`revenueRealized=false` while there's none): `mrrProxy` (all PRO × monthly price), `ltvPerProUser` (monthly × `assumedLifetimeMonths`), `projectedLtv` (PRO signups in window × that), `roas` (realized ÷ spend), `projectedRoas` (projectedLtv ÷ spend), `ltvToCac` (ltvPerProUser ÷ cost-per-paid-signup), `byChannel:[{ channel, proUsers, realizedRevenue, projectedLtv }]`, `note`. Price is configurable via `PRO_MONTHLY_PRICE` (def. 9.90) / `PRO_YEARLY_PRICE` (89.00) / `PRO_ASSUMED_LIFETIME_MONTHS` (12). ROAS fields are `null` when there's no spend. Also carries **`payingCustomers`** (ACTIVE subscriptions with a real payment provider — excludes promo/manual grants) and **`avgTicket`** (ticket médio = `realizedRevenue ÷ payingCustomers`); both are `0` until billing is live, wired to populate automatically once real payments arrive.
- **Retention cohorts** — `GET /admin/analytics/retention-cohorts?weeks=8` (weeks clamped 4–16). The retention triangle: `{ weeks, cohorts:[{ weekStart (ISO Monday), size, activeByWeek:[…] }], byChannel:[{ channel, users, retentionByWeek:[…] }] }`. Users are grouped by signup week; `activeByWeek[k]` = distinct users from that cohort who scanned a receipt `k` weeks after signup (`k=0` = signup week). Each cohort's array only spans the weeks it's old enough to have observed (the FE renders a triangle — recent cohorts are shorter — never fabricated future zeros). `byChannel` is the per-channel **pooled** curve: `retentionByWeek[k]` in `0..1` over every cohort mature enough to have observed week `k`, so a fresh cohort never drags the tail down. Answers "do the users a channel brings keep coming back?"
- **`visits` (acquisition response)** — first-party top of funnel: `{ totalVisits, uniqueVisitors, signups, visitToSignupRate, byCampaign:[{ channel, source, medium, campaign, uniqueVisitors, signups, conversionRate }], note }`. Fed by the public visit beacon (below), so click→signup conversion is OUR number, independent of Meta. `note` set when no visits recorded yet.
- **`retention` (acquisition response)** — per channel cohort quality: `[{ channel, cohort, activated, activationRate, cohort7, retainedD7, retentionD7, cohort30, retainedD30, retentionD30 }]`. "Retention" = activation (first receipt) within 7/30 days of signup; D7/D30 denominators only count signups old enough to have had the full window.

All require a JWT for a user with `Role.ADMIN`. Regular users hit 403.

### Visit beacon (public, no auth)

`POST /api/v1/visits` → **204**. Anonymous top-of-funnel beacon the web landing fires once per session on first load (localStorage `ea_anon_id`). Body:
```
{ "anonId": "<random client id>", "platform": "WEB",
  "attribution": { "utmSource", "utmMedium", "utmCampaign", "utmContent", "utmTerm",
                   "clickId", "referrer", "landingPath" } }
```
All fields optional except a non-blank `anonId` (missing → silently ignored). Backend derives the same `acquisitionChannel` as signup, stores no PII (client IP kept only as a one-way hash). Rate-limited 60/min per IP. Powers `acquisition.visits`.

---

## 10e. Price alerts ("avise-me quando")

User-curated price-drop rules. A rule fires when **any** household's confirmed receipt contributes a matching low price to the collaborative index — the community retention loop.

```
POST   /api/v1/alerts          → 201 PriceAlertResponse
       { "productId": "<uuid>", "thresholdPrice": 5.99, "radiusKm"?: 5, "active"?: true }
GET    /api/v1/alerts          → 200 List<PriceAlertResponse> (newest first)
DELETE /api/v1/alerts/{id}     → 204 (404 if not the caller's)
```

`PriceAlertResponse`: `{ id, productId, productName, friendlyDescription, thresholdPrice, radiusKm, active, lastFiredAt, createdAt }` — `friendlyDescription` is the household's rename of the product (null when never renamed), prefer it for display.

- **Upsert semantics:** one rule per (user, product). Re-`POST`ing the same `productId` updates the existing rule's threshold/radius/active — no duplicates, no 409.
- **`radiusKm`** is measured from the user's home. If set but home or market coordinates are unknown, the rule does **not** fire (constraint honored). Omit it to match anywhere in the network.
- **No self-notify:** a rule never fires for the contributor's own household.
- **Cooldown:** at most one fire per rule per 24h.
- **Delivery:** fires a `PRICE_DROP` notification (see §10b) with `payload = { ruleId, productId, observedPrice, thresholdPrice, marketCnpj, marketName }`.

> **Note:** `/api/v1/alerts` is now a backward-compatible alias over the unified rule engine (§10f) — a price alert is a `PRICE_DROP` notification rule. New rule types and the enable/disable surface live under `/api/v1/notification-rules`.

---

## 10f. Notification rules (unified create / enable / disable)

One surface for every notification trigger — the rules the user creates **and** the system defaults they can toggle. `GET` returns both (defaults are lazily seeded per user, active by default, `isDefault: true`).

```
GET    /api/v1/notification-rules            → 200 List<NotificationRuleResponse> (defaults first, then newest)
POST   /api/v1/notification-rules            → 201 NotificationRuleResponse   (create/upsert a user rule)
       { "type", "productId"?, "thresholdPrice"?, "radiusKm"?, "leadTimeDays"?, "channel"?, "active"? }
PATCH  /api/v1/notification-rules/{id}       → 200 NotificationRuleResponse   (partial: any of active/thresholdPrice/radiusKm/leadTimeDays/channel)
DELETE /api/v1/notification-rules/{id}       → 204 (defaults can't be deleted — disable instead → 400)
```

`NotificationRuleResponse`: `{ id, type, productId, productName, friendlyDescription, thresholdPrice, radiusKm, leadTimeDays, channel, active, isDefault, lastFiredAt, createdAt }` — `friendlyDescription` is the household's rename of the product (null for product-less rules or when never renamed), prefer it for display.

**User-creatable types** (you supply the params; validated server-side):

| type | needs | fires when |
|------|-------|-----------|
| `PRICE_DROP` | productId + thresholdPrice (+radiusKm?) | the product is seen at/below your price anywhere in the network (= "avise-me quando", §10e) |
| `STOCKOUT` | productId (+leadTimeDays, default 3) | replenishment — we predict your usual product is about to run out (from your buying cadence) and warn you `leadTimeDays` ahead |
| `BUDGET` | thresholdPrice | your household's confirmed spend this calendar month reaches the threshold |

**System defaults** (auto-seeded, toggle `active`; no params needed):

| type | fires when |
|------|-----------|
| `PROMO_PERSONAL` | you paid below your own historical median on a confirmed receipt |
| `PROMO_COMMUNITY` | a product you've bought is flagged as a promo anywhere in the network |
| `CHEAPER_MARKET` | a product you've bought is seen **at one of your watched markets** (§ Markets) below your last paid price — see drop rule below |
| `DIGEST` | weekly (Mon 08:00) summary of your household's activity |

- **`CHEAPER_MARKET` scope & toggle:** by default it watches only **your favourite/watched markets**. Set `radiusKm` on the rule (via PATCH) to *also* include any market within that distance of home. The required drop **scales with price**: ~20% on a ~R$1 item down to ~5% on a ~R$200 item (log-interpolated), so cheap items need a deep cut to ping you and pricey items trigger on a small %. Payload includes `lastPaidPrice` and `savingsPct`.
- **Upsert:** `POST` of an existing `(type, productId)` updates it. Posting a default-scope type is rejected (`400`) — toggle it instead.
- **Channel:** `channel` overrides the per-type preference (§10 preferences) for that one rule. Channels: `PUSH` (live), `EMAIL` (live once SMTP is wired), `ALEXA`/`SMS`/`WHATSAPP` (structure only — not yet functional), `NONE`.
- **No self-notify / cooldown:** community rules never fire for the contributor's own household; at most one fire per rule per 24h.

---

## 11. Categorizer admin (rarely needed by the FE)

```
GET  /api/v1/categorizer/classify?description=Milho&description=Lays
                                       → full chain: dictionary + ML + final decision (dev)
GET  /api/v1/categorizer/ml/predict?description=Milho&description=Lays
                                       → ML model ALONE (dev — inspect/improve the model)
GET  /api/v1/categorizer/benchmark     → categorization accuracy % over the golden set (records a snapshot)
GET  /api/v1/categorizer/quality/history?limit=50 → quality trend over time (snapshots)
GET  /api/v1/categorizer/status        → ML model state
POST /api/v1/categorizer/retrain       → trigger retraining manually          [ADMIN only]
POST /api/v1/categorizer/auto-promote  → trigger learned-dictionary promotion  [ADMIN only]
POST   /api/v1/categorizer/promote-consensus  → graduate user-correction consensus → learned dict [ADMIN only]
GET    /api/v1/categorizer/consensus         → list all CONSENSUS-graduated products             [ADMIN only]
DELETE /api/v1/categorizer/learned           → clear all auto-promoted learned entries + reset in-memory dict [ADMIN only]
DELETE /api/v1/categorizer/consensus         → revert all CONSENSUS-graduated products to NONE  [ADMIN only]
POST   /api/v1/categorizer/dictionary/import  → bulk-upsert token mappings into learned dict       [ADMIN only]
POST   /api/v1/categorizer/dictionary/curated/import → bulk-upsert CURATED entries (highest tier), hot-reloaded [ADMIN only]
GET    /api/v1/categorizer/dictionary/curated?q=&page=&size= → Page<CuratedEntryResponse> — list/search curated entries [ADMIN only]
DELETE /api/v1/categorizer/dictionary/curated/{id} → 204 — delete one curated entry + hot-reload  [ADMIN only]
GET    /api/v1/categorizer/dictionary/learned?q=&page=&size= → Page<LearnedEntryResponse> — list/search learned entries [ADMIN only]
DELETE /api/v1/categorizer/dictionary/learned/{id} → 204 — delete one learned entry + hot-reload  [ADMIN only]
POST   /api/v1/categorizer/brands/import      → bulk-upsert brand-registry entries, hot-reloaded   [ADMIN only]
POST   /api/v1/categorizer/brands/derive-from-catalog?minProducts=2 → grow registry from EAN-catalog brands (no external calls) [ADMIN only]
POST   /api/v1/categorizer/benchmark/import   → bulk-upsert golden-set rows for the benchmark      [ADMIN only]
POST   /api/v1/categorizer/ean-catalog/import → bulk-seed EAN→category catalog (pre-mapped rows)   [ADMIN only]
POST   /api/v1/categorizer/ean-catalog/import-off → bulk-seed from RAW Open Food Facts rows (server maps tags) [ADMIN only]
GET    /api/v1/admin/products/brand-coverage    → dry-run brand-extraction coverage over the base  [ADMIN only]
GET    /api/v1/admin/products/unmatched-report?topN=30 → item→product matching KPI + worst orphans [ADMIN only]
```

> The **model-training / catalog-mutating** endpoints (`retrain`, `auto-promote`, `promote-consensus`, `learned`, `consensus`, the four `*/import`s) require `Role.ADMIN` — a normal user gets `403`. The read/debug GETs (`classify`, `ml/predict`, `benchmark`, `quality/history`, `status`) remain open to any authenticated user.

> Since 2026-07-02 the curated dictionary, brand registry and benchmark golden set live in **DB tables** (formerly classpath CSVs) — the three new imports make them editable at runtime, no deploy needed. Bodies: curated `[{keyword, genericName, category}]`, brands `[{key, displayName}]`, benchmark `[{description, expectedCategory, expectedBrand?, expectedPackSize?, expectedPackUnit?}]`. All are upserts returning `{imported, skipped}`.

**`/promote-consensus`** — turns user category corrections into deterministic knowledge: products corrected by ≥N distinct households (consensus) get their global category set (source `CONSENSUS`), and recurring agreed tokens enter the learned dictionary. Returns `{ productsGraduated, tokensLearned, learnedTotal }`. Runs daily automatically; this is the manual trigger.

**`GET /consensus`** — returns all products the consensus job graduated: `[{ id, ean, normalizedName, genericName, brand, category }]`. Use to review what was auto-approved before deciding to keep or revert. Read-only.

**`DELETE /learned`** — wipes every auto-promoted entry from the `learned_dictionary_entry` table and resets the in-memory dictionary to just the curated tier. Returns `{ removedEntries }`. Use to restore a clean baseline if the learned dict drifts or is corrupted.

**`DELETE /consensus`** — reverts all products whose category was set by consensus promotion (source `CONSENSUS`) back to `NONE` / null category, so they re-enter the classification cascade on the next request. Returns `{ revertedProducts }`. Does **not** touch the learned dictionary — run `DELETE /learned` too if you want a full reset.

**`POST /dictionary/import`** — bulk-upserts a list of `{ token, genericName, category, sampleCount? }` into the learned dictionary and swaps the in-memory reference immediately. `sampleCount` defaults to 999 when omitted. Returns `{ imported, skipped }`. Use to pre-seed large volumes of known token→category mappings without ingesting receipts.

**`POST /ean-catalog/import`** — bulk-seeds the EAN catalog (step A2 in the canonicalization cascade). Body: `[{ ean, genericName?, brand?, category?, source }]`. `source` accepts `OPEN_FOOD_FACTS`, `CURATED_IMPORT`, or `USER_CONFIRMED`. Upserts by EAN — re-importing the same EAN updates it. Returns `{ imported, skipped }`. Designed to receive Open Food Facts Brazil dump data; entries with blank/null EAN are skipped. Category and brand from the catalog enrich newly created products but never overwrite data already extracted from the receipt description.

**`/benchmark` (quality metric)** — runs the cascade over `seed/categorization-benchmark.csv` (curated description → true category/brand/quantity) and returns per-field accuracy: `{ total, correct, accuracyPct (category), wrong, uncategorized, brandChecked, brandCorrect, brandAccuracyPct, quantityChecked, quantityCorrect, quantityAccuracyPct, mlCategoryChecked, mlCategoryCorrect, mlCategoryAccuracyPct, failures:[{description, field, expected, got, source}] }`. Brand/quantity are scored only on golden rows that declare them. `mlCategory*` is the ML model measured **alone** (shadow) — it's currently gated OUT of the live cascade (`category-apply-enabled=false`); watch `mlCategoryAccuracyPct` to decide when to re-enable. Each call records a snapshot.

**`/quality/history`** — the trend: snapshots (newest first) from benchmark runs + backfills. Each: `{ recordedAt, trigger, accuracyPct, benchmarkCorrect, benchmarkTotal, catalogProducts, catalogCategorized, catalogCoveragePct, brandAccuracyPct, quantityAccuracyPct, mlAccuracyPct, mlReady }`.

Mostly for ops. Categorization runs automatically on receipt confirm.

**`/classify` (debug)** — repeat `description` for multiple terms. Each result:
```json
{
  "input": "Batata Frita",
  "category": "PRODUCE", "genericName": "Batata", "brand": null,
  "packSize": null, "packUnit": null,
  "source": "DICTIONARY",
  "dictionary": { "genericName": "Batata", "category": "PRODUCE", "source": "DICTIONARY" },
  "mlCategory": { "label": "GROCERIES", "confidence": 0.40, "meetsThreshold": false },
  "mlGenericName": { "label": "Salgadinho", "confidence": 0.40, "meetsThreshold": false },
  "mlReady": true, "mlConfidenceThreshold": 0.75
}
```
`source` says which layer won (`DICTIONARY`/`LEARNED_DICTIONARY`/`ML`/`NONE`) — the fast way to tell a bad dictionary entry from an over-confident ML guess. `mlApplied` tells you whether the ML guess is actually used in the live cascade (currently `false` — ML is benched).

**`/ml/predict` (dev, ML-only)** — the model's raw guess per term, ignoring the dictionary and the apply gate: `[{ input, category:{label,confidence,meetsThreshold}, genericName:{...}, ready, confidenceThreshold }]`. Use it to inspect/improve the model in isolation; `/classify` is the whole chain.

---

## 12. Legal

```
GET /api/v1/legal/terms             → { "version": "1.0", "content": "...", "updatedAt": "..." }
GET /api/v1/legal/privacy-policy    → same shape
```

Both are public (no auth). Show before register; pass the returned `version` in
the register request.

---

## 13. Subscription & limits (FREE vs PRO)

Every user has a `subscriptionTier` (`FREE` default, or `PRO`), exposed on
`UserResponse` and admin user views. All paywall decisions go through one
gating service.

> **Enforcement is OFF by default** (`SUBSCRIPTION_ENFORCE=false`). Right now
> **nothing is gated — every feature is allowed for all users** (no 402s, full
> history). The table below is the behavior **once enforcement is enabled**
> (set `SUBSCRIPTION_ENFORCE=true` at monetization launch). Build the FE's
> 402-handling now so it's ready.

**What's gated when enforcement is ON (FREE limit → PRO):**

| Capability | FREE | PRO | Enforcement |
| --- | --- | --- | --- |
| Watched markets (`POST /markets/watched/{cnpj}`) | 3 pins | unlimited | New pin over the cap → **402**. Unpin / re-pin existing is always fine. |
| Receipt uploads (`POST /receipts`) | 5 / calendar month | unlimited | Over the cap → **402**. Counts ALL statuses (reject/resubmit can't game it). |
| History window (`/insights/*`, `/items`) | last 90 days | full range | `from` older than 90d (or omitted) is silently clamped. No error — just windowed. |
| Notification delivery | in-app inbox only | inbox + push/email | FREE inbox row is always saved (`delivered=false`, `failureReason=free_tier_inbox_only`); push/email skipped. |
| Basket optimization (`POST /shopping-list/optimize`) | — | ✅ | FREE → **402**. |

**402 response** (Payment Required) — distinct from 403 (forbidden). Standard
error shape; the message resolves `subscription.upgrade_required` with the
gated feature name as an argument, e.g. *"This feature (BASKET_OPTIMIZATION)
requires a PRO subscription."* Treat 402 as "show the upgrade prompt".

**Admin set-tier (ops / promos / testing):**
`PUT /api/v1/admin/users/{id}/subscription-tier` — body `{ "tier": "PRO" }`
or `{ "tier": "FREE" }`. ADMIN-only. Returns the admin user detail. PRO
activates a manual subscription; FREE cancels it.

**Billing webhook (integration seam):**
`POST /api/v1/webhooks/subscription` — public route, provider-agnostic. A real
payment provider (Stripe / Mercado Pago) maps its webhook event onto:
```json
{ "userEmail": "u@x.com", "action": "ACTIVATE",
  "provider": "stripe", "providerRef": "sub_123",
  "currentPeriodEnd": "2026-12-31T00:00:00" }
```
`action` is `ACTIVATE` (grant PRO) or `CANCEL` (drop to FREE). Verified by the
`X-Webhook-Secret` header against `economizai.billing.webhook-secret`
(empty in dev → check skipped; set + wrong/missing → **401**). FE doesn't call
this — it's server-to-server from the provider.

**RevenueCat webhook (mobile IAP — Apple StoreKit + Google Play):**
`POST /api/v1/webhooks/revenuecat` — public route, server-to-server from
RevenueCat. Authenticated by a fixed `Authorization` header configured in the
RevenueCat dashboard, checked against
`economizai.billing.revenuecat.auth-header` (**fail-closed**: config unset →
every call 401). Body is the standard RevenueCat event envelope; we read only:
```json
{ "event": { "type": "INITIAL_PURCHASE", "app_user_id": "<our user UUID or email>",
  "expiration_at_ms": 1798761600000, "product_id": "economizai_pro_monthly", "id": "evt_..." } }
```
Purchase/renewal-type events grant PRO until `expiration_at_ms`;
`EXPIRATION`/`SUBSCRIPTION_PAUSED` drop to FREE. A bare `CANCELLATION`
(auto-renew off) is a no-op — access lasts until the period ends. Always 200
for accepted calls (unknown users are a logged no-op) so RevenueCat doesn't
retry. FE never calls this.

---

## Common patterns + gotchas

- **All money** is `BigDecimal` with 2 decimals (R$ centavos). Never parse as
  float on the FE — keep as string or use a money lib.
- **All quantities** are `BigDecimal` with 3 decimals (kilos, liters, units).
- **All timestamps** are ISO-8601 without timezone (`2026-04-28T10:00:00`).
  Server timezone is America/Sao_Paulo.
- **CNPJ** strings are always 14 digits, no formatting (`93015006005182`, not
  `93.015.006/0005-182`).
- **Empty lists are not errors.** A new user with no receipts gets `total: 0`
  and empty arrays from `/insights/spend`, not 404. Render the empty state.
- **The k-anon hybrid** lets you show "poucas amostras" warnings without
  leaking sub-K-anon data. Check `kAnonBlocked` on `ReferencePrice`.
- **Volume gates** mean some endpoints return empty until enough data
  accumulates (predictions: ≥2 per product, preferences: ≥5 per generic,
  reference price: ≥5 samples + ≥3 households). This is by design.
- **Rate limiting.** `POST /auth/*` is capped at **5 req/min/IP**;
  `POST /receipts` is capped at **30 req/hour/user**. Over-quota responses
  are `429 Too Many Requests` with a `Retry-After: <seconds>` header and a
  translated message body. Successful responses on rate-limited routes
  carry `X-RateLimit-Remaining` so the FE can warn the user before they
  hit the wall. Other routes are uncapped.

---

## Postman collection

Full request library + sequential E2E flow at
`postman/economizai.postman_collection.json`. Set the `baseUrl` collection
variable to your environment, optionally set `qrPayload` to a real NFC-e for
the receipt steps.
