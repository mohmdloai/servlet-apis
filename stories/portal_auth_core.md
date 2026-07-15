# Slice P1: Portal auth core — passwordless OTP login + isolated customer sessions

> The keystone of the customer-portal epic. Establishes the **second authentication plane**: a customer
> can prove they own an email (6-digit OTP) and receive an **isolated, rotating session** usable only on
> `/api/portal/*` — never on the staff/admin API. Also **hardens the existing staff auth** by adding the
> missing token-audience separation.
>
> Canonical decisions: [`frontst/docs/customer-portal-epic.md`](../../frontst/docs/customer-portal-epic.md)
> (the two-plane model + §1–§13). Mirrors — never merges into — the staff spine (`JwtAuthFilter`,
> `AuthService`, `RefreshTokenStore`, `AuthCookies`). Feeds frontend story 34.

---

## Goal

An existing customer of a store logs in with an emailed 6-digit code and gets a session:
```
POST /api/public/{orgSlug}/portal/request-code   {email}            → 200 {sent:true}   (always, no oracle)
POST /api/public/{orgSlug}/portal/verify-code     {email, code}      → 200 + Set-Cookie (customer_access, customer_refresh) + {customer}
POST /api/portal/auth/refresh                                        → 200 rotate         (customer_refresh cookie)
POST /api/portal/auth/logout | /logout-all                          → 204
GET  /api/portal/auth/sessions                                      → device list
GET  /api/portal/me                                                 → {name, email, phone, address, email_verified}
PATCH /api/portal/me                                                 {name?, phone?, address?} → updated profile
```
A customer token is **rejected 401 on `/api/orgs/**`** and a staff token is **rejected 401 on
`/api/portal/**`** — the boundary is the deliverable, proven by test.

## Why passwordless OTP + a separate plane (not a customer row in `app_user`, not shared JWT)

- Customers already prove email ownership at every order; a short-lived code re-proves it with **no
  password to store, breach, or reset** (the standing product decision).
- A customer is a **CRM row**, not staff — folding them into `app_user`/`OrgRole` would blur the
  authorization model and risk a customer reaching an org endpoint. A **separate key + `aud` + path +
  filter** makes cross-plane use structurally impossible, not merely policy-checked.
- The Redis session machinery (rotation, reuse-theft, per-device kill, fail-closed version) is already
  correct — we **instantiate a second copy** under its own namespace, not extend the staff one.

## Design

### Migration (next `V##`)
`ALTER TABLE customer ADD COLUMN email_verified_at TIMESTAMPTZ;` (nullable; stamped on first OTP
success). jOOQ codegen. No other schema — the OTP challenge and sessions live in Redis (self-expiring).

### Identity
- `ActorType.CUSTOMER` (re-added); `ActorContext.customer(orgId, customerId)`;
  `SecurityContext.isCustomer()`; a customer `SecurityContext` carries `actorId=customerId`,
  `actorType=CUSTOMER`, `org_id`, empty roles.
- **Second `JwtUtil`** (`customerJwtUtil`) built from `CUSTOMER_JWT_SECRET` (≥32B check reused). Customer
  access token claims: `sub=customer_id`, `actor_type=CUSTOMER`, `aud="customer"`, `org_id`,
  `token_version`, `fam`, `iat`, `exp` (15-min). **No** `org_roles`/`system_roles`/`allowed_actions`.
- **Staff hardening:** `staffJwtUtil` now stamps `aud="staff"`; `JwtAuthFilter` accepts `staff` or
  **missing** (grace for live sessions), rejects `customer`.

