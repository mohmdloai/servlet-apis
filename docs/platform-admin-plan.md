# Platform Admin API — Implementation Plan (slices 2–5)

> Roadmap for the **platform tier** (`SystemRole` ADMIN/SUPPORT), the container above orgs — not
> OWNER/MANAGER. Slice 1 (impersonation + per-device kill-switch) shipped in `docs/impersonation.md`.
> This plan covers the remaining four capabilities. Same spine and conventions as the rest of the
> codebase (`domain → repository → service → api`, manual DI in `AppConfig`, jOOQ, servlet dispatch).

> **Status: implemented.** Slices 0, 2, 3, 4, and 5 are all built and green (Testcontainers ITs over
> Postgres+Redis for the services, Mockito handler-authz matrices, and a direct enforcement test for
> the suspended-org gate). Migrations `V42` (platform_audit) and `V43` (org.suspended_at/reason)
> landed; regenerate jOOQ after pulling. The endpoint surface is documented in `CLAUDE.md`
> (Platform admin section).

## Context

After impersonation, a platform ADMIN can *act inside* an org (via the `isSystemAdmin()` bypass in
`AuthzHelper.requireOrgAccess`) but **cannot discover orgs, manage users/roles, suspend an org, or
administer another user's sessions**, and SUPPORT is inert outside read-only impersonation. What's
already plumbed and reusable:

- `OrgRepositoryImpl.findAll(offset, limit)` — **exists, currently unused**.
- `org.active` column (V15) — **exists, only ever set `true` on create; never toggled or enforced**.
- `UserRepository.insertOrgRole(userId, orgId, role)` and `findSystemRoles(userId)` — exist.
- `RefreshTokenStore.listSessions(userId)`, `revokeFamily`, `revokeAllForUser`, `denyFamilyAccess`,
  and `AuthService.logoutAll(userId)` — all exist and are **already keyed by an arbitrary userId**.
- Platform plane: `AdminSweepServlet` at `/api/admin/*`, `PlatformImpersonationServlet` at
  `/api/admin/impersonate/*`, `AuthzHelper.requireAdmin`.

Next migration number: **V42**.

---

## Slice 0 — Shared foundations (do first; small)

1. **`/api/admin/*` becomes a dispatcher.** Replace the single-purpose `AdminSweepServlet` with an
   `AdminServlet` that parses `pathInfo` and delegates to per-resource handlers (mirrors `OrgServlet`):
   `/sweep` (existing), `/orgs`, `/orgs/{id}`, `/orgs/{id}/…`, `/users`, `/users/{id}/…`.
   `PlatformImpersonationServlet` stays as the more-specific `/api/admin/impersonate/*` mapping.
2. **Authz helpers** (`AuthzHelper`): add `requirePlatformRead(req)` (ADMIN **or** SUPPORT) and keep
   `requireAdmin(req)` for writes. Read endpoints below use `requirePlatformRead`; mutations use
   `requireAdmin`. This is what finally gives SUPPORT a purpose (read-only console).
3. **`platform_audit` table (V42)** — a minimal, shared audit ledger for every mutating platform
   action in slices 3–5 (org suspend, role grant, force-logout, …). Seeds the future general spine
   (item 5); `impersonation_event` stays as-is and can be folded in later.
   ```sql
   CREATE TABLE platform_audit (
     id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
     actor_id     UUID NOT NULL REFERENCES app_user(id),   -- the platform admin
     action       VARCHAR(64) NOT NULL,                    -- 'ORG_SUSPEND','ROLE_GRANT',...
     target_type  VARCHAR(32) NOT NULL,                    -- 'ORG','USER','SESSION'
     target_id    UUID,
     detail       JSONB,                                   -- action-specific payload
     source_ip    VARCHAR(64),
     user_agent   TEXT,
     created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
   );
   CREATE INDEX ix_platform_audit_target ON platform_audit (target_type, target_id, created_at);
   ```
   `PlatformAuditRepository.insert(...)` + a helper `PlatformAuditService.record(ctx, action, …)`.

---

