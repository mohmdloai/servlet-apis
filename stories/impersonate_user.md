# Slice: Impersonate a user (two-tier, audited)

> Platform + org **act-as**. Gives `SystemRole` ADMIN/SUPPORT and org OWNERs a way to *become* a
> target user via a short-lived access-token overlay, without touching the target's session and
> without ever escaping the impersonator's authority scope. Implements the design in
> [`docs/impersonation.md`](../docs/impersonation.md). First real cross-org platform route beyond
> the expiry sweep — see the platform-scope model in `docs/frontend-architecture.md` §3.3.

---

## Goal

Mint an **access token only** whose `sub` is the target user and whose new `act` claim is the real
driver, then let every existing RBAC gate run unchanged against `sub`. Two entry points:

- **Platform tier** — `POST /api/admin/impersonate/{userId}`: `SystemRole` ADMIN (write) or SUPPORT
  (read-only). Overlay carries the target's identity **across all their orgs**, zero `system_roles`.
- **Org tier** — `POST /api/orgs/{orgId}/impersonate/{userId}`: org `OWNER`. Overlay carries the
  target's identity **confined to `{orgId}`** — every other org and all `system_roles` stripped.

Both write an `impersonation_event` START row; stopping writes STOP. Any `inventory_log` (or other
ledger) row written under an overlay is stamped with `impersonator_id`.

**Done means:** an ADMIN calls `POST /api/admin/impersonate/{userId}`, receives an `access_token`
cookie (no refresh cookie), and every subsequent request is authorized *as the target* — while every
audit row it touches records the ADMIN as the real driver. `POST /api/auth/stop-impersonating`
returns them to their own identity; doing nothing also returns them when the short token expires.

---

## Why this slice

`SystemRole` is fully decoded but only ever used as an org bypass; `SUPPORT` is gated on nowhere.
This slice gives the platform tier a real cross-org capability and gives `SUPPORT` its purpose (safe
read-only view-as), and it does so with the overlay pattern so the blast radius is bounded by a short
TTL and there is no server-side impersonation session to leak or clean up.

---

## Scope

### In
- **Domain**: three nullable fields on `SecurityContext` (`impersonatorId`, `impersonationTier`,
  `impersonationReadOnly`) + `ImpersonationTier{PLATFORM, ORG}` enum. `UserRepository` read for
  "target's roles within one org" and "is target active" (reuse `findById`/`findOrgRoles`).
- **Token**: `JwtUtil.generateAccessToken` **overload** adding `act`, `act_tier`, `act_scope_org`,
  `act_mode` claims and an explicit `ttlMillis` (impersonation tokens use a shorter TTL,
  `IMPERSONATION_TTL_MILLIS`, default **300000** = 5 min). Existing signature unchanged.
- **Filter**: `JwtAuthFilter` lifts the `act*` claims into the new `SecurityContext` fields.
- **Service**: `AuthService.impersonate(caller, targetId, tier, scopeOrgId, reason, env)` and
  `AuthService.stopImpersonating(caller, env)`, with all guard rails and **scoped-overlay minting**.
- **Repository + schema**: `ImpersonationEventRepository` (insert START/STOP); migration **V41**
  creating `impersonation_event` **and** adding nullable `impersonator_id UUID` to `inventory_log`.
- **API**: `PlatformImpersonationServlet` under `/api/admin/impersonate/*` (sibling of
  `AdminSweepServlet`); `ImpersonationHandler` off `OrgServlet` for the org route;
  `POST /api/auth/stop-impersonating` in `AuthServlet`. All three set the **access cookie only**
  (extracted into a shared `AuthCookies.writeAccess(resp, token, maxAge, secure)` helper — the
  refresh cookie is deliberately never written).
- **Read-only gate**: `AuthzHelper` rejects mutating guards (`requireOrgAccess` at STAFF+ / MANAGER,
  the `isOwnerOrAdmin` money gates) with `403` when `impersonationReadOnly` is true.
- **Layer-2 stamping**: `inventory_log` writes persist `impersonator_id` when
  `SecurityContext.impersonatorId != null`.

### Out (explicitly deferred)
- **Generic `audit_log` spine** — this slice stamps the ledger that exists (`inventory_log`) and adds
  the dedicated `impersonation_event` table. A cross-cutting audit table every mutating service writes
  to is its own slice; `impersonator_id` stamping extends to it for free once it lands.
- **Nested impersonation** — forbidden, not supported. An overlay token cannot start another.
- **Impersonating a system admin** — forbidden (admin→admin grants nothing).
- **Frontend "acting as…" banner** — the API returns the fields to render it; the UI is a frontend slice.
- **Org-tier read-only mode** — org-tier is always full-write within scope; `act_mode=READONLY` is
  platform-SUPPORT-only in this slice.
- **A dedicated cross-org "list users" console** — the caller supplies a known `userId`; discovery UI
  is part of the platform-dashboard slice.

---

## Schema

### V41 — `V41__Create_impersonation_event_and_log_actor.sql`

```sql
CREATE TABLE impersonation_event (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    impersonator_id UUID        NOT NULL REFERENCES app_user(id),
    target_id       UUID        NOT NULL REFERENCES app_user(id),
    tier            VARCHAR(16) NOT NULL,             -- 'PLATFORM' | 'ORG'
    scope_org_id    UUID        REFERENCES org(id),   -- NULL for PLATFORM tier
    event           VARCHAR(16) NOT NULL,             -- 'START' | 'STOP'
    reason          TEXT,
    source_ip       VARCHAR(64),
    user_agent      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_imp_tier  CHECK (tier  IN ('PLATFORM','ORG')),
    CONSTRAINT ck_imp_event CHECK (event IN ('START','STOP')),
    CONSTRAINT ck_imp_scope CHECK ((tier = 'ORG') = (scope_org_id IS NOT NULL))
);
CREATE INDEX ix_impersonation_event_target ON impersonation_event (target_id, created_at);
CREATE INDEX ix_impersonation_event_actor  ON impersonation_event (impersonator_id, created_at);

ALTER TABLE inventory_log ADD COLUMN impersonator_id UUID REFERENCES app_user(id);
```