### OTP challenge — `CustomerOtpStore` (Redis)
- `request(orgId, email)`: normalize email (`trim().toLowerCase()`, single-address validation — reuse
  `EmailAddresses.isSingleValid`); look up `customer` by `(org_id, email)`. **Always** return "sent". If
  the customer exists: generate a 6-digit `SecureRandom` code, store `portal:otp:{orgId}:{sha256(email)}`
  → JSON `{codeHash: sha256(code), attempts:0, exp}` with **10-min TTL**, and send the code email.
  **Implemented decision (product owner, 2026-07-14):** the code is sent **synchronously through
  `EmailSender`, bypassing `NotificationService`/preferences** — a login code is a transactional
  security message that must not be suppressible by a customer's marketing/email opt-out (the one-click
  unsubscribe would otherwise lock them out) nor delayed by the delivery sweeper. A send failure is
  swallowed (logged, never thrown, no address logged) so the response stays the uniform `200 {sent:true}`
  — a `5xx` on a real address would be an enumeration oracle. (Supersedes the earlier
  `NotificationService.notify(CUSTOMER, PORTAL_LOGIN_CODE)` sketch; no `PORTAL_LOGIN_CODE` notification
  type ships.)
- `verify(orgId, email, code)`: load challenge (missing/expired → generic fail); `attempts++`; if
  `attempts > 5` → delete challenge (must re-request); **constant-time** compare `sha256(code)` to stored;
  on match → delete challenge, return the resolved `customerId`.

### Sessions — `CustomerSessionStore` (Redis, mirrors `RefreshTokenStore`)
Keys `crt:{hash}`, `crt:fam:{fam}`, `crt:user:{orgId}:{custId}`, `crt:revoked-fam:{fam}`,
`cust:ver:{orgId}:{custId}` (see epic §5). `hashToken` reused (SHA-256). Family rotation on refresh;
presenting a revoked hash → 401 (reuse-theft); `logout` denies the family's access + revokes the token;
`logout-all` bumps `cust:ver` and revokes all families; `token_version` warm-path is **fail-closed on
Redis miss** (same as staff — must be primed at verify-time).

### `CustomerAuthService`
`requestCode` · `verifyCode` (→ mint session: new `fam`, access + raw refresh, cache version) ·
`refresh` (rotate) · `logout` · `logoutAll` · `listSessions`. Returns raw token strings; the servlet
writes cookies.

### API surface
- **Anon bootstrap** on `PublicStorefrontServlet` (org resolved from `{orgSlug}`, already JWT-bypassed +
  rate-limited): `POST /{orgSlug}/portal/request-code`, `POST /{orgSlug}/portal/verify-code` (verify sets
  the two portal cookies via `CustomerAuthCookies`).
- **`CustomerAuthFilter`** (new) on `/api/portal/*`: extracts `customer_access` cookie → `customerJwtUtil.
  parseAndVerify` → require `aud=customer` → `isCustomerTokenVersionValid` (fail-closed) → `fam` denylist
  → build customer `SecurityContext`. **Bypass** `/api/portal/auth/refresh` (needs only the refresh cookie)
  and `/api/portal/auth/logout`. `JwtAuthFilter` adds `/api/portal/` to its early-return bypass.
- **`PortalServlet`** on `/api/portal/*`: `/auth/refresh`, `/auth/logout`, `/auth/logout-all`,
  `/auth/sessions`, `GET|PATCH /me`. `CustomerAuthCookies` mirrors `AuthCookies` (names/paths per epic §6).
- **Rate limiting:** `RateLimitFilter` gains `rl:portal-otp-req` + `rl:portal-otp-verify` (per-IP **and**
  per-`sha256(email)`), `rl:portal-refresh`; coverage extended to `/api/portal/*` and the bootstrap path.
- **CSRF:** portal endpoints require `X-Portal-Request: 1`; mutations additionally check
  `Origin`/`Referer` against `CORS_ALLOWED_ORIGINS`.
- **Wiring:** `AppConfig` builds `customerJwtUtil`, `CustomerOtpStore`, `CustomerSessionStore`,
  `CustomerAuthService`, `CustomerPortalService`; `EmbeddedTomcatLauncher` registers `CustomerAuthFilter`
  + `PortalServlet` (filter order Cors → RateLimit → CustomerAuth on `/api/portal/*`).