## Slice 2 — Cross-org read console (item 1)  ·  lowest risk, partly plumbed

**Goal:** platform ADMIN/SUPPORT can list every org and inspect one, without holding any org role.

- **Authz:** `requirePlatformRead` (VIEWER-equivalent at the platform tier).
- **Repository:** wire the existing `OrgRepositoryImpl.findAll(offset, limit)` + add `countAll()`.
  Add read-only rollups keyed by org (new org-less queries, since every existing repo method is
  org-scoped): counts of users, PENDING_PAYMENT orders, open disputes, unallocated payments.
- **Service:** `PlatformOrgService.list(page, size)`, `getWithHealth(orgId)`.
- **API / endpoints:**
  - `GET /api/admin/orgs?page&size&status=active|suspended` → paged org list (name, slug, active,
    created_at, member count).
  - `GET /api/admin/orgs/{orgId}` → org + health rollup (the counts above).
- **Decisions:** cross-*entity* global search (find an order/payment across all orgs) is a **later
  expansion** — this slice is list + per-org drill-down only, to keep the org-less query surface small.
- **Tests:** admin lists all orgs incl. ones they have no role in; SUPPORT can read (200) but cannot
  hit any mutation (403); non-platform caller → 403; pagination + `status` filter.
- **Unlocks:** the read half of the SUPPORT console for free.

---

## Slice 3 — User & system-role administration (item 3)  ·  greenfield

**Goal:** grant/revoke platform + org roles and manage user status **without touching the DB**. This
is how a new ADMIN or SUPPORT is minted going forward.

- **Authz:** `requireAdmin` (all mutations). Reads may use `requirePlatformRead`.
- **Schema:** none for roles (`user_system_role` / `user_org_role` exist). Password reset reuses
  `PasswordHasher`.
- **Repository (`UserRepository` additions):** `insertSystemRole(userId, role)`,
  `deleteSystemRole(userId, role)`, `deleteOrgRole(userId, orgId, role)`, `findAll(page, size)`,
  `search(emailPrefix)`, `setActive(userId, boolean)`, `updatePasswordHash(userId, hash)`.
- **Service:** new `UserAdminService` — create user, disable/enable, grant/revoke system role,
  grant/revoke org role, reset password. Each mutation calls `PlatformAuditService.record(...)` and,
  where it changes authority, bumps revocation (see below).
- **API / endpoints:**
  - `GET /api/admin/users?page&size&q=` · `GET /api/admin/users/{id}` (roles + status + sessions count)
  - `POST /api/admin/users` `{email, password?, actor_type}` → create (bcrypt if password given)
  - `PATCH /api/admin/users/{id}` `{active}` → enable/disable
  - `POST /api/admin/users/{id}/system-roles` `{role}` · `DELETE .../system-roles/{role}`
  - `POST /api/admin/users/{id}/org-roles` `{org_id, role}` · `DELETE .../org-roles/{org_id}/{role}`
  - `POST /api/admin/users/{id}/reset-password` `{password}`
- **Decisions / guard rails:**
  - **Disabling or revoking must revoke access now:** `setActive(false)` and role changes call
    `logoutAll(userId)` (bumps `token_version`) so stale access tokens die immediately — otherwise a
    demoted user keeps their old `org_roles` in-token for up to 15 min.
  - **No self-lockout / last-admin guard:** an ADMIN cannot remove their own ADMIN role or disable
    themselves, and the system refuses to remove the *last* remaining platform ADMIN.
  - Creating a user with `actor_type` other than `USER`/`SERVICE` is rejected here.
- **Tests:** grant ADMIN → target gains platform access on next login; revoke org role →
  `logoutAll` fired and old token 401s; last-admin guard; self-demotion blocked; create+disable;
  every mutation writes a `platform_audit` row.

---

## Slice 4 — Cross-user security operations (item 4)  ·  companion to the kill-switch

**Goal:** incident response — an admin can see and cut another user's sessions/devices and force a
full logout. Almost entirely reuses existing `RefreshTokenStore`/`AuthService` methods, just keyed by
a target userId instead of `self`.

