# 11 — Story: Self-service auth — registration, password recovery, invite activation

> **Status: SHIPPED (backend).** Built 2026-07-07. Closes the two account-lifecycle gaps that the
> **platform admin console** (frontend `11_st_platform_admin_console.md`) flagged as *"not started"*:
> **registration / sign-up** and **forgot-password (self-service)** — plus the **invite/activation**
> bridge that makes the already-shipped org **provisioning** (`POST /api/admin/orgs`) actually usable
> (a provisioned owner gets an unusable password and, until now, no way to set one). Verified
> end-to-end against real PostgreSQL + Redis (`AccountServiceIT`, 12 tests) and via the provisioning
> path (`PlatformOrgServiceIT`, +1 invite-token assertion), and probed live against the running
> server. Product decisions are folded in-line and flagged **[DECISION]**.
>
> Endpoints to build against (4 + 1 prerequisite migration + 1 provisioning hook):
> - **V49** — `CREATE TABLE app_user_magic_token` (single-use, hashed, TTL'd credential tokens)
> - `POST /api/auth/register` — open self-service sign-up (+ optional first org, caller = OWNER)
> - `POST /api/auth/forgot-password` — request a reset link (always opaque; no account enumeration)
> - `POST /api/auth/reset-password` — set a new password from a reset token, then log in
> - `POST /api/auth/activate` — set a first password from an invite token, activate, then log in
> - **Provisioning hook** — `PlatformOrgService.provision` now mints + emails an INVITE for a minted owner

---

## The gap (verified against code, 2026-07-07)

| # | Surface it blocks | What was missing | Evidence (pre-change) |
|---|---|---|---|
| 1 | Sign-up form | No way to create an account without a platform ADMIN. `AuthServlet` accepted only `login`/`refresh`/`logout`/`logout-all`/`stop-impersonating`; account creation was `POST /api/admin/users` (`requireAdmin`) or provisioning (`requireAdmin`) | `AuthServlet.doPost` switch; `UserAdminHandler` |
| 2 | Forgot-password link | No unauthenticated recovery. `POST /api/me/password` needs a live session + current password; `POST /api/admin/users/{id}/reset-password` is ADMIN-only. No reset-token table, no email-a-link path | `AuthService` had `login`/`changePassword` only; no token store for `app_user` |
| 3 | Provisioned-owner first login | `PlatformOrgService.provision` mints an owner with an **unusable** password (`hash("!" + randomUUID())`) but nothing let that owner set a real one — an admin had to `reset-password` for them by hand | `PlatformOrgService.provision`; its own doc said "awaiting a reset/invite" that did not exist |

**Why now.** Provisioning (PG1) shipped in the platform-admin work but was *inert* for a fresh owner
— the keystone (an invite/reset flow) was the missing half. Registration and forgot-password are the
same primitive (a single-use emailed token that sets a password), so all three land together.

---

## The primitive — V49 `app_user_magic_token`

**Intent.** One mechanism for every "prove you own this email, then set a password" flow, mirroring
the customer-scoped `customer_magic_token` (V45) but for `app_user`. A leaked or DB-exfiltrated token
must be worthless.

| Column | Notes |
|---|---|
| `id` | UUID PK |
| `user_id` | FK → `app_user(id)` **ON DELETE CASCADE** |
| `token_hash` | `VARCHAR(64)` UNIQUE — **SHA-256 hex**; the raw token lives only in the emailed URL |
| `purpose` | `TEXT` CHECK ∈ (`PASSWORD_RESET`, `INVITE`) |
| `expires_at` | `TIMESTAMPTZ` |
| `consumed_at` | `TIMESTAMPTZ` — stamped on redeem; a used token never resolves again |
| `created_at` | `TIMESTAMPTZ` |

Indexes: partial lookup `(token_hash) WHERE consumed_at IS NULL` (hot validate path); `(expires_at)`
for an eventual purge job.

**Guarantees.**
- **Full entropy, hashed at rest** — 256-bit `SecureRandom`, URL-safe base64; only the SHA-256 hash
  is stored (reuses `RefreshTokenStore.hashToken`). A DB read never yields a usable token.
- **Genuinely single-use** — redeem is a compare-and-swap: `UPDATE … SET consumed_at = now WHERE
  token_hash = ? AND purpose = ? AND consumed_at IS NULL AND expires_at > now RETURNING user_id`. Two
  concurrent redeems of one link race on the row; exactly one wins. (Unlike the customer `VIEW_ORDER`
  link, which is deliberately multi-use.)
- **Purpose-bound** — a `PASSWORD_RESET` token is rejected at `/activate` and vice-versa (the redeem
  filters on `purpose`).
- **Short-lived** — `PASSWORD_RESET` TTL default **120 min** (`PASSWORD_RESET_TTL_MINUTES`); `INVITE`
  TTL default **7 days** (`INVITE_TTL_DAYS`).

Service surface: `CredentialTokenService.mint(txDsl, userId, purpose, now)` /
`mintAutonomous(...)` / `consume(txDsl, purpose, rawToken, now) → Optional<UUID>` plus URL builders
`resetUrl(raw)` / `activateUrl(raw)` off `PUBLIC_BASE_URL`.

---

## 1. `POST /api/auth/register` — open self-service sign-up

**Intent.** Let a new user create their own account (and, optionally, their first org) without an
operator. **[DECISION] Open public sign-up** was chosen over invite-only — it departs from the
otherwise provisioned-only model, so the abuse controls in **Out (deferred)** are the follow-up.

**Contract.** Body `{email, password, org_name?}`; anonymous.
- Validates: `email` a single valid address (`EmailAddresses.isSingleValid`); `password` ≥ 8; when
  `org_name` is present, it passes `OrgService.validateName`.
- One transaction: `409` if the email is already registered; else insert a `USER` (active, bcrypt
  hash, `token_version = 0`). If `org_name` is given, derive a **unique slug** from the name (retry
  with a short random suffix on collision so a taken name never blocks a registrant), insert the org,
  and grant the registrant **OWNER**.
- **Auto-login**: on commit, issues a device session and returns **`201`** with `access_token` +
  `refresh_token` cookies and the standard `AuthResponse {expires_in, user_id, actor_type}`.
- Errors: `400` invalid email / weak password / bad org name; `409` duplicate email.

## 2. `POST /api/auth/forgot-password` — request a reset link

**Intent.** Begin recovery **without leaking whether an email is registered**.

**Contract.** Body `{email}`; anonymous. **Always `200`** with an opaque message
(`"If an account exists for that email, a reset link has been sent."`). Only an existing, **active**
account has a `PASSWORD_RESET` token minted and a link emailed; unknown/disabled accounts mint
nothing and send nothing. Internal failures are swallowed (logged) so the response is uniform — no
timing/status oracle.

## 3. `POST /api/auth/reset-password` — set a new password from a reset token

**Intent.** Complete recovery, and **evict any attacker** who may already hold a session.

**Contract.** Body `{token, new_password}`; anonymous. One transaction: **consume** the
`PASSWORD_RESET` token (`400 "invalid or expired token"` on miss/expiry/reuse/wrong-purpose), reject
a disabled account, set the new bcrypt hash, and **bump `token_version`**. After commit,
`propagateLogoutAll` **revokes every prior session**, then a fresh session is issued for this device
— returns **`200`** + cookies + `AuthResponse`. `new_password` ≥ 8.

## 4. `POST /api/auth/activate` — set a first password from an invite token

**Intent.** The provisioning keystone — a provisioned/invited owner turns an unusable password into a
real one and gets in.

**Contract.** Body `{token, new_password}`; anonymous. Same shape as reset, with two differences:
consumes an **`INVITE`** token, and **activates** the account (`active = true`) if it was not already
— then logs in. Returns **`200`** + cookies. `new_password` ≥ 8.

## 5. Provisioning hook — invite on `POST /api/admin/orgs`

**Intent.** Make the shipped provisioning flow self-completing.

**Contract.** `PlatformOrgService.provision`, when it **mints** a new owner, now mints an `INVITE`
token **in the same transaction** (a rolled-back provision leaves no orphan token) and, after commit,
emails an activation link (`activateUrl`) **best-effort** — a delivery failure never fails the
provision. An **attached** existing owner (who already has a working password) gets **no** invite.

---

## Transport & delivery intent

- **Anonymous, but rate-limited.** The four endpoints are added to the `JwtAuthFilter` allowlist
  (alongside `login`/`refresh`), so they skip auth — but they stay under the `rateLimitFilter` mapped
  on `/api/auth/*`, which throttles the unauthenticated surface for free.
- **Auth email ≠ customer notification.** `AuthMailer` sends reset/invite mail **directly** via
  `EmailSender`, bypassing the customer-scoped, opt-out notification pipeline: an auth mail targets an
  `app_user`, must never carry an unsubscribe link, and must not be suppressible by preferences.
  Sending is best-effort and never throws (so forgot-password stays opaque). Org names are
  HTML-escaped in the invite body.
- **Links point at the frontend.** Emails carry `{PUBLIC_BASE_URL}/reset-password?token=…` and
  `/activate?token=…` — frontend routes that POST the token back to §3/§4. The pages are the
  frontend's side (this story is backend-only).
- **Cookies unchanged.** `HttpOnly`, `SameSite=Strict`, access cookie at `/`, refresh scoped to
  `/api/auth` — identical to `login` (shared `AuthService.issueSession` / `AuthCookies`).

## Authorization summary

| Route | Auth | Notes |
|---|---|---|
| `POST /api/auth/register` | anonymous | rate-limited; 409 on duplicate email |
| `POST /api/auth/forgot-password` | anonymous | always 200 (no enumeration) |
| `POST /api/auth/reset-password` | anonymous | token is the capability; bumps token_version |
| `POST /api/auth/activate` | anonymous | token is the capability; activates + bumps token_version |
| `POST /api/admin/orgs` (invite side-effect) | `requireAdmin` | unchanged authz; now also emails a minted owner |

## Scope

### In
- V49 token table + `CredentialTokenService` (mint/consume, hashed, single-use, TTL by purpose).
- The four `/api/auth/*` endpoints + DTOs + `JwtAuthFilter` allowlist + `AppConfig` wiring.
- `AuthMailer` (direct transactional auth email) + reset/invite templates.
- `AuthService.issueSession` extracted from `login` and reused for auto-login.
- Provisioning invite hook.

### Out (deferred)
- **Sign-up abuse controls** — email-verification-before-active, CAPTCHA/proof-of-work, per-IP
  sign-up quotas, disposable-domain blocking. Open registration ships without these; they are the
  natural next slice now that `[DECISION]` opened public sign-up.
- **Admin-created-user invites** — `POST /api/admin/users` with a blank password could reuse the same
  INVITE mechanism; only provisioning is wired today.
- **Token purge job** — the `(expires_at)` index exists; a recurring cleanup of expired/consumed rows
  is not yet scheduled.
- **Frontend pages** — `/reset-password` and `/activate` screens live in `frontst`.

## Tests

`AccountServiceIT` (Testcontainers PG + Redis, 12):
- register: creates user + logs in; with `org_name` grants OWNER; duplicate email → 409; weak
  password → 400; invalid email → 400.
- forgot → reset: emails a link, resets the password, new password logs in **and old one no longer
  does**, and the token is **single-use** (second redeem → 400).
- forgot: unknown email → no email, no token; disabled account → no email, no token.
- reset: invalid token → 400; **expired** token → 400.
- activate: invite token sets password + logs in; a `PASSWORD_RESET` token is **rejected** at
  `/activate` (purpose-bound).

`PlatformOrgServiceIT` (20): existing provisioning coverage + a new assertion that a minted owner
gets exactly one live, unconsumed **INVITE** token.

## Acceptance (what "done" means)

1. A new user can `POST /api/auth/register` with no credentials and is returned an authenticated
   session; `GET /api/me` with those cookies reflects their identity.
2. Registering with `org_name` yields an org whose sole OWNER is the registrant.
3. Duplicate email → `409`; password < 8 → `400`; malformed email → `400`.
4. `POST /api/auth/forgot-password` returns `200` with the **same** body whether or not the email
   exists; a token is minted **only** for an existing active account.
5. `POST /api/auth/reset-password` with a valid token sets the password, **invalidates every prior
   session** (token_version bump + logout-all), and logs the user in; an invalid/expired/reused/wrong
   -purpose token → `400`.
6. `POST /api/auth/activate` with a provisioning INVITE token sets the first password, activates the
   account, and logs the user in.
7. `POST /api/admin/orgs` for a **new** owner email mints an INVITE token in the provisioning txn and
   emails an activation link; an **existing** owner email mints none.
8. Tokens are stored only as SHA-256 hashes, are single-use (consumed on redeem), and expire
   (`PASSWORD_RESET` ≤ 2h, `INVITE` ≤ 7d by default).
9. All four endpoints are reachable anonymously and remain behind the `/api/auth/*` rate limiter.

## CLAUDE.md additions (ready to apply)

Add under **Auth (`/api/auth`)**:

- `POST /api/auth/register` — anonymous self-service sign-up. Body `{email, password, org_name?}`;
  creates a USER (+ optional org, caller = OWNER) in one txn and logs in (201 + cookies). 409 on
  duplicate email.
- `POST /api/auth/forgot-password` — anonymous. Body `{email}`; always 200 (no account enumeration);
  mints a single-use `PASSWORD_RESET` token + emails a link only for an existing active account.
- `POST /api/auth/reset-password` — anonymous. Body `{token, new_password}`; consumes the token, sets
  the hash, bumps `token_version` (logout-all), logs in (200 + cookies).
- `POST /api/auth/activate` — anonymous. Body `{token, new_password}`; consumes a provisioning
  `INVITE` token, sets first password + activates, logs in (200 + cookies).

Note under **Platform admin → `POST /api/admin/orgs`**: a **minted** owner now also receives an
emailed INVITE activation link (best-effort, sent after commit). Prerequisite migration **V49** adds
`app_user_magic_token` (hashed, single-use, TTL'd; purposes `PASSWORD_RESET` | `INVITE`).
