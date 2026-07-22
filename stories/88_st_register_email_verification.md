# 88 — Story: Register verify-to-activate (email verification for self-serve merchant accounts)

> **Status: IMPLEMENTED.** Branch `88_feat/register-email-verification` (PR #88 — verify with
> `gh pr list --state all`). **Frontend pair: `frontst/stories/57_st_register_verify_email.md`**
> (check-your-email interstitial, `/verify-email` redemption page, login 403 handling).
> **Builds on story 87** (email gate + `rl:auth-register` throttle; merged as PR #87).
>
> **What this is.** Slice B of the register-spam pair. `POST /api/auth/register` currently mints a
> **live, logged-in** account (+ optional first org) with zero proof the inbox exists — the
> full-form-first flow stays (best conversion), but the account is now created **unverified**:
> a verification link is emailed, **login is blocked with a 403 until it's clicked**, and a
> recurring purge job deletes never-verified accounts. This is the Google/GitHub model, and it is
> cheap here because every building block already exists: `app_user_magic_token` +
> `CredentialTokenService` (V49 — reset/invite tokens, one-shot redeem that auto-logs-in),
> `AuthMailer`, and JobRunr.

**As** the operator of a public merchant-signup form,
**I want** self-registered accounts to prove inbox ownership before they can sign in — and junk
accounts that never verify to disappear on their own —
**so that** register spam yields nothing durable: no usable account, no permanent DB rows, no
cleanup toil.

---

## The reality this corrects

1. **Register = instant live session.** `AccountService.register` creates the `app_user` (+ org,
   when `orgName` is given) and immediately calls `authService.issueSession` — cookies and all. A
   bot's account is as real as a human's.
2. **`email_verified_at` exists only on `customer`** (portal plane, stamped by OTP verify). The
   staff/merchant plane (`app_user`) has no verification concept.
3. **The token machinery is already built.** `app_user_magic_token` (V49) with purposes
   `PASSWORD_RESET`/`INVITE`, minted by `CredentialTokenService.mintAutonomous`, redeemed one-shot
   (atomic `consumed_at`), redemption issuing a fresh session. Verification is a third purpose, not
   a new system.

## The design

### 1 · Migration `V65__App_user_email_verification.sql`
*(number = next free at implementation time — V64 is the current tip)*

- `ALTER TABLE app_user ADD COLUMN email_verified_at TIMESTAMPTZ;`
- **Backfill every existing row** `SET email_verified_at = created_at` — all current users are
  grandfathered; nobody alive gets locked out by this deploy.
- Widen the V49 CHECK: purpose set becomes `('PASSWORD_RESET', 'INVITE', 'EMAIL_VERIFY')`
  (drop + re-add the constraint).

### 2 · Token purpose `EMAIL_VERIFY`

- `AppUserTokenPurpose.EMAIL_VERIFY`; TTL **48h** in `CredentialTokenService` (reset stays shorter,
  invite stays as-is).
- `verifyUrl(rawToken)` → `PUBLIC_BASE_URL + "/verify-email?token=" + rawToken` (sibling of
  `resetUrl`/`activateUrl`; the frontend route is story 57's).
- `AuthMailer.sendVerifyEmail(email, url)` — same plain transactional style as reset/invite,
  sent synchronously post-commit (a verification mail is transactional: bypasses the
  notification/preference pipeline, exactly like reset/invite/OTP).

### 3 · `AccountService.register` — stop issuing a session

- Account + optional first org creation stays **unchanged and atomic** (the org is inert — its only
  OWNER can't log in until verified, and purge removes both together).
- After commit: mint `EMAIL_VERIFY` token + `sendVerifyEmail`. A send failure logs ERROR but does
  not roll back — `/resend-verification` is the recovery path.
- Return type: the created user, **no `LoginResult`**. `AuthServlet.handleRegister` responds
  **201 `{email, verification: "sent"}`** and sets **no cookies**.

### 4 · Two new anonymous routes on `AuthServlet`

- **`POST /api/auth/verify-email`** `{token}` → one-shot redeem (atomic `consumed_at`, expired /
  consumed / unknown → the existing redemption 400/401 behaviour), stamp
  `email_verified_at = now()`, then **`issueSession`** — the click logs the user in, exactly like
  reset/activate redemption. Idempotent replay of a consumed token fails like any replayed token.
- **`POST /api/auth/resend-verification`** `{email}` → **uniform 200 always**
  (enumeration-safe, mirrors `requestPasswordReset`): only an existing, **active, unverified**
  account gets a fresh token + email (prior unconsumed tokens are superseded/invalidated). Bucketed
  per-IP as `rl:auth-resend` reusing **`AUTH_FORGOT_LIMIT`** (default 5/min).

### 5 · Login gate

`AuthService.login`: after password verification and the `active` check — if
`email_verified_at IS NULL` → **403** `AuthorizationException("Email not verified")`. Login
otherwise only emits 400/401, so the status alone is the frontend's discriminator. The refresh
path needs no gate: under this flow an unverified user can never hold a session (register no longer
issues one; grandfathering covers everyone pre-existing).

### 6 · Ownership-proof self-heal (keep the invariant coherent)

Any flow that proves inbox ownership stamps `email_verified_at` if null:
- redeeming a `PASSWORD_RESET` or `INVITE` token (the link arrived in that inbox);
- `UserAdminService.createUser` / `PlatformOrgService.createOrg`-minted owners: stamped **at
  creation** — the admin plane vouches; the invite-activation path re-proves it anyway. Platform
  flows must never produce a 403-locked user.

### 7 · Purge job — `UnverifiedAccountPurgeJob` (api module, JobRunr)

- Recurring (cron **`UNVERIFIED_PURGE_INTERVAL`, default daily**), gated by the shared
  `ORDER_SWEEPER_BACKGROUND_ENABLED` flag like the other jobs.
- Deletes `app_user` where `email_verified_at IS NULL` **AND** `created_at < now() - 7 days`
  **AND** no unexpired `EMAIL_VERIFY` token exists (never yank an account whose emailed link is
  still live). Per-user txn: delete magic tokens + org roles; when the user was an org's **only**
  member, delete the org too (it can hold no business data — its owner never logged in); any FK
  violation → skip that user + WARN (defensive, not expected). Logs a count per run.

## Scope

### In
Migration V65; `EMAIL_VERIFY` purpose + TTL + URL + mailer template; register de-sessioning +
201 body; `/verify-email` + `/resend-verification` routes (+ `rl:auth-resend` bucket); login 403
gate; self-heal stamping (reset/invite redeem, admin-plane creation); purge job + env; CLAUDE.md
updates (endpoints + env); tests.

### Out
- **Frontend** — story 57 (`frontst`).
- **Changing what register collects** (email/password/orgName contract unchanged).
- **Verifying email changes for existing users** (email isn't self-editable on this plane today).
- **Customer-plane verification** — portal OTP already is it.
- **Grace-period login** ("let them in for N days unverified") — strict 403 keeps the invariant
  simple; conversion is protected by the auto-login redemption instead.

## Acceptance criteria

1. **Register:** valid registration → 201 `{verification:"sent"}`, **no cookies**, `app_user` row
   with `email_verified_at NULL`, one `EMAIL_VERIFY` token row, one verification email
   (LoggingEmailSender in dev).
2. **Blocked login:** correct password before verification → **403** "Email not verified"; wrong
   password still 401 (the gate runs after password check, so it's not a password oracle).
3. **Redemption:** `POST /verify-email` with the emailed token → 200 + both auth cookies +
   `email_verified_at` stamped; second use of the same token → error, no session; expired token →
   error naming expiry; subsequent normal logins succeed.
4. **Resend:** unknown / already-verified / disabled email → same uniform 200 with no send;
   unverified account → fresh token sent and the old one no longer redeems; 6th request/min/IP →
   429.
5. **Grandfathering:** every pre-migration user logs in exactly as before (backfill), including
   platform-minted owners; admin-created users are born verified.
6. **Self-heal:** an unverified account that completes a password reset becomes verified.
7. **Purge:** an unverified account 8 days old with no live token is deleted — along with its
   token rows, org roles, and its sole-member org; a 3-day-old one and one holding an unexpired
   token both survive; verified accounts are never touched.

## Tests

- **`AccountServiceTest` (unit):** register returns no session, mints token, sends mail; send
  failure doesn't roll back; resend uniformity matrix (AC 4).
- **`AuthServiceTest` (unit):** AC 2 ordering (password check before verification check).
- **`CredentialTokenServiceTest`:** `EMAIL_VERIFY` TTL, one-shot redeem, supersede-on-resend.
- **Repository/api IT (Testcontainers):** migration backfill (AC 5); full register → verify-email →
  login round-trip (AC 1–3); purge job matrix (AC 7).
- **`RateLimitFilter` IT:** `rl:auth-resend` bucket.

## New dependencies

None (dnsjava arrived in 87; everything here is existing infra).

## Definition of done

All ACs green; `mvn test` green across modules; CLAUDE.md documents the two new endpoints, the
register response change, and `UNVERIFIED_PURGE_INTERVAL`; frontend story 57 can be built against
this contract without further backend changes.

## Guard rails (do not regress)

- **Resend and forgot-password stay enumeration-safe** — uniform 200, no timing/status oracle.
- **The 403 is reserved**: on `/login`, 403 ⇔ unverified — don't reuse it for other login
  failures, the frontend keys on the status.
- **Platform/admin-plane user creation must never yield a login-blocked user** (§6).
- **Purge never deletes** a verified account, an account younger than the window, or one with a
  live token — and never cascades into an org with other members.