- **Authz:** `requireAdmin`.
- **Repository/service:** none new — reuse `listSessions(userId)`, `revokeFamily(familyId, userId)`
  + `denyFamilyAccess` (per-device kill-switch), `revokeAllForUser` + `incrementTokenVersion`
  (= `logoutAll`). Add thin `AuthService` passthroughs that take a target userId and audit.
- **API / endpoints:**
  - `GET /api/admin/users/{id}/sessions` → that user's active families (device, ip, last-seen)
  - `DELETE /api/admin/users/{id}/sessions/{familyId}` → revoke one device (immediate, via the
    kill-switch denylist)
  - `POST /api/admin/users/{id}/logout-all` → force global logout (bumps `token_version`)
- **Decisions:** every action writes `platform_audit`. `logout-all` here is the same primitive as
  slice 3's revoke-triggers — share one internal method. Self is allowed (an admin may cut their own
  other devices) but the last-admin/self-lockout guard from slice 3 does **not** apply (logout ≠
  de-privilege).
- **Tests:** admin lists/kills another user's device → that device 401s immediately while its others
  survive; force-logout-all → all the target's tokens 401; audit rows written; non-admin → 403.

---

## Slice 5 — Org lifecycle + enforcement (item 2)  ·  needs enforcement, not just an endpoint

**Goal:** suspend/reactivate an org and have suspension actually *mean* something.

- **Authz:** `requireAdmin`.
- **Schema (V-next):** optional `org.suspended_at`, `org.suspended_reason` (the boolean `org.active`
  already exists and is the enforcement flag).
- **Service:** `PlatformOrgService.suspend(orgId, reason)` / `reactivate(orgId)` → toggles
  `org.active`, audits. Suspend optionally force-logs-out that org's members (reuse slice 4).
- **Enforcement — the load-bearing part:** `AuthzHelper.requireOrgAccess` must reject a suspended org
  with `403 "Org suspended"` for normal members, **while still allowing the platform bypass** (an
  admin can enter a suspended org to fix it). Requires an org-active lookup per org-scoped request —
  cache `org:active:{orgId}` in Redis (mirror of DB, invalidated on suspend/reactivate) to avoid a DB
  hit on the hot path, matching the token_version cache pattern.
- **API / endpoints:**
  - `POST /api/admin/orgs/{orgId}/suspend` `{reason}` · `POST /api/admin/orgs/{orgId}/reactivate`
- **Decisions:** hard-delete stays on the existing OWNER path; platform delete-with-guards is a
  later add. Quotas/limits are out of scope here.
- **Tests:** suspend → members get 403 on every org endpoint, admin bypass still 200; reactivate
  restores; Redis cache invalidated correctly; audit rows.

---

## Recommended sequence & rationale

1. **Slice 0** (foundations) → **Slice 2** (read console): low risk, `findAll` already there, gives
   SUPPORT its read role immediately.
2. **Slice 3** (user/role admin) + **Slice 4** (session ops): together make ADMIN self-sufficient and
   provide the incident-response toolkit; slice 4 is cheap (pure reuse).
3. **Slice 5** (org lifecycle): last, because enforcement touches the hot authorization path and
   deserves its own careful review.

Cross-cutting: **every mutation in slices 3–5 audits to `platform_audit`**, which also closes the
accountability gap where an admin's bypass-driven org actions currently leave no platform trace.

## Verification (per slice)
`docker-compose up -d` · regen jOOQ after any migration (`mvn generate-sources -Pcodegen -pl
repository`) · `mvn install` · per-slice ITs over Postgres+Redis Testcontainers · end-to-end curl
against `mvn exec:java -pl api` with a seeded ADMIN and SUPPORT (login → exercise each endpoint →
assert authz matrix + audit rows), mirroring the impersonation slice's empirical pass.

## Out of scope (later)
Global cross-entity search, quotas/rate-limits, feature flags, the full general-purpose audit spine
(item 5 in the gap analysis — this plan ships only the minimal `platform_audit`), and any change to
the admin money-threshold bypass (a separate hardening decision).
