# Economiza AI — API guide for the FE

Practical walk-through of the backend, organized by user flow. For the
machine-readable contract, hit **`/swagger-ui`** on whichever environment
you're against (everything's annotated with descriptions and examples).

- **Production:** `https://api.economizaai.app`
- **Dev:** `https://api-dev.economizaai.app`
- **Local:** `http://localhost:8080`

> Use the custom domains above. The `*.onrender.com` subdomains are
> misleadingly named (the **dev** service's onrender URL is
> `economizai-app-prod.onrender.com`) — don't reference them.

Both environments run the same code (prod is deployed from `main`, promoted from
`development`); dev is where features land first and where test/screenshot data lives.

All `/api/v1/**` routes require a JWT in the `Authorization: Bearer <token>`
header, except the public ones: `/auth/*`, `/legal/*`, `/webhooks/*`,
`/public/report-charts/*`, `/contact`, `/beta-signup`, `/visits`, `/app-config`
and `/chains/logo/*`. `/admin/**` and `/categorizer/ai/**` need `ROLE_ADMIN`,
`/merchant/**` needs `ROLE_MERCHANT` (403 otherwise). Access tokens expire after 24h —
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

**`?light=true`** (the app's Home uses this): same shape, but `suggestedShoppingList` and
`communityPromosNearby` come back empty and the server skips computing them — those two are
the slow part (consumption predictions + nearby promo detection) and the Home doesn't render
them. Cached 30 min per household generation (any receipt change invalidates immediately);
`unreadNotificationCount` is always live. Supports `If-None-Match` → 304.

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
(`[Economiza AI beta tester] …`) so it's separable from `/contact`. **Public**,
**rate-limited to 5/hour per IP** (own bucket, separate from `/contact`). Returns `202`
with no body on success.

```
GET /api/v1/app-config   → 200 (public, no auth)
```

Remote config for the mobile **ForceUpdateGate** (replaces the legacy GitHub
gist — same JSON shape, just swap the URL). Returns
`{ "minVersion", "androidUrl"?, "iosUrl"?, "message"? }` — `minVersion` always
present (`0.0.0` = gate is a NO-OP, nothing blocked); the optional fields are
omitted when unset so the app's store-URL fallbacks apply. Served with
`Cache-Control: max-age=60`, **rate-limited to 60/min per IP**.

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
  "acceptedTermsVersion": "1.1",
  "acceptedPrivacyVersion": "1.1",
  "platform": "WEB",               // optional: WEB | ANDROID | IOS
  "attribution": {                 // optional — marketing attribution, all fields optional strings
    "utmSource": "instagram", "utmMedium": "cpc", "utmCampaign": "black-friday",
    "utmContent": "video-a", "utmTerm": "mercado barato",
    "clickId": "<fbclid>", "referrer": "https://...", "landingPath": "/promo"
  },
  "referralCode": "ABC2345"        // optional — the inviter's code (see "Referral program")
}
→ 201 {
  "token": "...", "refreshToken": "...", "user": { ... },
  "signupPromoGranted": false,
  "signupPromoValidUntil": null,
  "newAccount": true
}
```

**`newAccount`** is `true` only when THIS call created the account: `register`, and
`google` / `apple` when the social sign-in created the user. It is `false` on `login`,
`refresh` and a returning social user. Fire the signup analytics events off it.

**Launch promo — granted on the FIRST confirmed nota, not at signup (since 2026-11-07).**
While the promo is on, an account gets PRO when it confirms its first receipt (valid
until the fixed `SUBSCRIPTION_PROMO_UNTIL` date), once per user. The `signupPromoGranted` /
`signupPromoValidUntil` fields are kept for contract compatibility but are now always
`false`/`null` on `register` / `google` / `apple` / `login` / `refresh`. The confirm that
granted it says so in its own response (`launchPromo`, §3 "Review + confirm"); afterwards
read `GET /users/me/subscription` to show "grátis até DD/MM".

**`referralCode` is optional** on `/register`, `/google` and `/apple`. It is applied only
when the call creates the account, and silently ignored when it is unknown, the user's
own code, or the account already existed — it never fails the signup.

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
→ 200 { "token": "...", "refreshToken": "...", "user": { ... }, "newAccount": false }
```

E-mails are case-insensitive everywhere (register, login, reset, social): the address is
trimmed and lower-cased server-side, so `Maria@X.com` and `maria@x.com` are one account.

- **Wrong email/password** → `401 auth.invalid.credentials` (same error for unknown email or wrong password — no account-enumeration).
- **Password login on a social account** → `409 auth.social_account` (the email belongs to a Google/Apple account with no password). The response carries the provider so you can point the user at the right button:
  ```
  { "status": 409, "message": "Esta conta usa login com Google...", "errors": { "provider": "GOOGLE" } }
  ```
  Read `errors.provider` (`GOOGLE`/`APPLE`) to highlight the matching "Continue with …" button; `message` is already localized.

### Social login (Google / Apple)

The mobile app runs the **native** Google / Apple sign-in SDK, gets the provider token, and sends it here. The backend verifies the token (RS256 against the provider's JWKS; issuer/expiry checks, and audience when client IDs are configured), then **finds-or-creates** the user and returns the **same `AuthResponse`** as password login.

```
POST /api/v1/auth/google   { "idToken": "<google id_token>", "platform": "ANDROID", "referralCode": "ABC2345" }                 → 200 { token, refreshToken, user, signupPromoGranted, signupPromoValidUntil, newAccount }
POST /api/v1/auth/apple    { "identityToken": "<apple identity_token>", "name": "Maria Silva", "platform": "IOS", "referralCode": "ABC2345" } → 200 { token, refreshToken, user, signupPromoGranted, signupPromoValidUntil, newAccount }
```

`newAccount` is `true` only when this call just created the account (not on a returning
user's login); `referralCode` is optional and only applied in that case.
`signupPromoGranted`/`signupPromoValidUntil` behave exactly as in `/register` above
(always `false`/`null`).

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
POST   /api/v1/users/me/password        { "newPassword": "..." }   ← set a FIRST password (social account); 409 if one exists
POST   /api/v1/users/me/email-change          { "newEmail": "...", "currentPassword": "..." }  → 204 (see "Change e-mail")
POST   /api/v1/users/me/email-change/confirm  { "code": "123456", "refreshToken": "..."? }     → 200 UserResponse
DELETE /api/v1/users/me/email-change          → 204 (cancel the pending change)
DELETE /api/v1/users/me                 → LGPD account deletion (cascades all data; also removes the profile picture
                                          and the stored receipt photos from object storage)
GET    /api/v1/users/me/export          → LGPD data export (ALL personal data: user + accountExtras + household + receipts + notificationRules + watchedMarketCnpjs + subscription + marketAliases + customCategories + categoryOverrides + manualPurchases + shoppingLists + notifications)
PATCH  /api/v1/users/me/contribution    { "contributionOptIn": false }   ← LGPD opt-out from collaborative panel
PATCH  /api/v1/users/me/location        { "latitude": -30.0277, "longitude": -51.2287 }
GET    /api/v1/users/me/location/cep?cep=90010000  → { latitude, longitude, label }  ← resolve-only, não salva
       ← alternativa ao GPS (funciona na web, sem permissão): CEP → coordenada aproximada
         (BrasilAPI CEP v2; fallback geocoding). 404 quando o CEP não resolve. Para SALVAR,
         chame PATCH /me/location com as coords devolvidas (mesmo fluxo da prévia do GPS).
PATCH  /api/v1/users/me/push-token      { "pushDeviceToken": "<FCM>" }   ← null/empty to clear
GET    /api/v1/users/me/store-review-prompt  → { "eligible": true, "reason": "ok" }
       ← may the app show the native store-rating dialog NOW? true only on a positive moment:
         >= 2 confirmed notas, no failed nota (FAILED_PARSE/CONTINGENCY_PENDING/NEEDS_DEVICE_FETCH)
         in the last 7 days, < 3 prompts ever, none in the last 90 days.
         reason (machine key, never rendered): ok | not_enough_receipts | recent_failure |
         max_prompts_reached | cooldown
POST   /api/v1/users/me/store-review-prompt  → { "status": "ok" }
       ← call right after StoreReview.requestReview(); counts the prompt + starts the cooldown
PATCH  /api/v1/users/me/phone           { "phoneNumber": "+5551999999999" }  → 204 (E.164; sends a 6-digit OTP via SMS)
POST   /api/v1/users/me/phone/verify    { "code": "123456" }                 → 204 (400 on wrong/expired code)
GET    /api/v1/users/me/whatsapp        → { optIn, weeklyReportEnabled, maskedNumber, confirmLink, reportLink }
       ← WhatsApp opt-in (reverse flow): confirmLink / reportLink are wa.me deep-links carrying a
         per-user token ("CONFIRMAR-<token>" links the number, "RELATORIO-<token>" pulls the report
         on demand); the user sends the message and the inbound webhook does the linking.
PATCH  /api/v1/users/me/whatsapp        { "weeklyReportEnabled": true|false }  → same shape
       ← toggles the weekly report; requires a prior opt-in (linked number), error otherwise
GET    /api/v1/users/me/savings         → SavingsSummaryResponse (see §7b "Savings")
GET    /api/v1/users/me/subscription    → SubscriptionStatusResponse (see §7b "Subscription status")
GET    /api/v1/users/me/notification-preferences
PUT    /api/v1/users/me/notification-preferences
       { "preferences": [ { "type": "PROMO_PERSONAL", "channel": "PUSH" }, ... ] }
       ← PROMO_COMMUNITY and CHEAPER_MARKET are retired toggles: no longer listed by the GET and ignored
         in the PUT (their content lives in the deals digest — see digest-preferences below)

GET    /api/v1/users/me/digest-preferences   → { "frequency": "DAILY", "sendHour": 17 }
PUT    /api/v1/users/me/digest-preferences
       { "frequency": "DAILY|WEEKLY|OFF", "sendHour": 0-23 | null }
       ← frequency required; sendHour optional override (null = infer it).
         sendHour out of 0-23 → 400. frequency=OFF disables the digest entirely.

POST   /api/v1/users/me/profile-picture   ← multipart form, field name "file"
                                            JPG/PNG/WEBP, max 5MB
GET    /api/v1/users/me/profile-picture   ← returns the bytes (Content-Type matches the upload). Strong ETag +
                                             Cache-Control private/5min; send If-None-Match → 304 without bytes.
DELETE /api/v1/users/me/profile-picture
```

**Set password (social accounts)**: a Google/Apple user has no local password. `POST /users/me/password` (authenticated) creates one, enabling email+password login **in addition to** the social login (which keeps working). Returns `200 { "message": ... }`; `409 user.password.already.set` when the account already has a password (use the `PUT` instead). `UserResponse` exposes `authProvider` (`LOCAL|GOOGLE|APPLE`) and `hasPassword` so the FE can decide between "create password" and "change password" UI. After setting one, `forgot-password` also works for that account.

**Change e-mail** (accounts with a password): three calls, code-based like the other flows.

```
POST /api/v1/users/me/email-change
{ "newEmail": "maria.nova@example.com", "currentPassword": "..." }
→ 204   (a 6-digit code, valid 30 min, is e-mailed to the NEW address; the account e-mail does not change yet)

POST /api/v1/users/me/email-change/confirm
{ "code": "123456", "refreshToken": "<this device's refresh token>" }   // refreshToken optional
→ 200 UserResponse   (email = the new address, emailVerified = true, pendingEmail = null)

DELETE /api/v1/users/me/email-change   → 204   (cancels the pending change; idempotent)
```

| Response | When |
|---|---|
| 409 `user.email-change.password-required` | Social-only account (no password). Send the user to "create password" (`POST /users/me/password`) first. |
| 409 `user.email-change.in-use` | The new address already belongs to another account (also possible at confirm, if it was registered meanwhile). |
| 409 `user.email-change.same-email` | The new address is the current one. |
| 400 (invalid current password) | `currentPassword` is wrong. |
| 400 `user.email-change.code-invalid` | Wrong, expired or already-used code, or the code locked after 5 wrong guesses — request a new one. |
| 429 | More than 3 requests/hour, or more than 15 confirm attempts/hour. |

On confirm the old address gets a notice e-mail and **every other session is signed out**.
Send this device's `refreshToken` in the confirm body to keep it signed in; without it
ALL refresh tokens are revoked (the current access token still works until it expires).
While a change is pending, `UserResponse.pendingEmail` carries the new address (`null`
otherwise, including after the code expires) — use it to render "confirme o código
enviado para …" with resend/cancel actions. A new request replaces the previous code.

**Profile picture**: standard multipart upload. The response is JSON `{ "status": "ok" }` on success.
Capped at **10 uploads/hour** per user (429); `503 image.decode.busy` when too many images are being
decoded at once (retry in a few seconds).

- **On upload**: server-side downscales JPEG/PNG to a 512px max dimension before storing (saves disk + bandwidth, no FE work).
- **On GET**: returns the raw image bytes. **Never 404s** — when no picture has been uploaded, the server generates a deterministic initials avatar (PNG, 256x256, color hashed from email) so `<img>` tags always render. Inspect the **`X-Profile-Picture-Fallback: true|false`** response header to distinguish a generated avatar from a user-uploaded photo (handy for "edit photo" vs "upload photo" copy).
- **WebP**: stored as-is (no resize). All other formats (JPEG/PNG) are normalized.
- Storage is selected by `PROFILE_PICTURE_STORAGE`: `local` (disk — the default, ephemeral on Render) or `r2` (Cloudflare R2). The API contract is the same for both.

### Subscription checkout (web)

```
GET  /api/v1/subscriptions/plan            → 200 { "monthlyAmount": 9.90, "currency": "BRL", "webCheckoutAvailable": false }
POST /api/v1/subscriptions/checkout        → 200 { "checkoutUrl": "<Mercado Pago init_point>" }
                                             503 billing.not.configured (provider not credentialed yet)
                                             502 billing.checkout.failed (MP API down/refused)
POST /api/v1/webhooks/mercadopago          ← Mercado Pago only (x-signature HMAC, fail-closed)
```

`webCheckoutAvailable` flips to `true` the moment the MP credentials land in the
env — gate the subscribe button on it (no app release needed for the switch-on).

The FE opens `checkoutUrl` (browser/webview) and the user authorizes the monthly
subscription there. Entitlement flips **only via the webhook**, so an abandoned
checkout changes nothing: preapproval `authorized` → PRO until the next payment
date; each approved monthly charge (`subscription_authorized_payment`) advances
the period; `cancelled`/`paused` only stops the renewal — PRO is kept until the
paid period ends (`cancelAtPeriodEnd: true` on `GET /users/me/subscription`).
Poll `GET /users/me/subscription` after the back_url redirect to reflect the new
tier. Inert until `MP_ACCESS_TOKEN` / `MP_WEBHOOK_SECRET` are set. Apps keep using
RevenueCat (`/webhooks/revenuecat`).

### Referral program — "indique um amigo"

```
GET /api/v1/referrals/me
→ 200 {
  "code": "ABC2345",
  "shareUrl": "https://economizaai.app/?ref=ABC2345",
  "invitedCount": 4,          // accounts attached to this user's code
  "qualifiedCount": 2,        // of those, how many already qualified
  "rewardedMonths": 1,        // months of Premium earned by inviting
  "firstRewardUsed": true,    // the one-time "first friend who scans" reward is spent
  "referredBy": false,        // this account was itself invited by someone
  "canRedeemCode": true       // this account may still enter someone else's code
}

POST /api/v1/referrals/redeem
{ "code": "ABC2345" }
→ 204
```

The code is created on the first `GET` and never changes (7 characters, upper-case
letters and digits; input is trimmed and upper-cased server-side). A new account is
attached to an inviter either at signup (`referralCode` on `/register`, `/google`,
`/apple`) or later through `redeem`, which is allowed only while the account has **no
confirmed receipt** and was **never referred** (`canRedeemCode`).

| Response | When |
|---|---|
| 400 `referral.code.invalid` | Unknown code (or the code of someone this user invited). |
| 400 `referral.code.own` | The user's own code. |
| 409 `referral.redeem.already_referred` | The account already used a code. |
| 409 `referral.redeem.too_late` | The account already has a confirmed receipt. |
| 429 | More than 10 redeem attempts/hour. |

Rules (server-side, nothing for the FE to compute): a referral **qualifies** when the
invited person confirms a receipt that fed the collaborative index, while living in a
different household than the inviter. The inviter's **first** qualified referral earns
1 month of Premium (once); from then on, 1 month per invited friend who subscribes.
Months accumulate and start after the inviter's current entitlement ends (so after the
launch promo). The invited friend earns nothing. Each reward arrives as a `SYSTEM`
notification with `payload.kind = "REFERRAL_REWARD"` (`rewardKind`, `startsOn`,
`endsOn`); the end of the earned time is `rewardPremiumUntil` on
`GET /users/me/subscription`.

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

**Per-account limits on password reset:** at most **3 reset codes per hour** per account — further `forgot-password` calls still return 204 but send nothing (tell the user to check the e-mail already sent). Wrong guesses are also counted per account across codes: after **10 wrong codes within an hour** the account refuses reset-code verification (400) for **30 minutes**, even with a fresh code.

**Dev mode**: when SMTP isn't configured, the email body (incl. the code) is logged with a `[DEV-MODE]` prefix instead of being sent. The endpoints still return 204, so the flow works for FE testing — grab the code from logs.

`UserResponse` now includes `emailVerified` + `emailVerifiedAt`. You can gate features behind `emailVerified === true` if you want.

### Phone verification (for SMS / WhatsApp notifications)

```
PATCH /api/v1/users/me/phone         { "phoneNumber": "+5551999999999" }  → 204
POST  /api/v1/users/me/phone/verify  { "code": "123456" }                 → 204
```

`phoneNumber` must be E.164 (leading `+`, country code, 8–15 digits) — otherwise `400`. Setting the phone stores it as **unverified** and sends a 6-digit OTP (10-minute TTL) over SMS. Submit that code to `…/phone/verify`; a correct, unexpired code marks the phone verified (`204`). Wrong, expired, or missing code → `400`.

A verified phone is required for the **SMS** and **WHATSAPP** notification channels to actually deliver. **Dev mode**: when Twilio isn't configured, the OTP is logged with a `[DEV-MODE] phone OTP for …` prefix in the server logs instead of being texted — the endpoint still returns 204, so grab the code from the logs.

**WhatsApp server-to-server routes (FE never calls these):**
`POST /api/v1/webhooks/twilio/whatsapp-inbound` — Twilio's inbound-message webhook
(form-urlencoded, checked via the `X-Twilio-Signature` header); it handles the
`CONFIRMAR-<token>` / `RELATORIO-<token>` messages behind `GET /users/me/whatsapp`.
`GET /api/v1/public/report-charts/{token}.png` — the last-7-days spend-by-category pie
(PNG) Twilio fetches as the report's media; public but gated by a short-lived HMAC
token (404 on an invalid/expired token).

---

## 2. Households

Every user already has a household (auto-created at register; they are its
**host**). Several users share one household = one shared history (receipts,
dashboard, lists, predictions, custom categories, friendly names).

```
GET  /api/v1/households/me                 → { id, name, hostId, inviteCode, inviteCodeExpiresAt,
                                               members[{id,name,email,joinedAt}], createdAt, canLeave }
PATCH /api/v1/households/me                { "name": "Casa da Ana" }   ← any member; blank clears (app shows a default)
GET  /api/v1/households/invites/{code}     → { householdName, hostFirstName, memberCount, memberFirstNames[] } — preview BEFORE joining
POST /api/v1/households/join               { "inviteCode": "ABC123", "bringData": true }   ← case-insensitive
POST /api/v1/households/leave              → see "Leaving" below
POST /api/v1/households/me/invite-code/regenerate   → HOST ONLY (403); rotates the code, extends TTL another 48h
DELETE /api/v1/households/me/members/{memberId}     → HOST ONLY (403); the member moves out as if they left;
                                                      400 household.remove.self when memberId is the caller (use "leave")
GET  /api/v1/households/me/members/{memberId}/profile-picture → that member's photo (same headers as /users/me/profile-picture); 404 if not in my household
```

**Roles.** The **host** (`hostId`) is whoever created the household, or the guest
promoted after the host left. Only the host invites (rotates the code) and removes
members (`403 household.not.host`). **`inviteCode`/`inviteCodeExpiresAt` come back
only for the host** (null for guests) — show the share/invite UI only when they're
present. Every user's **home** (their own household) is stored on the account while
they are a guest elsewhere; it is never inferred from receipts.

**Invite link.** The app shares `https://economizaai.app/convite/<code>` (WhatsApp
etc.). Opening it shows the preview (`GET /invites/{code}`) and the
"bring my data?" choice, then calls `/join`. Copy to show:
- to the host when sharing: guests may take a **copy** of what is created from the
  moment they join, if they ever leave;
- to a guest who picks `bringData: true`: if the host ever leaves, the host may take
  a **copy** of what the guest brought.

**Join.** `bringData` (default `true`) moves the joiner's receipts, lists, manual
purchases, snoozes, custom categories, overrides, friendly names and brand
preferences into the shared household. On a collision (same NFC-e already there)
the household's row wins and the joiner's stays parked at home. `bringData: false`
keeps everything at home, untouched. Joining while still sharing a household with
others → **`409 household.join.leave.first`**.

**Leaving** (also what happens to a removed member):
- **Guest** (incl. a promoted host): returns to their home, gets back what they
  brought, and takes a **copy** of everything created in the shared household since
  they joined — including what others scanned. Starts empty if their home was empty.
- **Original host**: starts a **new** household with a **copy** of everything,
  including what guests brought. The guest who joined first becomes host.
- **Last member** (a guest left alone): the household's data **moves** to their
  home (one copy per NFC-e) and the household is deleted.
- **Alone in your own home**: nothing to leave → `400 household.leave.solo`.
  `canLeave=false` in `GET /me` tells the app to hide the button.

Nobody loses data: whatever is taken is a copy, the household keeps its originals.
Copies never feed the price index again (no double counting). `members[].joinedAt`
is null for the original host.

**Member cap.** `/join` answers `402` (`household.full.free`) when the household
has the FREE maximum (2) and nobody in it is PRO — Premium lifts it to 6. The
absolute cap (6, `household.full.max`) is a plain `400`. With
`SUBSCRIPTION_ENFORCE=false` (current prod) only the absolute cap applies.

**Notifications.** Every other member gets `HOUSEHOLD_MEMBER_JOINED` /
`HOUSEHOLD_MEMBER_LEFT` (destination `HOUSEHOLD`) when someone joins, leaves or
is removed.

**Invite codes expire after 48h.** When that happens, `/join` and the preview
return 400 with the same message as an unknown code (we don't tell the requester
whether the code was wrong vs. stale). Preview shares the join rate limit (10/h),
so it can't be used to probe codes. While a user is away as a guest, their home's
code is expired so nobody joins it in their absence.

**Receipts carry `scannedBy: { id, name }`** (list, detail and the Home
`recentReceipts`) so the app can show "por Ana" on a shared history.

### Data-share consents (household split)

> **Not used by the leave flow (2026-11-10):** leaving already copies the shared
> data with no approval step (the permission is given in the invite copy above).
> These endpoints remain for compatibility and always return an empty inbox.

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
  "qrPayload": "<whatever the camera scanned>",
  "entryMethod": "QR_SCAN"   // OPTIONAL — see below
}
→ 201 ReceiptResponse with status="PROCESSING"   (async — poll GET /receipts/{id} until
                                                  PENDING_CONFIRMATION, FAILED_PARSE or one of the
                                                  waiting states: NEEDS_DEVICE_FETCH, CONTINGENCY_PENDING,
                                                  PORTAL_PENDING)