The `ck_imp_scope` CHECK makes the tier/scope pairing a schema invariant: ORG events must carry a
`scope_org_id`, PLATFORM events must not. Regenerate jOOQ sources after applying (`mvn generate-sources
-Pcodegen -pl repository`).

---

## Guard rails (in `AuthService`, 400/403 as noted)

**Both tiers** — else `400`/`403`:
- Caller's own token already carries `act` → `409 "Already impersonating"` (no nesting).
- `caller == target` → `400 "Cannot impersonate yourself"`.
- Target not found → `404`; target `!isActive()` → `403 "Target account is disabled"`.
- Mint **access token only** — no `refreshTokenStore.store(...)`, no refresh cookie.

**Platform tier** (`/api/admin/impersonate/{userId}`):
- Caller lacks ADMIN and SUPPORT → `403`. SUPPORT ⇒ overlay `act_mode = READONLY`.
- Target has `SystemRole.ADMIN` → `403 "Cannot impersonate a system admin"`.
- Overlay `system_roles` = ∅; `org_roles` = target's full map.

**Org tier** (`/api/orgs/{orgId}/impersonate/{userId}`):
- Caller's **real** OrgRole in `{orgId}` is not `OWNER` (system-admin bypass **not** honored here) → `403`.
- Target is not a member of `{orgId}` → `404 "Target is not a member of this org"`.
- Target's max rank in `{orgId}` ≥ caller's (via `AuthzHelper.RANK`) → `403` (OWNER can't impersonate OWNER).
- Overlay `system_roles` = ∅; `org_roles` = `{ orgId: <target roles in orgId> }` only.

---

## Acceptance criteria

- [ ] ADMIN `POST /api/admin/impersonate/{userId}` (target is a plain org user) → `200`; response body
      `{impersonator_id = <admin id>, tier = "PLATFORM", read_only = false}`; a `Set-Cookie: access_token`
      is present with `Max-Age=300`; **no** `Set-Cookie: refresh_token` is present.
- [ ] The overlay access token decodes to `sub = target.id`, `act = admin.id`, `act_tier = "PLATFORM"`,
      **no `system_roles` claim**, and `token_version = target's current version`.
- [ ] Using the overlay cookie, a request the **target** is authorized for succeeds and one the target
      is **not** authorized for returns `403` — i.e. authorization keys off `sub`, not the admin.
- [ ] One `impersonation_event` row exists: `event='START'`, `tier='PLATFORM'`, `scope_org_id IS NULL`,
      `impersonator_id=admin.id`, `target_id=target.id`, `source_ip`/`user_agent` populated.
- [ ] **Cross-org leak blocked (org tier):** target belongs to orgs A **and** B; OWNER of A calls
      `POST /api/orgs/{A}/impersonate/{target}` → `200`, overlay token's `org_roles` has key A **only**
      (no B); a request to any `/api/orgs/{B}/...` under that overlay returns `403`. `act_tier='ORG'`,
      `act_scope_org=A`.
- [ ] **SUPPORT is read-only:** SUPPORT `POST /api/admin/impersonate/{userId}` → `200`,
      `read_only=true`, token has `act_mode='READONLY'`; under that overlay a VIEWER-level `GET` returns
      `200` but any STAFF+/MANAGER write (e.g. `POST /api/orgs/{o}/products`) returns `403` **before**
      any state change (no `product` row, no `inventory_log` row created).
- [ ] **No nesting:** calling either impersonate endpoint while already carrying `act` → `409`, no new
      `impersonation_event` row.
- [ ] **Not self / disabled / admin target:** `caller==target` → `400`; disabled target → `403`;
      platform impersonation of an ADMIN target → `403`. None create an `impersonation_event`.
- [ ] **Org-tier rank guard:** OWNER of `{orgId}` impersonating another OWNER of `{orgId}` → `403`;
      impersonating a MANAGER/STAFF/VIEWER of `{orgId}` → `200`.
- [ ] **Org-tier requires real OWNER:** a MANAGER of `{orgId}` → `403`; a system ADMIN with no org role
      in `{orgId}` → `403` on the **org** endpoint (they must use the platform endpoint).
- [ ] **Layer-2 stamping:** a stock mutation performed under an ADMIN overlay writes an `inventory_log`
      row whose principal columns are the **target** and whose `impersonator_id = admin.id`; the same
      mutation on a normal session leaves `impersonator_id` NULL.
- [ ] **Stop (explicit):** `POST /api/auth/stop-impersonating` under an overlay → `200`,
      `{impersonator_id: null}`, sets a fresh `access_token` cookie whose token has **no `act` claim**
      and `sub = admin.id`; writes an `impersonation_event` `event='STOP'` row. Calling it on a
      non-overlay session → `400 "Not impersonating"`.
- [ ] **Stop (implicit):** after the overlay's 5-min TTL, the next call with the (now-expired) access
      cookie plus the **untouched** admin refresh cookie can `POST /api/auth/refresh` back into the
      admin identity — the admin's refresh family was never revoked or rotated by impersonation.
- [ ] **Target revocation kills the overlay:** target does `POST /api/auth/logout-all` (bumping
      `token_version`); the outstanding overlay token’s next request → `401 "Token has been revoked"`.
- [ ] Missing/blank `JWT_SECRET`, wrong role, and unknown `userId` paths all return JSON `ApiError`
      with the documented status; no impersonation token is ever minted on a rejected request.