## Scope

### In
The migration; `CUSTOMER` actor + `isCustomer()`; `customerJwtUtil` + staff `aud` hardening;
`CustomerOtpStore` + `CustomerSessionStore` + `CustomerAuthService`; `CustomerAuthFilter` + `JwtAuthFilter`
bypass; bootstrap request/verify + `PortalServlet /auth/*` + `GET|PATCH /me`; rate-limit buckets; CSRF
guard; new env; `PORTAL_LOGIN_CODE` notification type + email template.

### Out (deferred)
Order/invoice/address/reorder reads (P2–P4); passkeys / WebAuthn; email-change re-verification flow
(PATCH `/me` excludes email); double-submit CSRF token; SMS OTP; account deletion.

## Authorization
Bootstrap = anonymous (org-by-slug, rate-limited). Everything under `/api/portal/*` = a valid **customer**
session (`aud=customer`), scoped to its own `(org_id, customer_id)`. **No** staff role is ever accepted
on the portal; **no** customer token is ever accepted on the staff plane.

## Acceptance criteria
1. **Happy path:** `request-code` for a real `(org,email)` emails a 6-digit code (visible in
   `LoggingEmailSender`); `verify-code` with it sets `customer_access` + `customer_refresh` (HttpOnly,
   `SameSite=Strict`, correct `Path`s) and returns the customer; `GET /api/portal/me` works with the
   cookie; `email_verified_at` is stamped.
2. **Enumeration-safe:** `request-code` for an unknown email returns the **identical** `200 {sent:true}`
   and sends nothing; `verify-code` failures are one generic error.
3. **Brute-force guard:** a 6th wrong `verify-code` invalidates the challenge (must re-request); a wrong
   code never succeeds; an expired (>10 min) code fails.
4. **Session security:** refresh rotates the token (old hash → 401 on reuse); `logout` kills that device's
   access immediately; `logout-all` invalidates every session (version bump); a tampered/expired access
   token → 401.
5. **Plane isolation (the headline):** a `customer_access` token on `GET /api/orgs/{orgId}/...` → **401**;
   a staff `access_token` on `GET /api/portal/me` → **401**; a customer token missing `aud`/with
   `aud=staff` → 401 on the portal.
6. **Scoping:** `/me` and its PATCH read/write only the session's own `(org_id, customer_id)`; a customer
   of org A can never touch org B (no path/body customer id is trusted).
7. **CSRF:** a portal call without `X-Portal-Request` → 400/403; a mutation with a foreign `Origin` → 403.
8. **Rate limits:** exceeding `rl:portal-otp-req` / `rl:portal-otp-verify` → 429.

## Tests
- `PortalAuthIT`: AC 1–3 (OTP loop, enumeration-uniform, lockout/expiry), AC 4 (rotation + reuse-revoke +
  logout-all + per-device kill), AC 6 (scoping), AC 7 (CSRF header/Origin), AC 8 (429).
- `PlaneIsolationIT`: AC 5 both directions (customer↔staff), incl. `aud` mismatch and missing-`aud` grace
  for staff.
- Unit: `CustomerOtpStore` (constant-time compare, attempt cap, TTL), `customerJwtUtil` (`aud`, no roles),
  `JwtAuthFilter` `aud` acceptance matrix.
- Verified live through Tomcat with `LoggingEmailSender`: full request→verify→`/me`→refresh→logout, plus
  the two cross-plane 401s by `curl`.

## What this unblocks
| Next | Depends on this |
|---|---|
| **P2 orders**, **P3 invoices**, **P4 addresses/reorder** | the customer `SecurityContext` + `/api/portal` plane |
| **Frontend story 34** (login + session gate + profile) | the bootstrap + `/me` + cookies |
| Passkeys / WebAuthn (later) | a customer identity to bind a credential to |