```

**Optional `entryMethod`** (analytics only, doesn't change behavior): tells us HOW the
user entered the nota — `QR_SCAN`, `MANUAL_CHAVE` (typed the key), or `PHOTO_CHAVE`
(photographed the printed key). If omitted, the server derives it from the payload
(URL → `QR_SCAN`, bare chave → `MANUAL_CHAVE`) — but then a typed key and a
photographed-and-OCR'd key are indistinguishable, so **send it** to split those two.

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

**Which states work** — `GET /api/v1/sefaz/supported-states` →
`{ "verified": ["RS","SC","SP","PR","MG","GO","MS"], "experimental": [...] }`.
`verified` = dedicated adapter + real fixture (safe to advertise); `experimental` =
best-effort catch-all we attempt-and-capture but haven't proven (beta). Use it to show
"Funciona em: …" on the scan screen. A scan from an unverified state is still attempted
(so we capture the nota to add support) up to an evidence cap per UF; past that it returns
a localized 400 (`receipt.state.unsupported`). Authenticated, not admin.

**Resubmitting a chave that is still in flight.** If the household already has this
chave in `PROCESSING` or `IMPORT_QUEUED` (double tap, or the app re-sent while the first
ingest is running), `POST /receipts` returns **that same receipt** (same `id`, still 201)
and starts nothing new — just keep polling it. A `CONFIRMED` duplicate is still 409.

**How each state is doing right now** — for the in-app FAQ ("situação dos estados"):

```
GET /api/v1/sefaz/state-status
→ 200 [
  { "uf": "AC", "name": "Acre",              "status": "VALIDATING" },
  { "uf": "RS", "name": "Rio Grande do Sul", "status": "WORKING" },
  { "uf": "SE", "name": "Sergipe",           "status": "UNSTABLE" },
  ...
]
```

All 27 UFs, ordered by `uf`. `WORKING` = verified adapter and healthy; `UNSTABLE` =
verified but its portal is on the paused watch list or its recent notas are mostly
failing for portal reasons; `VALIDATING` = experimental (still collecting notas — scans
are attempted, results not guaranteed). Authenticated, not admin; the answer is the same
for every user and cached server-side for ~5 minutes. Never hardcode the state list in
the app — render it from here.

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

### Bulk import receipts from access keys (chaves) — onboarding

Fill a new user's history from the **chaves** they export from a state program
(e.g. the Nota Fiscal Gaúcha portal), without scanning each nota. Each chave
(NFC-e 65 / NF-e 55, any UF) rides the by-chave fetch chain and is ingested
through the normal pipeline.

> **ADMIN-only for now.** `/receipts/import`, `/receipts/import/**`,
> `POST /receipts/confirm-batch` and `POST /receipts/delete-batch` require
> `ROLE_ADMIN` (operator tool, not exposed to end users yet) — a normal user gets
> 403. `POST /receipts/xml` and `POST /receipts/retry` are open to any
> authenticated user.

```
POST /api/v1/receipts/import
{ "chaves": ["43260593015006005182651200000076311055456577", ...] }   (max 500)
→ 202 ReceiptImportResponse

POST /api/v1/receipts/import/nfg-csv   (multipart/form-data, field "file")
  file = a state-program export — CSV/TXT, Excel (xlsx/xls) or PDF; format is
  sniffed by content, chaves extracted server-side (route name kept for compat)
→ 202 ReceiptImportResponse

POST /api/v1/receipts/import/extract-chaves   (multipart/form-data, field "file")
  same formats, but EXTRACTION ONLY — nothing is imported; use it to preview
  the detected chaves and then confirm via POST /receipts/import
→ 200 { "chaves": ["4326...", ...] }
→ 400 receipt.import.file.unreadable when the file can't be parsed
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
- Rejection `reason` keys: `receipt.import.invalid_chave`, `receipt.chave.typo`,
  `receipt.manual-chave.unsupported` / `receipt.manual-chave.nfe-unsupported` (no fetch path at
  all for that chave — same rule as a typed chave), `receipt.import.duplicate`,
  `receipt.import.merchant_unsupported`, `receipt.import.cap_reached`. `reasonMessage` is already localized.
- **Every UF** (2026-11-10): each chave rides the by-chave chain (free legacy reconsult →
  Infosimples → admin alert). `receipt.import.unsupported` no longer exists.
- **Segment policy (same for scan and import).** Any merchant is accepted into the user's personal
  history — restaurants, pet, apparel, e-commerce, etc. Only an explicit **admin BLOCKED override**
  rejects. What's gated is **index contribution**: only grocery/pharmacy (supermarket, pharmacy,
  food-retail) feed the collaborative price index and our algorithms/metrics; everything else is
  "GREY" (kept in personal history, out of the shared index).

### Upload e-commerce receipts by XML — Amazon / Mercado Livre / Shopee

```
POST /api/v1/receipts/xml   (multipart/form-data, field "files" — accepts several)
```
- **User-facing** (any authenticated user; NOT admin). For marketplace/e-commerce NF-e that
  have **no QR to scan** — the user downloads the nota's XML from the order page and uploads it.
- Send one or more `.xml` parts under **`files`**. Response `202` is the same
  **ReceiptImportResponse** as bulk import (`received`, `queued`, `queuedReceiptIds`,
  `rejected`, `rejectedChaves[{chave, reason, reasonMessage}]`).
- **Self-contained:** the chave and every line item are read from the XML itself — **no SEFAZ
  fetch** — so it works for **any UF** and never depends on a portal or paid lookup. CPF is stripped
  before persist.
- Same dedup + monthly cap + per-item rejection semantics as bulk import; nothing is dropped
  silently. Reason keys reuse `receipt.import.*` (`invalid_chave`, `duplicate`, `cap_reached`).

**Persistent staging.** Imported notas carry `origin: "IMPORT"` and stay in a staging
list until the user acts on them — the import screen rehydrates from the server, so
nothing is lost on refresh/navigation.

```
GET  /api/v1/receipts/import/staging
→ 200 ReceiptResponse[]   // every non-confirmed IMPORT nota for the household, newest first

POST /api/v1/receipts/confirm-batch
{ "ids": ["<uuid>", ...] }
→ 200 { "affected": <int> }   // confirm (definitive save) several at once

POST /api/v1/receipts/delete-batch
{ "ids": ["<uuid>", ...] }
→ 200 { "affected": <int> }   // delete several of the household's notas at once

POST /api/v1/receipts/retry
{ "ids": ["<uuid>", ...] }
→ 202 { "affected": <int> }   // re-queue failed IMPORT notas ("tentar de novo" on the import screen)
```

- Staging returns everything except `CONFIRMED` (which has been saved definitively and
  drops off): `IMPORT_QUEUED` / `PROCESSING` / `PENDING_CONFIRMATION` / `FAILED_PARSE` /
  `NEEDS_DEVICE_FETCH` / `CONTINGENCY_PENDING` / `PORTAL_PENDING`. Confirm a nota (`POST /receipts/{id}/confirm` or `confirm-batch`)
  or delete it (`DELETE /receipts/{id}` / `POST /receipts/delete-batch`) to remove it.
- **`PORTAL_PENDING`** (new): the state's SEFAZ portal was offline (código 615) when the nota was
  scanned, so no path could reach it — but the nota is fine, the portal is just down (minutes to
  weeks). NOT a failure: the server re-fetches on a schedule (`PortalRecoverySweeper`) until the portal
  returns (→ `PENDING_CONFIRMATION` + a "nota pronta" push), giving up to `FAILED_PARSE` only after a
  long window (~14d). `parseErrorMessage` carries the honest "portal do seu estado está fora" text.
  The app should render it as a transparent "aguardando" (not "falhou") and may offer the user to
  photograph the nota instead of waiting. Reason key: `receipt.portal.unavailable`.

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
| 503 `image.decode.busy` | Too many images are being decoded at once (server-wide limit). Retry in a few seconds. Same on `/receipts/chave/photo`. |

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

Note the bare-chave rule still applies at submit time: a bare chave is rejected
(`receipt.manual-chave.unsupported`) only when there is no fetch path at all for
it — i.e. the state's adapter needs the QR's signature params, the chave isn't
covered by the free legacy reconsult, AND the paid by-chave fallback
(Infosimples) is off. No state is hard-blocked (RS included).

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
| — `receipt.contingency.pending` | Via polling (`status=CONTINGENCY_PENDING`, not a failure): the note was issued in contingency (printed "EMITIDA EM CONTINGÊNCIA") and SEFAZ doesn't have it yet. The server re-fetches automatically (up to ~72h) — no rescan needed; it only turns `FAILED_PARSE` if the store never transmits it. |
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

**Server overload (any endpoint).** When the server has no free database connection
it answers fast with **`503 error.overloaded`** and a **`Retry-After: 5`** header
instead of hanging or returning a 500. This also applies while the JWT is being
checked, so an overloaded server never answers 401 for a valid token. Treat it as
"try again in a few seconds" — never as a signed-out session.

**Cost guard on paid fetches.** Some notas need a paid step (captcha solve, the
Infosimples by-chave lookup, AI extraction). Each paid service has a per-user daily
cap and a daily spend ceiling; free scraping is never blocked by them.

| Paid service | Per-user cap / day | Daily ceiling (all users) |
|---|---|---|
| Infosimples (by-chave lookup) | 10 | R$ 120 |
| Captcha solve | 30 | R$ 30 |
| Receipt by photo (vision, counted per photo) | 10 | R$ 30 |
| HTML parse rescue (AI) | 5 | R$ 15 |
| AI categorization (suggestions, post-confirm fallback, "corrigir item") | 60 | R$ 50 |
| SMS / WhatsApp (Twilio) | 3 | R$ 15 |

The values are server config (env vars — see `INFRASTRUCTURE.md`); ceilings reset at
midnight UTC (21:00 Brasília). What the app sees:

| Response | When |
|---|---|
| `receipt.paid_api.quota_exceeded` | The user hit their daily cap. Via polling on a scan (`FAILED_PARSE`); **429** on synchronous calls. |
| `receipt.paid_api.budget_exhausted` | The service's daily ceiling is spent. Via polling on a scan; **503** on synchronous calls. Only that service stops. |
| `receipt.paid_api.circuit_open` | The provider is failing and paused for a few minutes (**503** / via polling). |
| 429 `phone.otp.quota_exceeded` | SMS cap reached on the phone-OTP flow. |
| 429 `ai.correction.limit_reached` | AI-categorization cap reached on "corrigir item". |

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
PUT    /api/v1/receipts/{id}/items/{itemId}/category  → correct the category { "category": "MEAT_DAIRY" } OR { "customCategoryId": "<uuid>" }
POST   /api/v1/receipts/{id}/items                   → add a missing item (PENDING_CONFIRMATION only)
PATCH  /api/v1/receipts/{id}/market { "marketName": "...", "cnpj": "14 dígitos|null" }
                                                     → edita o mercado de uma nota de FOTO (dado não
                                                       verificado, livre pra corrigir). Corrigir o CNPJ
                                                       reencaixa a nota na lente certa. 400 se não for
                                                       nota de foto / não estiver em revisão / CNPJ inválido.
PATCH  /api/v1/receipts/{id}/issued-at { "issuedAt": "2026-11-08T14:30:00" }
                                                     → data da compra de uma nota de FOTO em revisão (hora
                                                       local do Brasil). 400 se não for foto / não estiver
                                                       em revisão / data no futuro ou antes de 2000.
                                                       Nota de FOTO em revisão: `missingFields` no
                                                       ReceiptResponse lista o que falta (ISSUED_AT, MARKET,
                                                       ITEM_NAMES, ITEM_PRICES); confirmar com algo faltando
                                                       → 400 localizado. Não é confirmada automaticamente.
POST   /api/v1/receipts/items-photo                  → multipart (1+ fotos) do cupom impresso. ASSÍNCRONO:
                                                        retorna 201 com a nota em PROCESSING na hora; a visão
                                                        roda em background e a nota vira PENDING_CONFIRMATION
                                                        (ou FAILED_PARSE). Fotos sobrepostas são costuradas
                                                        (dedup na junção). origin=PHOTO — só histórico pessoal,
                                                        nunca o índice; conta no cap mensal FREE; cap diário
                                                        por usuário; 503 quando a camada de IA está off.
POST   /api/v1/receipts/{id}/retry                   → "tentar novamente" de nota FALHADA, sem re-escanear:
                                                        SCAN re-roda a ingestão com o qrPayload guardado
                                                        (aproveita fallbacks novos, ex. resgate RS 227);
                                                        IMPORT re-enfileira a reconsulta por chave. 202;
                                                        poll GET /receipts/{id}. Aceita X-Device-Fetch.
                                                        400 localizado quando a falha é definitiva (ver
                                                        "Retry refused" abaixo).
POST   /api/v1/receipts/{id}/device-content { "rawContent": "<HTML/XML>", "unavailable": false }
                                                     → 200 ReceiptResponse. Nota deixada em NEEDS_DEVICE_FETCH
                                                        (portal do estado bloqueia o IP do servidor): o app busca
                                                        a nota no aparelho e reposta o conteúdo bruto; o servidor
                                                        re-ingere a MESMA nota. Mesmo rate limit do submit.
POST   /api/v1/receipts/{id}/items/{itemId}/correction { "hint": "o que o produto realmente é" }
                                                     → 200 ReceiptResponse. Correção de item mal classificado:
                                                        a IA reclassifica com a dica e aplica o resultado SÓ
                                                        PARA A CASA (nome amigável + categoria); o produto
                                                        global não muda — a proposta vai pra revisão do admin
                                                        (findings PRODUCT_CORRECTION + MISSING_RULE).
                                                        429 ai.correction.limit_reached (limite diário);
                                                        503 item.correction.unavailable (IA fora — tentar depois);
                                                        422 item.correction.failed (não identificou — pedir
                                                        outra descrição).
POST   /api/v1/receipts/{id}/confirm                 → commit. Optional body { excludedItemIds: [uuid, ...] }
                                                        Returns { receipt, personalPromos: [...], launchPromo }
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

**Per-item category correction** — `PUT /receipts/{id}/items/{itemId}/category` with EXACTLY ONE target: a global enum `{ "category": "MEAT_DAIRY" }`, a household custom category `{ "customCategoryId": "<uuid>" }` (create one first via `POST /categories`, or reuse one from `GET /categories`), **or** a fine taxonomy leaf `{ "categoryNodeSlug": "queijos" }` (slug from `GET /categories/taxonomy`). This lets the FE assign a category inline at confirm/edit without leaving the receipt. This is **household-scoped "evidence, not truth"**: it changes the category *this household* sees for that product everywhere (`GET /receipts/{id}`, `GET /items`) but does **not** mutate the global product, so other households are unaffected. `400` if not exactly one target is given or the item isn't linked to a canonical product yet. Returns the updated receipt with the override applied. (Aggregates/insights honor this override: `/insights/spend` `byCategory` is always override-aware; `/items` and `/insights/query` take `categoryView` — pass `categoryView=GLOBAL` to ignore overrides and use the global category.)

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

**Item-level category (new)** — each `ReceiptResponse.items[*]` now carries `category: string | null`. Resolved from the linked `Product` (when the item has been canonicalized). Values: `GROCERIES`, `BEVERAGES`, `PRODUCE`, `MEAT_DAIRY`, `BAKERY`, `CLEANING`, `PERSONAL_CARE`, `HEALTH`, `PET_SUPPLIES`, `OTHER`. `null` when the item hasn't been linked to a Product yet, or the Product has no category set. Use it to show a category chip per line on the ReviewScreen / receipt detail without a follow-up `GET /products/{id}` call.

Confirm is what triggers downstream side effects:
- Item canonicalization (raw text → canonical Product)
- Anonymized contribution to the collaborative price index (skipped if `contributionOptIn=false`)
- Personal promo detection (returned in the response)
- Geocoding of new markets (async, doesn't block)

`personalPromos` in the confirm response is the list of items the user paid
notably less for than usual — surface as "Você economizou" cards.

**`launchPromo`** in the confirm response is non-null only on the ONE confirmation
that granted the launch promo (the user's first confirmed receipt, while the promo
is on):

```json
{ "receipt": { ... }, "personalPromos": [ ... ], "launchPromo": { "validUntil": "2026-12-31" } }
```

`validUntil` is the last day of free Premium (a date, Brasília calendar). Show the
"você ganhou Premium grátis até DD/MM" notice when it is present; it is `null` on
every other confirm (not the first receipt, promo over/disabled, already granted, or
the user already has a better entitlement).

**A line the user edited or added does not feed the collaborative index.** Changing an
item's price data (or adding an item) keeps it in the household's own history, but
that line is skipped when the receipt contributes prices; untouched lines of the same
receipt still contribute.

**Retry refused (`POST /receipts/{id}/retry` → 400).** Some failures are a final
verdict about the nota itself — retrying would only fail the same way — so the retry
is refused with a localized message telling the user what to do instead. This happens
when the receipt's `parseErrorReason` is (exactly, or as a `key:` prefix) one of:

| `parseErrorReason` | Meaning |
|---|---|
| `receipt.parse.failed:chave-corrupted`, `receipt.chave.typo` | The 44 digits were read wrong — rescan the QR or retype. |
| `receipt.parse.failed:infosimples.not-found` | The portal has no such nota (cancelled / never transmitted). |
| `receipt.contingency.unavailable` | The store never transmitted the contingency nota. |
| `receipt.merchant.unsupported` | Not a grocery/pharmacy merchant. |
| `receipt.qr.invalid` | The payload is not an NFC-e QR code. |
| `receipt.state.unsupported` | No way to read this state yet. |
| `receipt.manual-chave.unsupported`, `receipt.manual-chave.nfe-unsupported` | The state can't be consulted from a typed chave — scan the QR. |

Hide the "tentar de novo" button for these keys. Every other failure stays retryable.
The batch `POST /receipts/retry` silently skips the same notas.

### List + filter

```
GET /api/v1/receipts
    ?from=2026-04-01T00:00:00
    &to=2026-04-30T23:59:59
    &marketCnpj=83261420003255
    &category=GROCERIES               ← multi-value: repeat for OR (?category=GROCERIES&category=CLEANING)
    &status=CONFIRMED                  ← optional ReceiptStatus: IMPORT_QUEUED|PROCESSING|NEEDS_DEVICE_FETCH|PENDING_CONFIRMATION|CONFIRMED|REJECTED|FAILED_PARSE|CONTINGENCY_PENDING|PORTAL_PENDING (invalid → 400; absent → all EXCEPT FAILED_PARSE)
    &q=leite condensado
    &categorySlug=queijos             ← taxonomy slug (category or subcategory); preferred over `category`
    &scope=MARKET                     ← segment lens (ALL default | MARKET | PHARMACY | FUEL | OTHERS; legacy SUPPORTED | OTHER)
    &page=0&size=20
→ Page<ReceiptSummaryResponse>  (each row has marketName, issuedAt, totalAmount, householdTotalAmount, discountTotal, approxTaxTotal, itemCount, status)
```

Default sort is `issuedAt DESC`. All filters optional. **Failed scans:** the default list hides `FAILED_PARSE`; `status=FAILED_PARSE` lists them (the app's "Com falha" filter — rows carry `parseErrorReason` + localized `parseErrorMessage`; they have no `issuedAt`, so sort with `sort=createdAt,desc`). `size=1` + `totalElements` = the failed count. `category` accepts one or many (same as `/items` and `/insights/query`); **prefer `categorySlug=<slug da taxonomia>`** (categoria OU subcategoria, inclui descendentes) — o param `category` (enum legado) está deprecado. `categorySlug=outros` equivale ao enum `OTHER` (inclui itens sem produto).

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
  "byCategory": [
    { "category": null, "slug": "leite", "label": "Leite", "parentSlug": null, "icon": null, "color": null, "total": 120.00, "itemCount": 8 },
    { "category": null, "slug": "custom:9f...", "label": "Ração premium", "parentSlug": "pet", "icon": "paw-outline", "color": "#F5A623", "total": 60.00, "itemCount": 2 }, ...
  ]
}
```
- **`byCategory` é OVERRIDE-AWARE** (desde 2026-10-14): agrupa pela categoria **efetiva do
  household** (correção de folha/enum/custom reflete na hora), não mais pelo `category_id`
  global. Cada bucket traz `slug` (folha da taxonomia; `custom:<id>` p/ categoria custom)
  e `label` (nome fino localizado). `category` (enum) vem **null** p/ buckets de nó/custom.
  `parentSlug`/`icon`/`color` são preenchidos **só p/ custom** (a custom aninha no grupo
  `parentSlug` com o visual escolhido); nós da taxonomia deixam null — o FE deriva o grupo
  pelo `slug`.
- **Spend totals are gross** (sum of item prices). The receipt-level discount is reported **alongside**, not subtracted: `totalDiscount` for the period, and `discount` per `byMonth`/`byWeek`/`byMarket` bucket. **Net spend = `total − discount`** — compute on the FE. `byCategory` has **no** `discount` (a receipt-level discount can't be split across categories); use `totalDiscount` there.
- **Ticket médio** is `total / receipts.count` — compute on the FE.
- **Empty state** = `total: 0`, `totalDiscount: 0` + all arrays empty. No errors.

```
GET /api/v1/insights/markets/top?limit=5
GET /api/v1/insights/markets/top-discounts?from=&to=&limit=5
GET /api/v1/insights/products/{productId}/price-history?from=&to=
```

> **Removido (2026-10-14):** `GET /insights/categories/top` — era órfão (nenhum consumidor).
> Use `byCategory` do `/insights/spend` (override-aware, com `slug`/`label`/`parentSlug`) ou o
> lens `CATEGORY` do `/insights/query`.

`markets/top-discounts` ranks the household's markets by **discount received** (biggest first), excluding markets that gave none. Each row: `{ cnpj, marketName, marketFriendlyName, grossTotal, discount, discountRate, receiptCount }` — `discount` in R$, `discountRate` = `discount / grossTotal` (0–1 fraction, format as %). Personal to your household, so it works even without community volume.

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
    &category=GROCERIES               ← repeat for OR: ?category=GROCERIES&category=BEVERAGES (enum legado)
    &categorySlug=carnes              ← taxonomy node (category OR subcategory); a category slug also matches its subcategories. NÃO mande slug em `category`
    &productId=<uuid>                 ← list-typed
    &ean=7891234567890                ← list-typed
    &minReceiptTotal=100.00           ← receipt-total range
    &maxReceiptTotal=500.00
    &groupBy=WEEK                     ← see list below
    &limit=100                        ← bucket cap (default 100, max 500)
    &categoryView=HOUSEHOLD           ← category lens (default HOUSEHOLD; only affects groupBy=CATEGORY)
    &scope=MARKET                     ← segment lens (ALL | MARKET | PHARMACY | FUEL | OTHERS; legacy SUPPORTED | OTHER)
```

**Segment lens (`scope`)** — filters by the emitter's segment, same as `/dashboard`, `/insights/spend`, `/receipts` and `/items`: `ALL` (everything, default), `MARKET` (supermarket + food retail, pharmacy EXCLUDED), `PHARMACY` (pharmacies only), `FUEL` (gas stations only), `OTHERS` (everything that isn't market/pharmacy/fuel, incl. unregistered merchants). `SUPPORTED` (grocery+pharmacy) and `OTHER` are the legacy pair — still accepted, no client breaks. An inclusion lens with no matching CNPJs returns empty (e.g. `FUEL` with no gas-station notas). Applies to the summary AND buckets so a scoped view is internally consistent.

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

The legacy `/insights/spend`, `/markets/top`, `/products/{id}/price-history` endpoints stay around for the existing dashboards. Use `/insights/query` for anything custom.

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
    &categorySlug=carnes              ← taxonomy node (category OR subcategory); a category slug also matches its subcategories
    &customCategoryId=<uuid>          ← items the household moved into a custom category
    &productId=<uuid>                 ← list-typed
    &ean=7891234567890                ← list-typed
    &minReceiptTotal=100.00           ← receipt-total range
    &maxReceiptTotal=500.00
    &categoryView=HOUSEHOLD           ← category lens (default HOUSEHOLD; or GLOBAL)
    &scope=MARKET                     ← segment lens (ALL | MARKET | PHARMACY | FUEL | OTHERS; legacy SUPPORTED | OTHER)
    &page=0&size=20                   ← standard Spring pagination
```

**Segment lens (`scope`)** — same segment filter as `/insights/query` and the dashboard: `ALL` (default), `MARKET`, `PHARMACY`, `FUEL`, `OTHERS` (legacy: `SUPPORTED`, `OTHER`). Pair it with `/insights/query?scope=…` for a matching header total.

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
GET    /api/v1/categories/taxonomy       → árvore 2 níveis (categoria→subcategoria), localizada; ordenada categoria→suas subcategorias
POST   /api/v1/categories                { "name":"Frutas", "icon"?, "color"?, "parentGroupSlug"?, "parentCustomCategoryId"? } → 201
PATCH  /api/v1/categories/{id}           { "name"?, "icon"?, "color"?, "parentGroupSlug"?, "parentCustomCategoryId"? } → 200 (edita por id)
DELETE /api/v1/categories/{id}           → 204, removes a custom category (its overrides revert)
POST   /api/v1/categories/migrate        { "productIds":[...], "targetCategory":..., "targetCustomCategoryId":... }
```

`GET /categories/taxonomy` devolve nós `{ slug, name, parentSlug, hasSubcategories }`
(`hasSubcategories` = tem subcategorias; era `group` antes do rename), já ordenados
**categoria → suas subcategorias** (Outros/Não-mercado por último).

`POST /categories` aceita, além do `name`, opcionais **`icon`** (nome Ionicons, ex.
`"paw-outline"`), **`color`** (hex) e UM pai (no máximo um): **`parentGroupSlug`** (slug de
uma **CATEGORIA** embutida da taxonomia) **ou** **`parentCustomCategoryId`** (id de uma
categoria **custom** do household) — a custom vira uma **subcategoria** dentro do pai,
aninhada no gráfico. Sem pai = categoria principal. `parentGroupSlug` precisa ser uma
categoria embutida (não subcategoria), senão `400`.

> **Mudou (2026-10-15):** o `POST` **não é mais idempotente por nome** — criar com um nome
> que já existe (entre as custom do household **ou** uma categoria/subcategoria embutida,
> case-insensitive) agora retorna **`409` `customcategory.name.duplicate`**. Para **editar**
> ícone/cor/pai/nome use o novo **`PATCH /categories/{id}`** (campos todos opcionais; um
> campo null fica inalterado; o pai só é (re)definido quando um campo de pai vem no corpo).
> **Nesting travado em 2 níveis:** o pai (embutido ou custom) tem de ser de **topo** e a
> categoria editada não pode ter subcategorias próprias; violar (profundidade >2, ciclo,
> auto-pai) → **`400` `customcategory.nesting.invalid`**.

`GET /categories` returns the 9 global enums **and** the household's custom ones. Buckets
custom carregam o visual + o grupo-pai (`icon`/`color`/`parentSlug`/`parentName`); nos enums
esses campos vêm null:
```json
[
  { "id": null, "name": "GROCERIES", "custom": false, "icon": null, "color": null, "parentSlug": null, "parentName": null },
  { "id": "uuid", "name": "Ração premium", "custom": true, "icon": "paw-outline", "color": "#F5A623", "parentSlug": "pet", "parentName": "Pet" }
]
```

> **`parentSlug`/`parentName` são só para aninhar na exibição** (mostrar a custom dentro do
> grupo-pai). O gasto de uma custom **não** é somado também no pai — no `/insights/spend` a
> custom vem como bucket próprio com `parentSlug`; **não some pai + filhas** ou conta dobrado.

`GET /categories/taxonomy` retorna a árvore 2 níveis já **ordenada grupo → suas folhas**
em ordem lógica (Hortifrúti → … → **Outros por último**; "Não-mercado" por último). Cada nó:
`{ slug, name, parentSlug, hasSubcategories }` (`hasSubcategories=true` p/ grupo). Use os `slug`
de folha em `categoryNodeSlug` (correção de item, regra de dicionário, aprovar achado).

`POST /categories/migrate` moves the selected products into the target. Provide **exactly one** of `targetCategory` (a global enum) or `targetCustomCategoryId` (a custom-category uuid) — `400 customcategory.migration.invalid` otherwise.
```json
{ "productIds": ["uuid","uuid"], "targetCategory": null, "targetCustomCategoryId": "uuid" }
→ 200 { "migrated": 2, "skipped": 0 }
```

**Migration screen flow:** list a category's items with `GET /items?category=GROCERIES`, check the ones to move (with a select-all toggle), then `POST /categories/migrate` with those `productIds` and the target. View a custom category's contents with `GET /items?customCategoryId=<uuid>`. After migration each moved item's `category` reads back as the custom-category **name** (a display string, no longer always an enum); the item's `globalCategory` keeps the underlying enum. In the default **HOUSEHOLD** category lens, `/items?category=<enum>` and the `CATEGORY` insights paths count the moved product under its new (custom/corrected) category and drop it from the old enum — no double-counting. Pass `categoryView=GLOBAL` to fall back to the global category everywhere.

---

## 5. Products

```
GET   /api/v1/products?query=arroz&page=0&size=20    → product picker search by name or exact EAN (autocomplete; see "Search behaviour")
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

**Search behaviour of `GET /products` (2026-11-11)** — it runs per keystroke, so it is bounded on purpose:

| `query` | What comes back |
|---|---|
| absent / blank | The **household's own products**, most purchased first, paged in the database (`page`/`size`; a client `sort` is ignored). |
| 1 character | Only the household's own products matching it — the catalog is not searched. |
| 2+ characters | Catalog matches by name / exact EAN and by receipt description, **capped at ~200 candidates per source**, plus everything the household bought or renamed that matches (never cut by the cap). Ranked by local relevance and paged over that capped set, so `totalElements` tops out around the cap — it is not the catalog size. |

`hasPriceHistory` on each row is `true` when the **caller's household** has a confirmed, non-excluded purchase of the product (it has its own price history to show).

`GET /products` with 2+ characters is still the **global** catalog (for autocomplete when creating alerts/rules etc.). For "the products I buy", use the two household-scoped endpoints:

- **`GET /products/mine`** → `List<HouseholdProductResponse>` = products your household has bought (confirmed, non-excluded), newest purchase first. Each: `{ productId, name, friendlyName, brand, category, timesBought, lastBoughtAt, lastUnitPrice, lastMarketCnpj, lastMarketName, lastMarketFriendlyName }`. `friendlyName` is your household's own rename of the product (null when never renamed) — prefer it for display. Display `lastMarketFriendlyName` (your custom market name when set, else the original `lastMarketName`).
  - **Search matches the rename too** (2026-08-26): the optional `query` filters by product name, brand **and `friendlyName`**. Same for `GET /products?query=` (returns products you renamed to something matching, even when the catalog name doesn't match) and `GET /receipts?q=`.
- **`GET /products/{id}/markets`** → `List<ProductMarketPriceResponse>` = where to buy this product, cheapest first. Scope: your **watched markets** always; nearby markets only when `includeNearby=true` (`radiusKm` from home). Each: `{ cnpj, cnpjRoot, marketName, friendlyName, price, priceType, communityMinPrice, sampleCount, distinctHouseholds, distanceKm, watched, visited, observedAt }`. Display `friendlyName` (custom-or-original).
  - **`priceType`** drives the privacy model: `OWN_LAST` = your household's own exact last paid price at a market you shopped at (your data). `COMMUNITY_MEDIAN` = the **k-anonymity-guarded** median from the collaborative index — only present when **≥3 distinct households** contributed (`economizaai.collaborative.min-households-for-public`); below that the market is omitted, never shown with a single-source price. **Why:** the price index is anonymized (no user/household FK); exposing a lone contributor's single price would re-identify them (a market with one contributor = that person's purchase). K=3 guarantees no individual purchase is exposed. The threshold is configurable, so the rule can be relaxed later if the privacy/legal stance changes — without code edits.

The "review queue" (`/unmatched`) is the workflow for messy receipts: items that didn't auto-
match show up here, the user picks the right product, and the alias is
automatically backfilled to all matching items.

---

## 6. Markets + watchlist

```
GET    /api/v1/markets[?scope=ALL|MINE&lat=&lng=]    → "Mercados" screen (20 km ∪ watched)
GET    /api/v1/markets/watched                       → "Meus mercados" (= scope=MINE)
POST   /api/v1/markets/watched/{cnpj}                → pin
DELETE /api/v1/markets/watched/{cnpj}                → unpin
PUT    /api/v1/markets/{cnpj}/name                   { "name": "Zaffari de casa" } → set a household-only custom name
DELETE /api/v1/markets/{cnpj}/name                   → revert to the global name
```

`GET /markets` (2026-11-10): `scope=ALL` (default) returns markets within **20 km**
(`best-markets-nearby-radius-km`) of the resolved origin UNION the household's watched
markets; `scope=MINE` returns only the watched ones. Pass `lat`/`lng` (all-or-nothing)
to measure from the device's current position; otherwise the origin follows the
"perto de você" cascade (see §7: device → profile → receipts-median → UF). **Sorted by
`distanceKm` ascending, nulls last** — a market from a receipt scanned far away (another
state) sinks to the end instead of flooding the top; watched-but-far markets are KEPT.
The old `radiusKm` param is gone (radius is fixed at 20 km).

Each row: `{ cnpj, cnpjRoot, name, friendlyName, address, city, state, latitude,
longitude, distanceKm, visited, watching, chainName, chainLogoUrl }`. `name` is the full
legal razão social; **`friendlyName` is the cleaned display name** (2026-11-10 — household
rename → chain name → `cleanLegalName` heuristic, e.g. "BISTEK SUPERMERCADOS LTDA" → "Bistek"),
so prefer it for display; `city`/`state` are new (2026-11-10, null until geocoded); `visited` =
has bought here, `watching` = pinned. Watched markets bypass the radius filter — useful
for "the market on my commute is far from home but I want its promos anyway".

**Display-name cascade (2026-11-11):** every `friendlyName` / `marketFriendlyName` /
`lastMarketFriendlyName` resolves as household rename → **nome fantasia** from the public CNPJ registry
(title-cased) → razão social with the corporate boilerplate stripped. Endpoints that already used the
chain name keep it between the nome fantasia and the cleaned razão social. `name` / `marketName` stay raw.

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
GET /api/v1/price-index/products/{productId}/online-reference
→ same ReferencePrice shape as above
```

National ONLINE reference price — median across all online sellers (marketplaces
+ supermarket delivery), no geo. A **separate series** from the physical index:
the two never mix. Fed by items with a real EAN regardless of the seller's
segment (a marketplace's grocery EAN counts; a book doesn't match a grocery
product). Same k-anon / min-sample rules as the physical reference.

```
GET /api/v1/price-index/products/{productId}/in-store-reference
→ { "scope": "STATE" | "NATIONAL", "states": ["RS"], "reference": ReferencePrice }
```

Typical IN-STORE price across **every physical market** (no per-market split), the
fallback card for the best-markets screen when no chain reaches k-anon yet. Computed
first over the states the household has confirmed receipts in (`scope: STATE`,
`states` lists them); when that slice is below K / min-sample it falls back to the
national in-store series (`scope: NATIONAL`, `states: []`). Same hybrid disclosure
as the other references: `reference.medianPrice` is `null` with `kAnonBlocked: true`
and the counts visible when even the national slice is sparse. Uses the wider
best-markets window (180 days).

```
GET /api/v1/price-index/products/{productId}/best-markets?limit=10&radiusKm=5[&lat=&lng=&sort=CHEAPEST|NEAREST]
GET /api/v1/price-index/products/{productId}/generic-comparison?sort=CHEAPEST|NEAREST
GET /api/v1/price-index/generic-comparison?type=creme+de+leite[&ean=&sort=CHEAPEST|NEAREST]
GET /api/v1/price-index/scanned-products
GET /api/v1/price-index/promos?radiusKm=5
GET /api/v1/price-index/promos/feed[?lat=&lng=]   (lat/lng = GPS do aparelho, os dois ou nenhum; lidera a cascata)
```

**`sort` (2026-11-10)** on best-markets + both generic-comparison endpoints (default
`CHEAPEST`): `CHEAPEST` = price ascending (historical; best-markets keeps watched-first;
since 2026-11-11 best-markets orders by each row's `latestPrice` — the number the card
shows — and `isCheapest` goes to the lowest `latestPrice`, no longer to the lowest median);
`NEAREST` = `distanceKm` ascending, nulls last. The radius/scope cascade is unchanged —
`sort` only reorders. Independently of the order, each row/offer carries persistent
`isCheapest`/`isNearest` booleans (decided over the whole result set) so the FE shows
"Mais barato"/"Mais perto" badges regardless of the active sort. (No "vale a pena?"
value judgment — that's a deferred Phase 2, see `docs/PRODUCT_COMPARISON.md`.)

**`GET /scanned-products` (2026-11-10)** — the household's "produtos escaneados" history:
products consulted (via best-markets / generic-comparison / barcode scan), deduped per
product/type, newest-consult first, capped at 100. Each:
`{ productId (nullable), productName, typeLabel, lastConsultedAt, snapshotBestPrice
(nullable), snapshotScope, scanCount }`. Returns **only the stored snapshot** (the best
price + scope captured at consult time) — instant, no live recompute. The FE lazily
fetches the live price per visible item via the comparison endpoints above and computes
the "mudou de preço desde a última consulta" delta client-side.

`promos` returns currently-detected community promos in the user's area
(recent median ≥ 15% below baseline, k-anon protected). Each promo now also
carries `uf` (the market's state).

`promos/feed` wraps the same list with the LOCATION CONTEXT the app needs for
its "perto de você" cascade: `{ locationSource, uf, promos }`.
- `locationSource` (renamed values 2026-11-10): `DEVICE` (real-time GPS passed on the
  request), `PROFILE` (user set a home location), `RECEIPTS_MEDIAN` (estimated from the
  **median** of the geocoded markets the household scanned), `UF_FALLBACK` (centroid of
  the household's majority UF) or `NONE`. `RECEIPTS_MEDIAN`/`UF_FALLBACK` are **inferred**
  — nudge "confirme sua localização". *(Old `EXPLICIT`→`PROFILE`, `INFERRED`→`RECEIPTS_MEDIAN`.)*
  When ≠ `NONE`, each promo's `distanceKm` is relative to that location and the app filters
  by radius (20 km) + watched markets.
- `uf`: the user's majority receipt state — a state-level fallback when there are
  no coordinates (the app shows "markets in your state").
- `promos`: the full list; the app does the nearby/"see more" split client-side.
The inferred location is the user's own data, used only to rank what THEY see —
it never enters the collaborative index. Legacy `/promos` (plain array) stays for
older app builds.

`best-markets` returns **one row per market CHAIN** (`cnpjRoot`), not per store:
stores of a chain practice the same shelf price, so their observations and
contributing households are pooled, and k-anonymity (≥3 households) is checked at
chain level. Each row is shown as one concrete store of the chain — `cnpj`,
`marketName`, `distanceKm` describe that store, picked as: a watched store if any,
else the closest to the viewer, else the most recently seen. The `cnpj` is therefore
always a real store the FE can watch/unwatch or route to. Window: 180 days
(`best-markets-lookback-days`), wider than the 90-day promo baseline.

**Default scope (no `lat`/`lng`/`radiusKm`) = the "perto de você" cascade (2026-11-05)**,
same as `/promos/feed`, resolved server-side:
1. user has a location — explicit (GPS/CEP) or **inferred** (median of the markets the
   household scanned) → only chains with a store within **20 km**
   (`best-markets-nearby-radius-km`) or a **watched** store. No widening to the whole
   state: an empty list is the honest "nothing near you" (the in-store card covers it);
2. no location but watches markets → only the watched chains;
3. else the household's majority **UF** → chains with a store in that state;
4. else national.

**The same rule (`NearbyScope`) applies app-wide:** `/deals` (`includeNearby=true`;
`false` = watched only), the daily deals digest push, PRICE_DROP alerts (rule radius or
20 km; unlocated → same UF), dashboard top promos, legacy `/price-index/promos`,
`/products/{id}/markets?includeNearby=true`, and "nearby" in `/markets` + product search
(use the inferred location when no address is set).

The scope only picks WHICH chains appear and which store represents each; the chain's
price still aggregates all its observations (k-anon household count is chain-wide).

Pass `lat`/`lng` to measure from the current position instead (barcode scan inside a
store — see the flow below); passing `lat`/`lng` or `radiusKm` bypasses the cascade and
keeps the caller's own scope. With a `radiusKm`, a chain stays listed when its display
store is within range or watched.

Each `best-markets` row carries both an aggregate and a real value:
`medianPrice`/`minPrice` (the "usual price" baseline, over the whole chain) **plus**
`latestPrice` + `latestObservedAt` — the actual most-recent observed price in that
chain and when it was seen. Show `latestPrice` + "visto em {latestObservedAt}" on the
market card (a real number the shopper will roughly find, honestly dated); reserve
the median for "usual price" contexts.

**k-anonymity is tiered (2026-11-03).** Grocery aggregates (`best-markets`,
`in-store-reference`, `online-reference`, `markets/{cnpj}/reference`) disclose from
**n=1** (`distinctHouseholds` may be 1 — render it honestly, never promise "3
famílias"). `latestObservedAt`/`mostRecentAt` carry **day precision only**. Anything
SENSITIVE keeps the full K=3 / min 5 observations: products in the health category
group and observations from any store that is NOT classified as grocery retail —
pharmacies and, since 2026-11-11, stores whose segment is still unknown/unclassified
(a product without a category stays grocery). List-shaped reads (admin, exports,
merchant panel) always keep K=3.

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

Every `by-ean` call is logged (append-only `product_scan`) with the match result
(exact/generic/none), the resolved type, and a snapshot of the resolved location —
best-effort, never affects the response.

**Exact-first + "ver todos".** Prefer the exact match (same product, where it's
cheapest). When the exact product has **no price** (empty `best-markets`), or to let
the user open the full brand/size range, call the generic comparison:

```
GET /api/v1/price-index/products/{productId}/generic-comparison
→ {
    typeName: "Creme de Leite",          // header, the scanned product's generic name
    scannedProductId: "…",               // flag this offer's row ("escaneado")
    scope: "STATE",                       // which cascade tier produced the list: NEARBY | STATE | NATIONAL
    offers: [                             // FLAT list, one row per (product, market), cheapest first
      { productId, productName: "Italac 200g", brand, packLabel: "200g",
        unitPrice: 2.99,
        marketCnpj: "11111111000100",    // 14 digits; key for the per-market price history below
        marketName: "SUPERMERCADO A COMERCIO LTDA",   // full legal razão social (show in details)
        marketFriendlyName: "Supermercado A",          // friendly label for the ROW (alias → chain → cleaned)
        city: "Porto Alegre", state: "RS",
        distanceKm: 2.3,                 // nullable (null when no coordinates)
        observedAt, channel: "IN_STORE", scanned: false,
        isCheapest: true, isNearest: false },   // persistent badges (see `sort` above)
      …
    ]
  }
```

A **flat, price-ascending list of offers** across every brand/size of the type — the
same product can appear multiple times (one row per market). Each offer is the
**most-recent observed price** for that (product, market) pair. Scope **widens** until
it finds offers: within ~20 km of the user → the user's state (`STATE`) → national
(`NATIONAL`) — unlike single-product `best-markets`, which stays near the user on
purpose (this is an explicit "price-range reference"). Tiered k-anon per (product,
market): grocery may disclose at n=1, pharmacy/health keep K=3 (fail-closed). Empty
`offers` only when there is genuinely no data for the type across the whole cascade.
These are **different brands/sizes** — a price-range reference, not the same product
(show the caveat). Keep the scanner admin-only in the app for now.

**Market naming (new):** `marketName` is now the full legal razão social (for the
expanded details), and `marketFriendlyName` is the display label for the row — the
same derivation used in insights price-history: household rename (`HouseholdMarketAlias`)
→ chain name (`MarketChain` by CNPJ root) → a cleaning heuristic that strips corporate
boilerplate (`COMERCIO E INDUSTRIA`, `LTDA`, `S/A`, `EIRELI`, `ME`, …) and title-cases.
Fall back to `marketName` when `marketFriendlyName` is absent.

When the scan only returned a `catalogPreview` (no exact product to anchor on),
resolve the type by name instead — same shape, `scannedProductId: null`:

```
GET /api/v1/price-index/generic-comparison?type=Creme%20de%20Leite
```

Optionally pass the `catalogPreview.ean` as `&ean=...`. This anchors the type
resolution on the catalog entry: when Open Food Facts stored the name in English
or junk (e.g. EAN 7896079431110 = a Brazilian white rice, OFF name "White Rice"),
the backend maps it to a type that actually exists in our pt catalog ("arroz") via
a small static staple map or, as a last resort, closed-set AI (returns exactly one
of our existing priced types, or none). The displayed name/brand never change —
only the comparison TYPE key. Without `ean` the behavior is unchanged (resolves
from the raw name only). Resolution is cached per EAN (≤1 AI call per code), and
is inert (falls back to the empty state) when no AI key is configured.

```
GET /api/v1/price-index/generic-comparison?type=White%20Rice&ean=7896079431110
```

### Price history for one (product, market) pair

Backs the **expandable offer row** (stock/dollar-style line chart). Same response
shape as the insights price-history (`PriceHistoryResponse`, so the FE reuses the
same chart), the **same tiered k-anon per (product, market)** as the offers above
(grocery n=1, pharmacy/health K=3, fail-closed). Blocked or no data → `points: []`
(never partial). One point = render the single price, no crash.

```
GET /api/v1/price-index/products/{productId}/markets/{marketCnpj}/price-history
→ {
    productId, productName, friendlyDescription,   // friendlyDescription = household product rename
    points: [
      { issuedAt, marketCnpj, marketName,          // marketName = full legal razão social
        marketFriendlyName,                         // row-friendly label (same derivation as above)
        unitPrice, quantity },
      …                                             // ascending by time (oldest → newest)
    ]
  }
```

Authenticated like the other price-index endpoints.

---

## 7b. Deals — "Ofertas pra você"

The active, ranked list of discounts currently relevant to the household: for
every product the household has bought, the cheapest market chain whose **most
recent observed price** beats what the household last paid by a meaningful
margin. This is the screen the daily digest push deep-links into.

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
      "currentPrice":     7.00,                   // the chain's MOST RECENT observed price (≤ 60 days old)
      "lastPaidPrice":    10.00,
      "savingsAmount":    3.00,
      "savingsPct":       30.00,
      "discountFraction": 0.3000,
      "distinctHouseholds": 4,
      "distanceKm":       2.3,                    // nullable
      "isWatched":        true,
      "observedAt":       "2026-11-01T00:00:00",  // day of the observation that priced `currentPrice`
      "priceSource":      "COMMUNITY"             // OWN_PURCHASE | COMMUNITY
    }
  ]
```

**What the price fields mean (2026-11-11):**
- `currentPrice` is the chain's **latest observed price**, at most 60 days old
  (`DEALS_MAX_PRICE_AGE_DAYS`) — the same number the best-markets screen shows as
  `latestPrice` for that chain. It is no longer a median.
- `observedAt` is the **date of that observation** (day precision — the time part is
  midnight), not the date of the household's last purchase.
- `priceSource` says whose observation it is: `OWN_PURCHASE` = the household itself paid
  that price at that chain on that day ("Você pagou R$ 7,00 no Atacadão em 01/11");
  `COMMUNITY` = someone else's receipt ("Visto por R$ 7,00 no Atacadão em 01/11").
- The chain where the household made its **last** purchase of the product is never
  suggested (that is the price being compared against).

Params (all optional): `includeNearby` (default `false` — watched markets only;
`true` also considers markets within `radiusKm` of home), `radiusKm`,
`limit` (default 20; `limit <= 0` returns `[]`).

Ranking: by savings (discount fraction) desc, tie-broken by purchase frequency.
The discount bar is **progressive** — ~20% required on a R$1 item, ~5% on a
R$200 one — so trivial drops don't surface. K-anonymity is applied **by tier**
inside the price index, as on best-markets (§7): grocery prices may be shown from a
single household's receipt; health products and non-grocery / unknown-segment
stores need ≥ 3 distinct households, below that the chain is dropped. Returns `[]`
when the collaborative index is off or nothing qualifies.

**Relevance feedback (2026-06-12):** the `DISMISSED` / `MUTED` events the app
reports (§10b) feed a per-user suppression filter on this list and the digest —
mute hides the product everywhere for 180 days, dismissal for 14 days. A
`DISMISSED` **with `marketCnpj`** hides only that (product, market) pair;
without it, the whole product. Currently in SHADOW (measured, not applied);
once validated it flips ON server-side with no API change — so always send
`productId` (+ `marketCnpj` for dismissals) on these events.

### How the daily deals digest works (push)

The deals screen above is always-on and read-only. The **digest** is the push
that nudges the user back to it. A scheduler runs hourly and, for each user whose
send hour **has already been reached today** and who hasn't received a digest
*today* (1/day hard cap), recomputes their deals and sends **one** `DEALS_DIGEST`
push — but only when at least one deal is **newsworthy** (brand-new, discount
improved by ≥ 5 p.p. / crossed a stricter relevance step, or it lapsed past the
collaborative lookback window). Standing, unchanged deals never re-notify. With
nothing newsworthy nothing is sent and the day's slot is not used up — the user is
looked at again on the next hourly run. The push quotes the same price as the
deals screen.

- **Preferences**: `GET`/`PUT /api/v1/users/me/digest-preferences` (above).
  `frequency` `OFF` = never; `WEEKLY` = once a week (currently Thursdays);
  `DAILY` = at most once a day. `sendHour` (0-23) overrides the send time;
  leave it `null` and the backend picks it: the household's typical shopping
  hour minus one once there is enough history, otherwise a per-user default
  spread between 11:00 and 19:00. Timezone is America/Sao_Paulo (v1).
- **Quiet hours**: a digest due between 21:00 and 08:00 lands in the inbox and its
  push goes out after 08:00 (§10b "Quiet hours").
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
{ "tier": "PRO", "status": "ACTIVE", "provider": "revenuecat", "currentPeriodEnd": "2026-07-12T10:00:00",
  "cancelAtPeriodEnd": false, "rewardPremiumUntil": null }
```

- `tier` — `FREE` | `PRO` (same value as in `GET /users/me`). PRO while the
  subscription is `ACTIVE` **or** a referral-reward window is running.
- `status` — provider lifecycle: `ACTIVE` | `CANCELED` | `EXPIRED`, or `null`
  if the user never had a subscription record (plain FREE).
- `provider` — e.g. `revenuecat`, `mercadopago`, `manual` (launch promo or admin
  grant), `null` for FREE.
- `currentPeriodEnd` — when the current period lapses. `null` for FREE and for
  open-ended admin grants. The hourly expiry sweep downgrades after it: on time
  for the launch promo and for a non-renewing subscription; a paid, still-renewing
  subscription gets a **3-day grace** first (a renewal notice can be late).
- `cancelAtPeriodEnd` — `true` when the subscription will **not renew** (the user
  cancelled / paused at the store or at Mercado Pago). Access continues until
  `currentPeriodEnd` — show "seu Premium vai até DD/MM", not "cancelado".
- `rewardPremiumUntil` — end of the Premium time earned through referrals, when
  some is still ahead (it starts after the current period); `null` otherwise.
- A paid subscription that ends while the user's launch promo is still running
  falls back to the promo (`provider: "manual"`, `currentPeriodEnd` = promo end).
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
`averageQuantityPerPurchase`, `typeLabel` (generic type, e.g. "biscoito") and
`categorySlug` (taxonomy leaf, nullable) — use both to pick the product icon.
**Volume gate**: a product needs purchases on **3 distinct days**; only when NO
product of the household reaches 3 does the gate fall back to 2 (so a new
household never sees an empty screen). Returns empty array, never an error.

```
GET /api/v1/consumption/predictions/page[?status=RAN_OUT|RUNNING_LOW|OK&page=0&size=30]
→ { "items": [ ... ], "counts": { "ranOut", "runningLow", "ok", "total" },
    "page", "size", "hasMore" }
```

Paged + filterable version for the forecast screen. Items are ordered by
**urgency** — closest to the run-out date first (ran out yesterday / runs out
tomorrow before one overdue for months; ties put the already-run-out first).
`counts` always cover ALL statuses (ignore the filter) so the filter chips show
totals. `size` is capped at 100.

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
POST /api/v1/shopping-list/optimize      (alias: /api/v1/shopping-lists/optimize — same handler)
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

Each `NotificationResponse` carries `payload` — the JSON we attached when we generated the notification (`receiptId`, `productId`, `savingsPct`, etc) so cards can deep-link. `type` is one of `PROMO_PERSONAL`, `DEALS_DIGEST`, `DIGEST`, `PRICE_DROP`, `STOCKOUT`, `BUDGET`, `SYSTEM`, `RECEIPT_READY`, `RECEIPT_FAILED`, `RECEIPT_RECOVERED`, `PUSH_OPTIN`, `HOUSEHOLD_MEMBER_JOINED`, `HOUSEHOLD_MEMBER_LEFT` (old inbox rows may still carry the retired `PROMO_COMMUNITY` / `CHEAPER_MARKET`) — what triggers each and how to toggle it is in §10f.

Each response also carries `destination` — a routing hint derived from `type` so the FE knows which screen to open when the card is tapped (the concrete id it needs is already in `payload`). Values:

| `destination`   | screen                 | types that map to it                                          |
|-----------------|------------------------|--------------------------------------------------------------|
| `DEALS`         | deals / offers screen  | `PROMO_PERSONAL`, `PROMO_COMMUNITY`, `CHEAPER_MARKET`, `DEALS_DIGEST`, `DIGEST` |
| `REPLENISHMENT` | replenishment / stock  | `STOCKOUT`                                                    |
| `PRODUCT`       | product detail         | `PRICE_DROP` (use `payload.productId`)                        |
| `BUDGET`        | budget screen          | `BUDGET`                                                      |
| `INBOX`         | generic inbox          | `SYSTEM` (and any future/unmapped type)                      |

> **Real-time vs digest:** the only notifications pushed in **real time** (on receipt confirm) are the user's explicit alerts — `PRICE_DROP` and `BUDGET`. The **discovery** content (personal promos, community promos, cheaper market) is delivered **only via the daily deals digest** (§ "How the daily deals digest works"), not in real time. `PROMO_COMMUNITY` and `CHEAPER_MARKET` no longer exist as toggles (2026-11-11) — the digest frequency is the control.

**Quiet hours — 21:00 to 08:00 (America/Sao_Paulo).** Nothing is pushed or e-mailed inside the window. The notification still lands in the inbox immediately (so `GET /notifications` and the unread badge are live); its push goes out on the first hourly run after 08:00 — at most **3 held-back pushes per user**, oldest first; the rest stay inbox-only. Exempt, always immediate: `RECEIPT_READY`, `RECEIPT_FAILED`, `RECEIPT_RECOVERED` (they answer something the user just did). Nothing for the FE to do, but don't expect a push for a night-time event before 08:00.

**Money and language in notification texts.** Amounts are formatted Brazilian-style (`R$ 1.234,50`) and server-generated notification texts are always in Portuguese, whatever `Accept-Language` the device sent at signup.

**System notices worth a dedicated card** (all `type: "SYSTEM"`, told apart by `payload.kind`): `LAUNCH_PROMO_ENDING` — the free Premium is about to end (sent 15, 7, 3 and 1 day before, only to promo holders without a paid subscription; route to the paywall); `REFERRAL_REWARD` — a referral earned a month of Premium (`rewardKind`, `startsOn`, `endsOn`).

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

## 10d. Admin (ROLE_ADMIN only — consumed only by the FE's admin dashboard, never the consumer app)

```
GET    /api/v1/admin/users?q=&from=&to=&platform=&channel=&uf=&includeInternal=false&page=&size=&sort=   → Page<AdminUserSummaryResponse> (sort: createdAt|name|subscriptionTier|receiptCount|totalSpend|cost|uf, e.g. receiptCount,desc; `uf` = predominant state A→Z / Z→A, users with no state always last; from/to ISO date-time; platform = registration Platform; channel = AcquisitionChannel)
GET    /api/v1/admin/users/uf?householdIds=<uuid>&householdIds=…   → { "<householdId>": "SP", … } — predominant UF per household (lazy lookup for the user list; households with no nota are absent)
GET    /api/v1/admin/users/cost?userIds=<uuid>&userIds=…           → { "<userId>": <cents>, … } — lifetime paid-API cost per user (absent = 0)
GET    /api/v1/admin/users/{id}              → AdminUserDetailResponse
GET    /api/v1/admin/users/{id}/cost         → UserCostResponse { totalCents, byService[], byMonth[] } — where one user's paid-API cost comes from (lifetime)
PUT    /api/v1/admin/users/{id}/subscription-tier → AdminUserDetailResponse — body {"tier":"PRO"|"FREE"} (see §13)
PATCH  /api/v1/admin/users/{id}/metrics-exclusion → AdminUserDetailResponse — body {"excluded":true|false}
DELETE /api/v1/admin/users/{id}              → 204 (deletes account + data; 400 for ADMIN accounts)
PATCH  /api/v1/admin/users/{id}/role          → AdminUserDetailResponse — body {"role":"MERCHANT"|"USER"}; anything touching ADMIN → 409; demoting a MERCHANT revokes its chain grants
GET    /api/v1/admin/users/{id}/merchant-access           → List<MerchantAccessResponse> {cnpjRoot, grantedAt}
POST   /api/v1/admin/users/{id}/merchant-access           → 201 MerchantAccessResponse — body {"cnpjRoot":"93015006"} (8 digits = the chain); 409 if not MERCHANT or already granted
DELETE /api/v1/admin/users/{id}/merchant-access/{cnpjRoot} → 204 (404 if the grant doesn't exist)
GET    /api/v1/admin/merchant-claims          → List<AdminMerchantClaimResponse> (fila PENDING_REVIEW — claims sem e-mail utilizável na Receita)
POST   /api/v1/admin/merchant-claims/{id}/approve → MerchantClaimResponse (promove a MERCHANT + grant + assinatura)
POST   /api/v1/admin/merchant-claims/{id}/reject  → MerchantClaimResponse — body {reason?}
GET    /api/v1/admin/merchant-promos?cnpjRoot=&page=&size= → Page<MerchantPromoResponse> (moderação das promos anunciadas)
PATCH  /api/v1/admin/merchant-promos/{id}/active   → MerchantPromoResponse — body {"active":false} (tira de circulação sem apagar)
GET    /api/v1/admin/receipts?from=&to=&marketCnpj=&category=&q=&householdId=&uf=&status=&parseErrorReason=&userQ=&entryMethod=&createdFrom=&includeInternal=false&page=&size=
                                              → Page<AdminReceiptSummaryResponse> — { receipt: ReceiptSummaryResponse, owner: {id,name,email}|null, uf, createdAt, parseErrorReason, parseErrorMessage }
                                                entryMethod (repeatable) = QR_SCAN | MANUAL_CHAVE | PHOTO_CHAVE | PHOTO_RECEIPT | IMPORT; also accepted by /stats and /failure-breakdown.
                                                from/to filter by PURCHASE date (a failed nota has none); createdFrom (ISO date-time, list only) filters by ARRIVAL — use it to window failures.
GET    /api/v1/admin/receipts/stats?<same filters as the list>   → AdminReceiptStatsResponse { count, totalAmount } — list header total
GET    /api/v1/admin/receipts/failure-breakdown?<same filters, minus status/parseErrorReason> → [{ reason, count }] — FAILED_PARSE notas grouped by reason ("falhas por motivo" pie; status forced to FAILED_PARSE)
GET    /api/v1/admin/receipts/entry-methods?days=30 → EntryMethodStatsResponse { windowDays, total, lines[] } — how notas are entered (QR scan / typed chave / photographed chave / photo / import): share + per-method failure rate
GET    /api/v1/admin/receipts/{id}            → AdminReceiptDetailResponse { receipt: ReceiptResponse, owner }
POST   /api/v1/admin/receipts/{id}/reparse    → 200 ReceiptResponse
POST   /api/v1/admin/receipts/retry-by-reason  → 202 { affected } — body {"reasonContains":"INFOSIMPLES"}; re-ingere toda nota FAILED_PARSE cujo motivo contém o fragmento
POST   /api/v1/admin/receipts/retry-batch      → 202 { affected } — re-ingere o backlog FAILED_PARSE de notas escaneadas pelo qrPayload guardado (cross-household; body opcional { ids: [...] }, sem body = backlog inteiro, cap 200)
POST   /api/v1/admin/notifications/test       → 202 Accepted
GET    /api/v1/admin/alerts/config            → AdminAlertsConfigResponse { emailActive, whatsAppEnabled, adminPhoneCount, adminTemplateConfigured, twilioWhatsAppConfigured, templateStatus } — canais de alerta operacional do admin
POST   /api/v1/admin/alerts/test              → 200 { triggered, at, … } — dispara um alerta operacional de teste (e-mail + WhatsApp quando configurado)
GET    /api/v1/admin/notifications/sent?days=30&page=&size=  → Page<AdminNotificationSummaryResponse> (sent notifications, newest first)
GET    /api/v1/admin/notifications/effectiveness?days=30   → NotificationEffectivenessResponse { windowDays, byType[], byHour[], byDayOfWeek[], topTexts[] }
GET    /api/v1/admin/notifications/audiences               → List<AudienceResponse> (built-in first, live matchCount)
GET    /api/v1/admin/notifications/audiences/{id}          → AudienceResponse
POST   /api/v1/admin/notifications/audiences               → 201 AudienceResponse
PUT    /api/v1/admin/notifications/audiences/{id}          → AudienceResponse (built-in → 400)
DELETE /api/v1/admin/notifications/audiences/{id}          → 204 (built-in → 400; usado por campanha → 409)
GET    /api/v1/admin/notifications/audiences/{id}/preview  → { matchCount, sampleEmails }
GET    /api/v1/admin/notifications/campaigns?page=&size=   → Page<CampaignResponse> (cada linha com bloco metrics)
POST   /api/v1/admin/notifications/campaigns               → 201 CampaignResponse (alvo = audienceId OU recipientUserIds[] — exatamente um; scheduledAt null=DRAFT, futuro=SCHEDULED, passado=400)
GET    /api/v1/admin/notifications/campaigns/{id}          → CampaignResponse
PUT    /api/v1/admin/notifications/campaigns/{id}          → CampaignResponse (só DRAFT/SCHEDULED)
POST   /api/v1/admin/notifications/campaigns/{id}/send     → 202 CampaignResponse (enviar agora; scheduler dispara em ~15s)
POST   /api/v1/admin/notifications/campaigns/{id}/cancel   → CampaignResponse (só DRAFT/SCHEDULED)
POST   /api/v1/admin/notifications/campaigns/{id}/test     → 202 (manda só pro admin chamador, fora das métricas)
DELETE /api/v1/admin/notifications/campaigns/{id}          → 204 (só DRAFT/CANCELLED/FAILED)
GET    /api/v1/admin/notifications/campaigns/{id}/metrics  → CampaignMetricsResponse (funil + engajamento + conversão)
GET    /api/v1/admin/notifications/settings                → List<NotificationSettingResponse> (notificações de sistema: enabled/agenda/templates + defaults)
PUT    /api/v1/admin/notifications/settings/{job}          → NotificationSettingResponse (job ∈ WEEKLY_DIGEST|MONTHLY_DIGEST|DEALS_DIGEST|BUDGET_ALERT|REPLENISHMENT; efeito imediato)
GET    /api/v1/admin/notifications/insights?days=30        → NotificationInsightsResponse (funil por tipo c/ abertura, melhor horário, por UF, engajados vs silenciosos, conversões R$)
GET    /api/v1/admin/products?page=&size=&q=&category=&categoryNodeSlug=&source= → Page<ProductResponse> (catalog; optional filters: name/EAN substring, category e.g. OTHER, taxonomy node slug, categorizationSource)
GET    /api/v1/admin/products/missing-brand    → Page<MissingBrandProductResponse>
PATCH  /api/v1/admin/products/{id}/brand       → 200 ProductResponse
PATCH  /api/v1/admin/products/{id}/category    → 200 ProductResponse — body {"category":"PET_SUPPLIES"}; sets GLOBAL enum + locks it (source=USER)
PATCH  /api/v1/admin/products/{id}/category-node?slug=queijos → 200 ProductResponse — seta a FOLHA fina (products.category_id), source=USER. slug de GET /categories/taxonomy. 400 se slug desconhecido
GET    /api/v1/admin/products/taxonomy-coverage → { total, withLeaf, withoutLeaf } — cobertura da migração pra taxonomia
GET    /api/v1/admin/products/duplicates       → List<DuplicateProductGroupResponse>
GET    /api/v1/admin/products/variant-conflicts?max=200 → VariantConflictResponse — READ-ONLY audit of likely MIS-MERGED products (a single product whose linked items carry >1 distinct REAL EAN, or inconsistent distinguishing-token signatures e.g. "AO LEITE SGLUT" + "50% ZERO" under one product). Reports productId + conflicting item names/EANs for MANUAL review; mutates nothing.
POST   /api/v1/admin/products/{id}/merge       → 200 ProductMergeResultResponse
POST   /api/v1/admin/products/split-variants?dryRun=true&max=500&strategy=DETERMINISTIC → 200 ProductVariantSplitResponse — one-time cleanup that SPLITS mis-merged products (one product holding items of DIFFERENT variants/brands/flavours). Groups items by variant signature (brand + VariantDiscriminator tokens); a product spanning >1 signature is split into one product per signature, reassigning receipt_items + price_observations + product_aliases; generic_name_norm preserved. dryRun=true (default) returns the PLAN, mutates nothing; dryRun=false applies. Idempotent (single-signature products skipped).
POST   /api/v1/admin/products/split-variants?strategy=AI&dryRun=true&max=50 → 200 AiProductVariantSplitResponse — AI-assisted split for the RESIDUAL long tail the deterministic dictionary can't disambiguate ("CAFE MELITTA 500G" still mixing Pimpinela/Canecão/Melitta — the extractor returns "café" for all; brand×flavour collisions like "orquídea"). Candidate gate (cost bound): only products STILL mixed after the deterministic pass (>1 distinct REAL EAN OR >1 deterministic signature), capped at max (default 50). Each candidate's distinct items are clustered by the LLM with a CONSERVATIVE prompt (different barcodes do NOT mean different products — a re-barcode is common; separate ONLY on a clearly different brand/flavour/variant name, else keep together) and a per-split confidence (HIGH/LOW). TWO cohesion guards are enforced OVER the AI, fail-closed: (1) EAN cohesion — items sharing the same real GTIN are forced into one group even if the AI split them; (2) identical-name cohesion — items whose NORMALIZED raw description is identical are forced into one group even if the AI split them. If the guards collapse to one group ⇒ no split. SAFE-APPLY GATE: on dryRun=false a split is materialized ONLY when it is HIGH-confidence AND the separated groups have MATERIALLY DIFFERENT (normalized) names; low-confidence or name-identical separations are DROPPED (items stay merged). Applies via the same mechanics (new products, receipt_items + price_observations + product_aliases repointed, generic_name_norm preserved). dryRun=true (default) mutates nothing and returns the SAFE splits in splits[] plus a per-product safety[] verdict ({ productId, productName, proposedGroups, confidence, safeToApply, reason ∈ SAFE|LOW_CONFIDENCE|NAMES_NOT_MATERIALLY_DIFFERENT|NO_SPLIT }) so a human previews exactly what a real apply would change vs skip; safety[] is empty on a real apply. aiAvailable=false (no-op) when no AI provider has an API key. Grouping (incl. confidence) cached per product+item-set hash so re-runs don't re-pay.
POST   /api/v1/admin/products/refresh-brands       → BrandBackfillResponse (fill missing brands)
POST   /api/v1/admin/markets/classify-segments     → SegmentClassificationSummary (CNAE-classify pending markets)
GET    /api/v1/admin/merchants/grey                → List<GreyMerchantResponse> (grey-zone review queue, ranked by scan volume)
GET    /api/v1/admin/llm/report                    → LlmReportResponse (LLM labels, cost, override-rate KPI, disagreement queue)
POST   /api/v1/admin/llm/disagreements/{id}/resolve?accept=true|false → 204 (accept applies suggestion as source USER)
PUT    /api/v1/admin/merchants/{cnpj}/support      → SupportOverrideResult — body {"override":"SUPPORTED"|"BLOCKED"|null}; SUPPORTED backfills the price index from the merchant's confirmed receipts
PUT    /api/v1/admin/merchants/{cnpj}/classification → ClassificationResult — body {"segment":"SUPERMARKET"|"PHARMACY"|"GAS_STATION"|"OTHER"}; veredito por segmento (Mercado/Farmácia/Combustível/Bloquear). Grava o segmento como MANUAL (o classificador CNAE não sobrescreve), limpa o override p/ segmentos suportados (ou BLOCKED p/ OTHER) e faz backfill do índice. Preferir a /support — evita que um mercado promovido vaze pra lente Farmácia
DELETE /api/v1/admin/products/{id}?force=false     → 200 ProductDeletionResponse (prune test/junk catalog rows)
GET    /api/v1/admin/overview?includeInternal=false → AdminOverviewResponse { usersTotal, usersToday, usersThisWeek, usersPro, usersPayingActive, receiptsTotal, receiptsToday, parseRate30d, activeHouseholds1d/7d/30d, stickiness, totalSpendConfirmed, priceObservations, contributingHouseholds, byState[] } — "Visão geral" do painel
GET    /api/v1/admin/dashboard?period=7d|30d|all&from=YYYY-MM-DD&to=YYYY-MM-DD&includeInternal=false → AdminDashboardResponse { window{period,from,to,days}, users{total,inPeriod}, receipts{total,inPeriod,parseRate}, spend{total,inPeriod}, engagement{activeHouseholdsInPeriod,activeHouseholds1d/7d/30d,stickiness}, priceIndex{priceObservations,contributingHouseholds}, funnel{signups,verified,activated,verifiedRate,activatedRate}, timeline[], byState[], recentUsers[], recentReceipts[] } — "Visão Geral" inteira numa chamada, para uma janela (padrão 7d; from+to vencem period; dias de Brasília sobre createdAt; período inválido → 400)
GET    /api/v1/admin/market-intel?includeInternal=false → MarketIntelResponse { priceObservations, contributingHouseholds, topProducts[], topMarkets[], topMarketsByChain[], categorySpend[], byUf[] }
GET    /api/v1/admin/analytics/acquisition?days=30&includeInternal=false → AcquisitionReportResponse (signup funnel + timeline by channel + byPlatform + campaign breakdown + Meta ad-spend / cost-per-signup)
GET    /api/v1/admin/analytics/subscriptions?includeInternal=false       → SubscriptionReportResponse (users by tier, paying-active, promo-granted)
GET    /api/v1/admin/analytics/deletion-reasons?days=30 → DeletionReasonStatsResponse { windowDays, total, answered, lines[], recentNotes[] } — motivos de exclusão de conta
GET    /api/v1/admin/marketing/dashboard?days=30   → MarketingDashboardResponse { windowDays, from, to, meta, organic } — Meta Ads spend + Google Search Console; each section carries configured=false until its env vars are set
GET    /api/v1/admin/analytics/store/ios?days=90   → StoreAnalyticsResponse { configured, dataDelayNote, syncedAt, dataFrom, dataTo, totals, bySource[], dailyRows[] } — funil iOS pré-instalação (App Store Connect)
POST   /api/v1/admin/analytics/store/ios/sync      → {"rowsSynced": n} — força a sincronização da App Store Connect agora
GET    /api/v1/admin/analytics/retention-cohorts?weeks=8&includeInternal=false → RetentionCohortResponse (weekly cohort triangle + per-channel pooled retention curve)
POST   /api/v1/admin/analytics/ad-spend/sync       → {"rowsSynced": n} — run the Meta ad-spend sync now instead of waiting for the daily cron (0 when Meta is not configured)
GET    /api/v1/admin/costs/infosimples             → InfosimplesFinanceResponse — painel da conta pré-paga: saldo/consumoMes/franquiaMinima ao vivo, aVarrerNoFechamento + proximoFechamento (dia 1º), totais históricos e historico[] mensal (recarga/consumo/varrido)
GET    /api/v1/admin/costs?days=30                 → CostReportResponse (paid-API spend: total + by service + by state + today vs budget + conta Infosimples ao vivo: infosimplesSaldo, infosimplesConsumoMes (cobrado real), infosimplesFranquiaMinima — null se indisponível). `dailyGlobalBudgetCents` é o teto geral OPCIONAL (0 = desligado, o padrão desde 2026-11-11); os tetos que valem são por serviço (config, ver §3 "Cost guard" / INFRASTRUCTURE.md) e não vêm nesta resposta.
GET    /api/v1/admin/state-coverage?days=           → StateCoverageResponse (per-UF: VERIFIED/EXPERIMENTAL + NOTA outcomes attempts/successes/failures/pending/users/failureReasons + per-layer `strategies` telemetry)
GET    /api/v1/admin/ingestion-health?days=30&includeInternal=false → IngestionHealthResponse (pipeline health: status mix, parse success rate, stuck/timeout counts, per-UF outcomes, top parseErrorReason keys — from the receipts table)
GET    /api/v1/admin/notifications/relevance-report?days=30 → RelevanceReportResponse (deal-suppression shadow-mode KPI before flipping relevance ON)
DELETE /api/v1/admin/receipts/{id}/observations    → PurgeObservationsResponse (pull one receipt's contributions out of the price index)
GET    /api/v1/admin/ecommerce/offers?ean=&page=&size= → Page<CuratedOfferResponse> (curated online offers, newest edits first)
POST   /api/v1/admin/ecommerce/offers              → CuratedOfferResponse — body CuratedOfferRequest {ean, productId?, provider, title, externalUrl?, affiliateUrl?, imageUrl?, price, freight?, currency?, inStock?, active?}
PUT    /api/v1/admin/ecommerce/offers/{id}         → CuratedOfferResponse
DELETE /api/v1/admin/ecommerce/offers/{id}         → 204
GET    /api/v1/admin/leaderboard/discount-hunters?days=30 → LeaderboardResponse (all households, emails as handles)

# ── Redes de mercado (chains) + logos ──
GET    /api/v1/admin/chains?state=&search=&city=&hasLogo=&page=0&size=20 → Page<MarketChainResponse> { id, cnpjRoot, name, logoUrl, marketCount }
GET    /api/v1/admin/chains/states                 → ["RS", …] — UFs com redes (filtro)
GET    /api/v1/admin/chains/cities?state=          → ["Porto Alegre", …] — cidades com redes (filtro)
POST   /api/v1/admin/chains                        → MarketChainResponse — body {cnpjRoot, name}
PUT    /api/v1/admin/chains/{id}                   → MarketChainResponse — body {name}
POST   /api/v1/admin/chains/{id}/logo              → MarketChainResponse — multipart, campo "file"
DELETE /api/v1/admin/chains/{id}/logo              → remove o logo
GET    /api/v1/chains/logo/{id}                    → PNG do logo (PÚBLICO, sem auth; Cache-Control público de 1 ano; 404 sem logo). É o caminho devolvido em `logoUrl` (`/chains/logo/{id}?v=…`)

# ── Fotos retidas de tentativas de nota por foto (diagnóstico; toggle temporário, em memória) ──
GET    /api/v1/admin/photo-attempts/settings       → { enabled }
POST   /api/v1/admin/photo-attempts/settings       → { enabled } — body { "enabled": true|false }; vale até o próximo restart
GET    /api/v1/admin/photo-attempts/{receiptId}    → { receiptId, count, photos[] } (cada foto com index, mediaType e o caminho de download)
GET    /api/v1/admin/photo-attempts/{receiptId}/{index} → bytes da foto (404 se não existir)

# ── Garimpo de promoções (admin deal-hunting; live calls need ML creds — 503 without) ──
GET    /api/v1/admin/garimpo/search?q=&marketplace=mercadolivre&offset=0&limit=20&minDiscount= → GarimpoSearchResponse (live term search; changed prices land in the history; minDiscount filters the response only)
GET    /api/v1/admin/garimpo/marketplaces          → List<GarimpoMarketplaceResponse> {key, configured}
GET    /api/v1/admin/garimpo/products/{marketplace}/{externalId}/history?page=&size= → Page<GarimpoSnapshotResponse> (append-only price change-log, newest first)
GET    /api/v1/admin/garimpo/watches?page=&size=   → Page<GarimpoWatchResponse> (standing searches, newest first)
POST   /api/v1/admin/garimpo/watches               → GarimpoWatchResponse — body GarimpoWatchRequest {searchTerm, marketplace?, targetPrice?, minDiscountPercent?, active?}; needs targetPrice AND/OR minDiscountPercent (meeting either = hit)
PUT    /api/v1/admin/garimpo/watches/{id}          → GarimpoWatchResponse
DELETE /api/v1/admin/garimpo/watches/{id}          → 204 (hard delete; price history stays)
POST   /api/v1/admin/garimpo/watches/{id}/run      → GarimpoWatchRunResponse {watch, hits[], webhookNotified} (run now + refresh history; validates webhook wiring)

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
- **User detail** — the `receipts` counts (PENDING_CONFIRMATION / CONFIRMED / REJECTED / FAILED_PARSE) and `spendLast30Days` are now the **USER's own** (receipts whose `user_id` is this user), 2026-11-10. Household context is kept SEPARATE and labeled: `householdMemberCount`, `householdReceiptCount`, `householdSpend` (never conflated with the user's own numbers). Useful for triaging "why is X seeing Y".
- **Receipts list** — same content-search semantics as `GET /receipts` (substring on raw + friendly description, product name, market name) but cross-household. `householdId` is an additional optional filter to scope to one household. Includes `FAILED_PARSE` rows (useful for parser triage).
- **Receipts get** — bypasses the per-household ownership check.
- **Reparse** — re-runs parsing on the receipt's stored raw HTML and replaces its items with the freshly-parsed ones. Resets `status` to `PENDING_CONFIRMATION` and clears `confirmedAt` — the owner re-confirms to commit. Useful when a parser fix lands. 400 if `rawHtml` is missing (e.g. legacy rows from before we persisted it).
- **Notifications test** — body `{ "email": "...", "title"?, "body"?, "type"? }`. Resolves the user by email and dispatches a payload through `NotificationService`. Useful to verify push/SMTP wiring on demand. Returns 202 even if the channel is stubbed; check the inbox (`GET /notifications`) or the device to confirm delivery. `type` defaults to `SYSTEM`.
- **Products missing brand** — paginated list of products whose brand wasn't auto-extracted (`Product.brand IS NULL`). Each entry includes up to 5 sample `rawDescription` strings from the product's aliases — gives the curator context for what name to assign. Sorted by `normalizedName` so paging is stable.
- **Set product brand** — body `{ "brand": "Tio João" }`. Lightweight PATCH that only sets `brand`. Returns the updated `ProductResponse`. Use after the missing-brand listing to fill catalog gaps; subsequent canonicalization for items of this product (and the metadata-dedup gate) become more accurate as soon as brand is set.
- **Products duplicates** — returns groups of products that share an exact `(genericName, brand, packSize, packUnit)` profile and are therefore probable duplicates. Each group: `{ genericName, brand, packSize, packUnit, category, products: [ProductResponse] }`. Only products with all four metadata dimensions populated are eligible. Within a group, the oldest product appears first — a natural default survivor.
- **Merge product** — body `{ "absorbedId": "<uuid>", "dryRun": false }`. The `{id}` in the path is the **survivor**. Migrates all aliases, receipt items, price observations, manual purchases, shopping-list items, household aliases (drops where survivor already has one for the household), and consumption snoozes (same conflict logic) from `absorbed` to the survivor; deletes `absorbed`. Set `dryRun: true` to get the migration counts without applying any change — the only undo is to **not** apply. Returns `ProductMergeResultResponse` with per-table counts.
- **Recategorize catalog** — the old `GET`/`POST /admin/products/recategorize` were removed. Use `POST /categorizer/recategorize-taxonomy` (and its `/calibrate` dry-run) — see §11.

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
- **Admin user list ranking** — `AdminUserSummaryResponse` carries `receiptCount` and `totalSpend` which are now the **USER's own** (receipts whose `user_id` is this user, confirmed spend), 2026-11-10. Household context is separate: `householdReceiptCount` (all members' notas) and `householdMemberCount` (show a "Domicílio N" badge when > 1). `/admin/users?sort=` accepts `receiptCount,desc` / `totalSpend,desc` (now rank by the user's own numbers) on top of the entity sorts (`createdAt`, `name`, `subscriptionTier`) — the aggregate sorts are resolved server-side.
- **Ops error drill-down** — `/admin/receipts?parseErrorReason=<key>&status=FAILED_PARSE` lists the receipts behind one "erro mais comum" (the machine key before `:` in the ingestion-health breakdown). Matches the exact key or `key:details`.
- **Market intel by chain** — `/admin/market-intel` returns `topMarketsByChain` (grouped by the **CNPJ root** = first 8 digits of the company registration, so every branch of a chain like Zaffari collapses into one row) alongside `topMarkets` (one row per full CNPJ). Same `{ cnpj, name, scans, spend }` shape; on the by-chain list `cnpj`/`name` are one representative unit of the chain.
- **Sent notifications** — `GET /admin/notifications/sent?days=30&page=&size=` → `Page<AdminNotificationSummaryResponse>` `{ id, userEmail, type, channel, title, body, delivered, deliveredAt, failureReason, read, createdAt }`, newest first — the "notificações enviadas" list.
- **Notification campaigns (notification ops)** — create/send admin-authored notifications to a reusable **audience** and measure each one. Audiences (`/admin/notifications/audiences`) are filter definitions resolved LIVE at send time (`role`, `subscriptionTier`, `locale`, `hasPushToken`, `registeredWithinDays`, `activeWithinDays` = scanned a receipt in the window); the seeded built-in **"Admins"** audience is the locked safe test target, and `GET /{id}/preview` shows `{ matchCount, sampleEmails }`. Campaigns (`/admin/notifications/campaigns`) carry `name/title/body` (literal text), `type` (NotificationType — drives the FE deep-link exactly like organic pushes), optional `extras` (forwarded to the push payload, always including `campaignId`) and optional `scheduledAt`; lifecycle `DRAFT → SCHEDULED → SENDING → SENT` (+ `CANCELLED`, `FAILED` com retry via `/send`). "Send now" = `POST /{id}/send` (202 — the scheduler poll dispatches within ~15s; poll `GET /{id}` until `SENT`); `POST /{id}/test` sends only to the calling admin without tagging metrics. **Metrics** (block on every list row + drill-down at `/{id}/metrics`): funnel `sent/delivered/read` from the outbox (rows carry `campaign_id`), engagement `pushOpened/tapped/dismissed` from `notification_events`, attributed conversion (`convertedUsers`, `conversions`, `attributedSavings` R$ — CONVERTED events by recipients within 14d of the send) and rates (`deliveryRate/readRate/openRate/conversionRate`), so the performance of each text, send time and type is directly comparable.
- **E-commerce "vale a pena online?"** — INERT by default (see DEV_NOTES). `GET /receipt-items/{id}/offer` returns the cheapest online offer for the item's EAN (`EcommerceOfferResponse`: `provider, title, price, freight, total, currency, externalUrl, affiliateUrl, imageUrl, inStock, curated, worthIt, paidPrice, savings`); `worthIt` = savings ≥ the configured margin; **204** when no offer matches. With no provider configured it serves only admin-**curated** offers — CRUD at `/admin/ecommerce/offers` (`CuratedOfferRequest`, EAN → online product + price + link). Providers are pluggable (Mercado Livre included, dark until credentials).
- **Discount-hunter leaderboard** — `GET /leaderboard/discount-hunters?days=30` → `LeaderboardResponse` `{ windowDays, entries:[{ rank, handle, finds, savings, isMe }], me }`. Public view lists only opted-in households (first-name handles) but always returns the caller's own `me`. Opt in/out via `PATCH /leaderboard/opt-in` `{ optIn }`. Admin view `/admin/leaderboard/discount-hunters` lists everyone with email handles (`me` null). Metric = confirmed items bought below the community average unit price (product seen by ≥2 households).
- **`byPlatform` (acquisition response)** — `[{ platform: "WEB"|"ANDROID"|"IOS"|"UNKNOWN", signups }]` — where the signup happened (from the immutable `registrationPlatform`); breaks down the `UNKNOWN`-channel signups (`UNKNOWN` = client didn't send a platform).
- **`adSpend` budget/status** — `adSpend.budgetRemaining` (aggregate R$) and, per `adSpend.byCampaign[]`: `status` (Meta effective_status), `lifetimeBudget`, `budgetRemaining`, `endsAt` (YYYY-MM-DD), `ended` (bool). Synced from Meta by the ad-spend job; all null until Meta is configured + synced.
- **`revenue` (acquisition response)** — money view. `realizedRevenue` is **real** provider money in the window (from RevenueCat purchase/renewal events → `RevenueEvent`; starts ~0, grows). Everything else is **modeled** from the configured PRO price until real payments accumulate (`revenueRealized=false` while there's none): `mrrProxy` (all PRO × monthly price), `ltvPerProUser` (monthly × `assumedLifetimeMonths`), `projectedLtv` (PRO signups in window × that), `roas` (realized ÷ spend), `projectedRoas` (projectedLtv ÷ spend), `ltvToCac` (ltvPerProUser ÷ cost-per-paid-signup), `byChannel:[{ channel, proUsers, realizedRevenue, projectedLtv }]`, `note`. Price is configurable via `PRO_MONTHLY_PRICE` (def. 9.90) / `PRO_YEARLY_PRICE` (89.00) / `PRO_ASSUMED_LIFETIME_MONTHS` (12). ROAS fields are `null` when there's no spend. Also carries **`payingCustomers`** (ACTIVE subscriptions with a real payment provider — excludes promo/manual grants) and **`avgTicket`** (ticket médio = `realizedRevenue ÷ payingCustomers`); both are `0` until billing is live, wired to populate automatically once real payments arrive.
- **Retention cohorts** — `GET /admin/analytics/retention-cohorts?weeks=8` (weeks clamped 4–16). The retention triangle: `{ weeks, cohorts:[{ weekStart (ISO Monday), size, activeByWeek:[…] }], byChannel:[{ channel, users, retentionByWeek:[…] }] }`. Users are grouped by signup week; `activeByWeek[k]` = distinct users from that cohort who scanned a receipt `k` weeks after signup (`k=0` = signup week). Each cohort's array only spans the weeks it's old enough to have observed (the FE renders a triangle — recent cohorts are shorter — never fabricated future zeros). `byChannel` is the per-channel **pooled** curve: `retentionByWeek[k]` in `0..1` over every cohort mature enough to have observed week `k`, so a fresh cohort never drags the tail down. Answers "do the users a channel brings keep coming back?"
- **`visits` (acquisition response)** — first-party top of funnel: `{ totalVisits, uniqueVisitors, signups, visitToSignupRate, byCampaign:[{ channel, source, medium, campaign, uniqueVisitors, signups, conversionRate }], note }`. Fed by the public visit beacon (below), so click→signup conversion is OUR number, independent of Meta. `note` set when no visits recorded yet.
- **`byHour` (acquisition response)** — signups by hour of day in **Brasília** time, always 24 entries: `[{ hour (0–23), total, byChannel:{CHANNEL:count} }]`, summed over every day of the window. The daily `timeline` is now also bucketed by Brasília day (before, signups after 21h landed on the next day).
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
| `DIGEST` | weekly (Mon 08:00) summary of your household's activity |

- **Retired (2026-11-11):** `PROMO_COMMUNITY` and `CHEAPER_MARKET` are no longer seeded or returned by `GET /notification-rules` — remove their toggles from the screen. Community promos and "cheaper at another market" reach the user through the deals digest (§7b), governed by `digest-preferences`.
- **Upsert:** `POST` of an existing `(type, productId)` updates it. Posting a default-scope type is rejected (`400 notificationrule.invalid.not-creatable`) — toggle it instead. The other validation errors are localized too: `notificationrule.invalid.threshold-required`, `notificationrule.invalid.product-required`, and `notificationrule.invalid.default-not-deletable` on `DELETE` of a default.
- **`STOCKOUT` cadence:** the reminder follows the consumption-prediction screen — "Já comprei" / "Adiar" there silence it — runs once a day (10:00) and stops after **3 reminders** without a new purchase.
- **`BUDGET`:** the scheduled check runs at 09:00 and 18:00 against the household's spend for the month **by purchase date** (the same number the dashboard shows).
- **Channel:** `channel` overrides the per-type preference (§10 preferences) for that one rule. Channels: `PUSH` (live), `EMAIL` (live once SMTP is wired), `ALEXA`/`SMS`/`WHATSAPP` (structure only — not yet functional), `NONE`.
- **No self-notify / cooldown:** community rules never fire for the contributor's own household; at most one fire per rule per 24h.

---

## 10g. Merchant panel (ROLE_MERCHANT — not consumed by the consumer FE)

Portal do lojista (docs/MERCHANT_ACCOUNTS.md). Invisível pra usuários comuns:
`/api/v1/merchant/**` responde 403 fora da role MERCHANT. Contas MERCHANT nascem
pelo claim self-serve (§10h) ou pelo admin (promote + grant em §10d). O escopo de
tudo é a(s) rede(s) concedida(s) por `cnpjRoot` (8 primeiros dígitos do CNPJ).

```
GET    /api/v1/merchant/profile          → MerchantProfileResponse { chains: [{ cnpjRoot, stores: [{cnpj, name, address, city, state, segment, receiptCount}] }] }
GET    /api/v1/merchant/price-comparison → MerchantPriceComparisonResponse { lookbackDays, products: [{productId, productName, state, chainMedianPrice, chainSampleCount, regionMedianPrice, regionSampleCount, deltaPercent}] }
GET    /api/v1/merchant/subscription     → List<MerchantSubscriptionResponse> {cnpjRoot, plan, status: PROMO|ACTIVE|EXPIRED, freeUntil, active}
GET    /api/v1/merchant/promos?page=&size= → Page<MerchantPromoResponse> {id, cnpjRoot, ean, productId, productName, description, promoPrice, regularPrice, startsAt, endsAt, source, active, verifiedByReceipts}
POST   /api/v1/merchant/promos           → 201 MerchantPromoResponse — body MerchantPromoRequest {cnpjRoot? (obrigatório só com >1 rede), ean, description?, promoPrice, regularPrice?, startsAt, endsAt}
PUT    /api/v1/merchant/promos/{id}      → MerchantPromoResponse
DELETE /api/v1/merchant/promos/{id}      → 204
POST   /api/v1/merchant/promos/import    → MerchantPromoImportResponse — multipart file (CSV ou XLSX, export do ERP; colunas pt: ean, preco[_promocional], preco_normal?, inicio, fim, descricao?) + ?cnpjRoot=
POST   /api/v1/merchant/promos/batch     → MerchantPromoImportResponse — body {promos: [MerchantPromoRequest]} (integração ERP/API) + ?cnpjRoot=
```

- **K-anonimato nos dois lados (price-comparison):** uma linha só aparece quando rede
  E região passam K=3 households distintos + mínimo de amostras. Nunca um preço sub-K.
- `deltaPercent` = (rede − região) / região em %, 1 casa; positivo = rede mais cara.
- **Promos**: validação por linha (EAN 8–14 dígitos, preço > 0, fim ≥ início, janela
  duplicada por rede+EAN → 409); import devolve `{received, imported, rejected,
  errors:[{line, ean, reasonKey, reason}]}` (reason já localizado). Writes exigem
  assinatura ativa → **402** `merchant.subscription.required` quando não há.
- **Assinatura (modal do portal do lojista)**: status `PROMO` + `freeUntil` = mostrar
  o modal "conta de marketing grátis até {freeUntil}" (promo de lançamento, 31/12/2026).
- `verifiedByReceipts` = cupons NFC-e reais da rede, dentro da janela, preço ≤ anunciado (±1%),
  vindos de pelo menos K casas distintas (um cupom só não verifica mais).

## 10h. Merchant claims (qualquer usuário autenticado)

Fluxo "sou este mercado" (docs/MERCHANT_ACCOUNTS.md §4b). Aprovação promove o
usuário a MERCHANT, concede a rede e abre a assinatura de marketing.

```
POST /api/v1/merchant-claims             → 201 MerchantClaimResponse — body {cnpj (14 díg.), companyName?, contactPhone?}
                                            → status AWAITING_CODE (código foi pro e-mail da EMPRESA registrado na Receita; e-mail volta mascarado)
                                            → ou PENDING_REVIEW (CNPJ sem e-mail utilizável → fila do admin)
GET  /api/v1/merchant-claims             → List<MerchantClaimResponse> (meus claims)
POST /api/v1/merchant-claims/{id}/verify → MerchantClaimResponse — body {code}; sucesso = APPROVED (virou MERCHANT + grant + assinatura)
```

- Código: 6 dígitos, TTL 24h, 5 tentativas; expirado/estourado fecha o claim
  (REJECTED, motivo `code_expired`/`code_attempts_exceeded`). Depois de um claim
  encerrado, um novo claim para a MESMA rede só é aceito após 24 h
  (`MERCHANT_CLAIM_COOLDOWN_HOURS`) → **429** `merchant.claim.cooldown` antes disso.
- 409 claim aberto duplicado ou rede já concedida; 400 código errado; 429 tentativas estouradas.
- Rate limit (por usuário E por IP — os dois baldes são cobrados): envio 3/hora,
  verificação 10/hora → 429 com `Retry-After`.

### Feed patrocinado (consumer — INERTE por flag)

```
GET /api/v1/price-index/sponsored-promos → List<SponsoredPromoResponse> {promoId, cnpjRoot, chainName, ean, productId, productName, description, promoPrice, regularPrice, startsAt, endsAt, verified}
```

- Devolve **[] até `MERCHANT_PROMOS_FEED_ENABLED=true`**. Endpoint separado do
  `/price-index/promos` orgânico DE PROPÓSITO: promo anunciada por lojista pagante
  nunca entra no ranking orgânico. FE renderiza SEMPRE com selo "Patrocinado";
  `verified` = cupons reais confirmam o preço.

---

## 11. Categorizer admin (rarely needed by the FE)

```
GET  /api/v1/categorizer/classify?description=Milho&description=Lays
                                       → dry-run of the cascade with the dictionary breakdown (dev)
POST /api/v1/categorizer/benchmark     → categorization accuracy % over the golden set (records a snapshot)   [ADMIN only]
GET  /api/v1/categorizer/quality/history?limit=50 → quality trend over time (snapshots)
GET  /api/v1/categorizer/live-accuracy → LiveAccuracyResponse { total, correct, accuracyPct, mismatches[] } [ADMIN only]
GET  /api/v1/categorizer/simulate?minTokens=3&maxTokens=6&sampleSize=2000 → PhraseTokenSimulationResponse — dry-run of the phrase-token limits over unmatched items + golden set [ADMIN only]
POST /api/v1/categorizer/auto-promote  → trigger learned-dictionary promotion  [ADMIN only]
POST /api/v1/categorizer/recategorize-taxonomy → { keywordUpdated, llmUpdated, eansScanned, catalogRowsEnriched, productsUpdated } — recategorize products into the 2-level taxonomy [ADMIN only]
GET  /api/v1/categorizer/recategorize-taxonomy/calibrate?sample=100 → { sample, keywordHigh, keywordLow, keywordNone, llmPlaced, agree, precisionPct, disagreements[] } [ADMIN only]
POST   /api/v1/categorizer/promote-consensus  → graduate user-correction consensus → learned dict [ADMIN only]
GET    /api/v1/categorizer/consensus         → list all CONSENSUS-graduated products             [ADMIN only]
GET    /api/v1/categorizer/consensus/audit?productId=&limit=50 → consensus-graduation audit rows { productId, category, householdIds, votes } [ADMIN only]
DELETE /api/v1/categorizer/learned           → clear all auto-promoted learned entries + reset in-memory dict [ADMIN only]
DELETE /api/v1/categorizer/consensus         → revert all CONSENSUS-graduated products to NONE  [ADMIN only]
POST   /api/v1/categorizer/dictionary/import  → bulk-upsert token mappings into learned dict       [ADMIN only]
POST   /api/v1/categorizer/dictionary/curated/import → bulk-upsert CURATED entries (highest tier), hot-reloaded [ADMIN only]. CuratedImportRequest aceita `categoryNodeSlug` opcional (folha fina → grava category_id; ausente usa o nó-grupo default do enum)
GET    /api/v1/categorizer/dictionary/curated?q=&page=&size= → Page<CuratedEntryResponse> — list/search curated entries [ADMIN only]
DELETE /api/v1/categorizer/dictionary/curated/{id} → 204 — delete one curated entry + hot-reload  [ADMIN only]
GET    /api/v1/categorizer/dictionary/learned?q=&page=&size= → Page<LearnedEntryResponse> — list/search learned entries [ADMIN only]
DELETE /api/v1/categorizer/dictionary/learned/{id} → 204 — delete one learned entry + hot-reload  [ADMIN only]
POST   /api/v1/categorizer/brands/import      → bulk-upsert brand-registry entries, hot-reloaded   [ADMIN only]
GET    /api/v1/categorizer/brands?q=&limit=20  → ["Tio João", …] — brand display names (autocomplete)            [ADMIN only]
GET    /api/v1/categorizer/brands/entries?q=&limit=50 → [{ id, normalizedKey, displayName, source }]           [ADMIN only]
DELETE /api/v1/categorizer/brands/{id}        → 204 — delete one brand-registry entry              [ADMIN only]
POST   /api/v1/categorizer/brands/promote-aliases → promote recurring brand aliases into the registry [ADMIN only]
POST   /api/v1/categorizer/brands/derive-from-catalog?minProducts=2&onlyBrazil=true → grow registry from EAN-catalog brands (no external calls) [ADMIN only]
POST   /api/v1/categorizer/benchmark/import   → bulk-upsert golden-set rows for the benchmark      [ADMIN only]
POST   /api/v1/categorizer/ean-catalog/import → bulk-seed EAN→category catalog (pre-mapped rows)   [ADMIN only]
POST   /api/v1/categorizer/ean-catalog/import-off → bulk-seed from RAW Open Food Facts rows (server maps tags) [ADMIN only]
GET    /api/v1/admin/products/brand-coverage    → dry-run brand-extraction coverage over the base  [ADMIN only]
GET    /api/v1/admin/products/unmatched-report?topN=30 → item→product matching KPI + worst orphans [ADMIN only]

# ── AI assist layer — /api/v1/categorizer/ai/** (ALL ADMIN only: spends money / mutates global state) ──
GET    /api/v1/categorizer/ai/status           → { enabled, extractorModel, curatorModel, pendingQueue{itensNaoCasados, produtosSemMarca, produtosSemNome, produtosOutros}, lastSweep{…} }
POST   /api/v1/categorizer/ai/sweep?full=false → starts the async sweep (full=true walks the WHOLE queue — historical backfill)
GET    /api/v1/categorizer/ai/findings?status=PENDING&type=&page=0&size=30&sort= → Page<AiFinding> (review queue; `type=PRODUCT_CORRECTION` = a user's "corrigir item" proposal — it already applies to that user's household; approve applies it to the GLOBAL product, reject leaves the catalog as it was)
POST   /api/v1/categorizer/ai/findings/{id}/approve → AiFinding — optional JSON body with field overrides
POST   /api/v1/categorizer/ai/findings/{id}/reject  → AiFinding
GET    /api/v1/categorizer/ai/findings/{id}/ufs     → ["RS", …] — UFs where the finding's product was scanned
POST   /api/v1/categorizer/ai/findings/approve-bulk → { approved, failed } — body: [<findingId>, …]
POST   /api/v1/categorizer/ai/findings/approve-subcategories-above?threshold=0.9 → { approved, failed }
POST   /api/v1/categorizer/ai/findings/re-enrich    → re-enriches pending findings
POST   /api/v1/categorizer/ai/suggest-subcategories?limit=120 → 202 — background sweep filing one PENDING subcategory suggestion per product
POST   /api/v1/categorizer/ai/reprocess-fallback?limit=10 → { receipts, matched, alreadyMatched, parseFailed, blankName, gone, callError } (diagnostic)
GET    /api/v1/categorizer/ai/usage?days=30    → spend panel { days, totalCalls, totalInputTokens, totalOutputTokens, totalCostUsd, allTimeCostUsd, budgetUsd, estimatedBalanceUsd, byActivity[], byDay[] }
GET    /api/v1/categorizer/ai/model-comparison?days=30 → [{ model, provider, calls, costUsd, avgCostPerCallUsd, successRatePct, avgLatencyMs, findingsCreated/Approved/Rejected, acceptanceRatePct, avgConfidence }] (days=0 → all time)
POST   /api/v1/categorizer/ai/benchmark-model?model=&sample= → starts an async accuracy benchmark of one model against the golden set
GET    /api/v1/categorizer/ai/benchmark-model/runs → benchmark run history
GET    /api/v1/categorizer/ai/providers        → current provider/model per tier + option catalog + which providers have credentials
PUT    /api/v1/categorizer/ai/providers/{tier} → body { provider, model } — switch a tier's provider/model, effective immediately
GET    /api/v1/categorizer/ai/classify?description= → LLM proposal for ONE description (admin "Testar" screen; costs one small call)
```

> The **catalog-mutating** endpoints (`auto-promote`, `promote-consensus`, `recategorize-taxonomy`, `learned`, `consensus`, every `*/import`, the `brands/*` writes, and `benchmark` — it records a snapshot), the dictionary/brand/consensus listings, `simulate`, `live-accuracy`, `recategorize-taxonomy/calibrate` and everything under `/categorizer/ai/**` require `Role.ADMIN` — a normal user gets `403`. Only the read/debug GETs `classify` and `quality/history` remain open to any authenticated user.

> Since 2026-07-02 the curated dictionary, brand registry and benchmark golden set live in **DB tables** (formerly classpath CSVs) — the three new imports make them editable at runtime, no deploy needed. Bodies: curated `[{keyword, genericName, category}]`, brands `[{key, displayName}]`, benchmark `[{description, expectedCategory, expectedBrand?, expectedPackSize?, expectedPackUnit?}]`. All are upserts returning `{imported, skipped}`.

**`/promote-consensus`** — turns user category corrections into deterministic knowledge: products corrected by ≥N distinct households (consensus) get their global category set (source `CONSENSUS`), and recurring agreed tokens enter the learned dictionary. Returns `{ productsGraduated, tokensLearned, learnedTotal }`. Runs daily automatically; this is the manual trigger.

**`GET /consensus`** — returns all products the consensus job graduated: `[{ id, ean, normalizedName, genericName, brand, category }]`. Use to review what was auto-approved before deciding to keep or revert. Read-only.

**`DELETE /learned`** — wipes every auto-promoted entry from the `learned_dictionary_entry` table and resets the in-memory dictionary to just the curated tier. Returns `{ removedEntries }`. Use to restore a clean baseline if the learned dict drifts or is corrupted.

**`DELETE /consensus`** — reverts all products whose category was set by consensus promotion (source `CONSENSUS`) back to `NONE` / null category, so they re-enter the classification cascade on the next request. Returns `{ revertedProducts }`. Does **not** touch the learned dictionary — run `DELETE /learned` too if you want a full reset.

**`POST /dictionary/import`** — bulk-upserts a list of `{ token, genericName, category, sampleCount? }` into the learned dictionary and swaps the in-memory reference immediately. `sampleCount` defaults to 999 when omitted. Returns `{ imported, skipped }`. Use to pre-seed large volumes of known token→category mappings without ingesting receipts.

**`POST /ean-catalog/import`** — bulk-seeds the EAN catalog (step A2 in the canonicalization cascade). Body: `[{ ean, genericName?, brand?, category?, source }]`. `source` accepts `OPEN_FOOD_FACTS`, `CURATED_IMPORT`, or `USER_CONFIRMED`. Upserts by EAN — re-importing the same EAN updates it. Returns `{ imported, skipped }`. Designed to receive Open Food Facts Brazil dump data; entries with blank/null EAN are skipped. Category and brand from the catalog enrich newly created products but never overwrite data already extracted from the receipt description.

**`/benchmark` (quality metric)** — runs the cascade over `seed/categorization-benchmark.csv` (curated description → true category/brand/quantity) and returns per-field accuracy: `{ total, correct, accuracyPct (category), wrong, uncategorized, brandChecked, brandCorrect, brandAccuracyPct, quantityChecked, quantityCorrect, quantityAccuracyPct, failures:[{description, field, expected, got, source}] }`. Brand/quantity are scored only on golden rows that declare them. Each call records a snapshot. (The trained-ML classifier was retired — the `mlCategory*` fields no longer exist.)

**`/quality/history`** — the trend: snapshots (newest first) from benchmark runs + backfills. Each: `{ recordedAt, trigger, accuracyPct, benchmarkCorrect, benchmarkTotal, catalogProducts, catalogCategorized, catalogCoveragePct, brandAccuracyPct, quantityAccuracyPct }`.

Mostly for ops. Categorization runs automatically on receipt confirm.

**`/classify` (debug)** — repeat `description` for multiple terms. Each result:
```json
{
  "input": "Batata Frita",
  "category": "PRODUCE", "genericName": "Batata", "brand": null,
  "packSize": null, "packUnit": null,
  "source": "DICTIONARY",
  "dictionary": { "genericName": "Batata", "category": "PRODUCE", "source": "DICTIONARY" }
}
```
`source` says which layer won (a `CategorizationSource`, e.g. `DICTIONARY`/`LEARNED_DICTIONARY`/`NONE`). The trained-ML layer was retired (`ML` is a tombstone value no product carries), so the old `ml*` fields and the `/ml/predict`, `/status` and `/retrain` endpoints no longer exist. For an LLM opinion on one description use `GET /categorizer/ai/classify` (ADMIN).

---

## 12. Legal

```
GET /api/v1/legal/terms             → { "version": "1.1", "content": "...", "updatedAt": "..." }
GET /api/v1/legal/privacy-policy    → same shape (alias: /api/v1/legal/privacy)
```

Both are public (no auth). Show before register; pass the returned `version` in
the register request. Current version of both documents: **1.1** (since
2026-11-11) — a register call still sending `"1.0"` is rejected with 400, so always
send the version read from these endpoints, never a hardcoded one.

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
| Receipt uploads (`POST /receipts`) — `RECEIPT_UPLOAD_UNLIMITED` | 5 / calendar month | unlimited | Over the cap → **402**. Counts ALL statuses of receipts the user SUBMITTED (reject/resubmit can't game it); copies created by a household split are not usage. |
| History window (`/insights/*`, `/items`) — `HISTORY_FULL_RANGE` | last 90 days | full range | `from` older than 90d (or omitted) is silently clamped. No error — just windowed. |
| Notification delivery — `PUSH_AND_EMAIL_DELIVERY` | in-app inbox only | inbox + push/email | FREE inbox row is always saved (`delivered=false`, `failureReason=free_tier_inbox_only`); push/email skipped. **Transactional notifications are never held back, whatever the plan:** `RECEIPT_READY` / `RECEIPT_FAILED` / `RECEIPT_RECOVERED`, `HOUSEHOLD_MEMBER_JOINED` / `HOUSEHOLD_MEMBER_LEFT`, and `SYSTEM` notices not sent by an admin campaign (account, billing, consent). |
| Basket optimization (`POST /shopping-list/optimize`) | — | ✅ | FREE → **402**. |
| History export (`GET /receipts/export`) | — | ✅ | FREE → **402** (`CSV_EXPORT`). |
| Receipt by photo (`POST /receipts/items-photo`) | — | ✅ | FREE → **402** (`PHOTO_EXTRACTION`); also counts toward the monthly receipt cap. |
| Household size (join) | 2 people | 6 people | One PRO member lifts the whole household to the max. Over the cap → localized `household.full.free` / `household.full.max`. The absolute max (6) applies even with enforcement off. |

The gate is **per feature** (`Feature` enum: `WATCHED_MARKETS_UNLIMITED`,
`HISTORY_FULL_RANGE`, `PUSH_AND_EMAIL_DELIVERY`, `RECEIPT_UPLOAD_UNLIMITED`,
`BASKET_OPTIMIZATION`, `CSV_EXPORT`, `PHOTO_EXTRACTION`). With enforcement on, the
server can still leave chosen features open to FREE
(`SUBSCRIPTION_FREE_OPEN_FEATURES`, comma-separated feature names) so paywalls can be
switched on one at a time — so don't assume "FREE ⇒ every row above is blocked";
react to the 402 of each call.

**402 response** (Payment Required) — distinct from 403 (forbidden). Standard
error shape; the message resolves `subscription.upgrade_required` with the
gated feature name as an argument, e.g. *"This feature (BASKET_OPTIMIZATION)
requires a PRO subscription."* Treat 402 as "show the upgrade prompt".

**Admin set-tier (ops / promos / testing):**
`PUT /api/v1/admin/users/{id}/subscription-tier` — body `{ "tier": "PRO" }`
or `{ "tier": "FREE" }`. ADMIN-only. Returns the admin user detail. PRO
activates a manual subscription; FREE cancels it right away.

**Billing webhooks** are provider-specific and server-to-server only (the FE never
calls them): RevenueCat below for the apps, Mercado Pago (`POST /webhooks/mercadopago`,
§1 "Subscription checkout") for the web. The generic `POST /webhooks/subscription`
seam was removed on 2026-11-11.

**Subscription lifecycle (what the app can rely on):**
- The **launch promo** is granted once per user, on their first confirmed receipt,
  until the fixed promo end date. It never replaces a paid subscription, an open-ended
  grant or a period that already ends later.
- Promo holders without a paid subscription get an inbox + push warning 15, 7, 3 and
  1 day before the promo ends (`SYSTEM`, `payload.kind = "LAUNCH_PROMO_ENDING"`).
- **Cancelling keeps access until the end of the paid period** (`cancelAtPeriodEnd`).
  A paid, still-renewing subscription whose renewal is late gets 3 days of grace
  before the downgrade.
- A paid subscription that ends while the promo is still running falls back to the promo.
- Referral rewards are separate Premium windows that queue after the current period
  (`rewardPremiumUntil`).

**RevenueCat webhook (mobile IAP — Apple StoreKit + Google Play):**
`POST /api/v1/webhooks/revenuecat` — public route, server-to-server from
RevenueCat. Authenticated by a fixed `Authorization` header configured in the
RevenueCat dashboard, checked against
`economizaai.billing.revenuecat.auth-header` (**fail-closed**: config unset →
every call 401). Body is the standard RevenueCat event envelope; we read only:
```json
{ "event": { "type": "INITIAL_PURCHASE", "app_user_id": "<our user UUID or email>",
  "expiration_at_ms": 1798761600000, "product_id": "economizai_pro_monthly", "id": "evt_..." } }
```
Purchase/renewal-type events grant PRO until `expiration_at_ms`. `CANCELLATION`
(auto-renew off) and `SUBSCRIPTION_PAUSED` (a pause was scheduled) only mark the
subscription non-renewing — access lasts until the period ends. `EXPIRATION` ends
it: back to the launch promo when the user still has it, FREE otherwise. Always 200
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
- **Rate limiting.** `POST /auth/login` is capped at **15 req/min**,
  `POST /auth/refresh` at **60 req/min**, account creation / social sign-in
  (`POST /auth/register`, `/auth/google`, `/auth/apple`) share **20 req/min**,
  every other `POST /auth/*` at **5 req/min**; receipt submits (`POST /receipts`,
  `/receipts/photo`, `/receipts/chave/photo`, `/receipts/prefetched`,
  `/receipts/items-photo`, `/receipts/xml`, `/receipts/retry`,
  `/receipts/{id}/retry`, `/receipts/{id}/device-content`) share **30 req/hour**.
  Pageable endpoints clamp `size` to **100**. Also capped: bulk import (5/hour), `extract-chaves`
  (30/hour), exports (10/hour), `households/join` + invite preview (10/hour),
  phone OTP (10/hour), `email-verification/resend` (3/hour), `/contact` and
  `/beta-signup` (5/hour each), `/visits` and `/app-config` (60/min).
  New on 2026-11-11: profile-picture upload (`POST /users/me/profile-picture`,
  **10/hour**), e-mail change request (`POST /users/me/email-change`, **3/hour**)
  and confirm (`…/email-change/confirm`, **15/hour**), referral redeem
  (`POST /referrals/redeem`, **10/hour**), merchant claim submit
  (`POST /merchant-claims`, **3/hour**) and verify
  (`POST /merchant-claims/{id}/verify`, **10/hour**). Auth and public routes are
  keyed by IP, authenticated routes by user; the two merchant-claim buckets are
  charged per user AND per IP (the request passes only when both still have a token).
  Over-quota responses are `429 Too Many Requests` with a
  `Retry-After: <seconds>` header and a translated message body. Successful
  responses on rate-limited routes carry `X-RateLimit-Remaining` so the FE
  can warn the user before they hit the wall. Other routes are uncapped.
- **Overload.** `503` + `Retry-After: 5` (`error.overloaded`) means the server is
  saturated, not that the request was wrong or the session ended — retry after the
  delay (§3 "Error paths").
- **E-mails are case-insensitive** (trimmed + lower-cased server-side) on every auth
  and account endpoint.

---

## Postman collection

Full request library + sequential E2E flow at
`postman/economizai.postman_collection.json`. Set the `baseUrl` collection
variable to your environment, optionally set `qrPayload` to a real NFC-e for
the receipt steps.
