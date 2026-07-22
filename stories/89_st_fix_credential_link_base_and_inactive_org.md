# 89 — Fix: credential links point at the storefront + registration orgs go live before verification

> **Status: IMPLEMENTED.** Branch `89_fix/credential-link-base-and-inactive-org` (PR #89 — verify
> with `gh pr list --state all`). **Frontend pair: none** — server-side + deploy config only.
> The production bug pair reported against stories 87/88/57 after deploy (2026-07-22).

---

## The two defects

### 1 · Every credential link targets the wrong app (prod)

`CredentialTokenService` builds reset/activate/**verify** URLs from `PUBLIC_BASE_URL` — which the
production compose sets to `https://store.${DOMAIN}` (the storefront), because that is where the
**customer** magic links (order view, unsubscribe) must land. But the credential pages
(`/reset-password`, `/activate`, `/verify-email`) live in the **admin app** at
`admin.${DOMAIN}`. Net effect: the story-88 verification email links to
`https://store.yabta3.com/verify-email?token=…` — a 404 on the storefront — and (pre-existing,
unnoticed) forgot-password/invite links were equally broken in prod.

**Fix:** a dedicated **`ADMIN_BASE_URL`** env consumed by `CredentialTokenService`
(`AppConfig`: `getenvOrDefault("ADMIN_BASE_URL", PUBLIC_BASE_URL)` — dev, where both apps are
localhost, needs nothing). `deploy/docker-compose.prod.yml` sets
`ADMIN_BASE_URL: https://admin.${DOMAIN}`. Customer magic links keep `PUBLIC_BASE_URL`
(storefront) untouched.

### 2 · An unverified registrant's org is born `active=true`

Story 88 called the registration org "inert" — but `active=true` means it is **publicly served**:
the storefront resolves active orgs by slug, so every spam registration minted a live (empty)
storefront at `store.yabta3.com/{slug}` and an active-looking org in the platform console, with
zero inbox proof. (Login itself was and is correctly 403-blocked — the account's `active` flag is
the *ban/suspension* switch and deliberately stays true; verification gates login via
`email_verified_at`.)

**Fix — born inactive, activated by the verify click:**
- `AccountService.register` creates the optional first org with **`active=false`** (and no
  `suspended_at` — that stamp is the admin-suspension marker, which is what keeps the two states
  distinguishable).
- `AccountService.verifyEmail` — in the same txn that stamps `email_verified_at` — activates the
  registrant's orgs matching **sole OWNER + `active=false` + `suspended_at IS NULL`**
  (`OrgRepository.activateRegistrationPendingOrgs`), then invalidates the `org:active:{id}` Redis
  mirror post-commit (load-through cache — `OrgStatusService.invalidate`). An admin-suspended org
  can never be resurrected by a verify click (the `suspended_at` guard), and a multi-member org is
  never touched.
- The purge job needs no change: it already deletes the sole-member org regardless of `active`.
- Platform provisioning and the authenticated `POST /api/orgs` keep creating active orgs — their
  callers are verified/vouched.

## Scope

### In
`ADMIN_BASE_URL` (AppConfig + compose + CLAUDE.md); inactive-at-register + activate-at-verify +
mirror invalidation; `OrgRepository.activateRegistrationPendingOrgs`; IT coverage.

### Out
- **Renaming/re-scoping `PUBLIC_BASE_URL`** — every customer link keeps working as-is.
- **Flipping `app_user.active` semantics** — it stays the ban switch; verification is
  `email_verified_at` (story 88 design, unchanged).
- **Retro-activating** orgs registered before this fix — they are unverified spam candidates; the
  purge job ages them out (or the platform console can suspend/delete).

## Acceptance criteria

1. **Link base:** with `ADMIN_BASE_URL=https://admin.example`, verify/reset/activate URLs start
   with it; unset → falls back to `PUBLIC_BASE_URL` (dev unchanged). Customer magic links always
   use `PUBLIC_BASE_URL`.
2. **Born inactive:** register-with-org → the org row is `active=false`, `suspended_at IS NULL`;
   registration without an org is unaffected.
3. **Activated by verify:** redeeming the verification link flips exactly that org to
   `active=true` and invalidates its status-mirror key; the org picker then shows it.
4. **Suspension is sacred:** an inactive org with `suspended_at` set (admin-suspended) is NOT
   activated by its sole owner's verify click; a multi-member inactive org is never touched.
5. **Deploy:** `docker-compose.prod.yml` carries `ADMIN_BASE_URL: https://admin.${DOMAIN}`.

## Tests

`AccountServiceIT`: register-with-org asserts `active=false` (AC 2); verify round-trip asserts the
flip (AC 3); suspended-org and multi-member guards (AC 4). Link-base fallback is wiring
(`getenvOrDefault`) — covered by AC 1's URL assertions in `CredentialTokenService` usage via the
existing token tests' base-URL constructor param.
