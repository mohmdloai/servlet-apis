# 09 — Story: Org Settings — members & roles, self profile, self password change

> **Status: SHIPPED (backend).** Built 2026-07-05 — all six gaps closed plus the V48 prerequisite,
> verified end-to-end against real PostgreSQL + Redis (`MemberServiceIT`, 20 tests: roster,
> add/set/remove, last-owner guard, de-privilege logout-all, self password change). The frontend
> **Settings** screen can now build directly against the contract below. Backend decisions are folded
> in-line and flagged **[BACKEND DECISION]**. The one open fork — **invite scope** — shipped as the
> recommended **(a) attach-existing-only** (unknown email → 404); full invite-by-email (option b)
> stays deferred (see §4).
>
> Endpoints to build (6 + 1 prerequisite migration):
> - **V48** — `ALTER TABLE app_user ADD COLUMN display_name` (prerequisite; carried by /me + members)
> - `GET  /api/me` — the caller's own profile (identity + roles + impersonation signal)
> - `POST /api/me/password` — self password change (verifies current)
> - `GET  /api/orgs/{orgId}/members` — the org's team roster (MANAGER read)
> - `POST /api/orgs/{orgId}/members` — add/invite a member (OWNER)
> - `PUT  /api/orgs/{orgId}/members/{userId}` — set a member's role (OWNER)
> - `DELETE /api/orgs/{orgId}/members/{userId}` — remove a member (OWNER)

---

## The gap (verified against code, 2026-07-05)

| # | Screen it blocks | What's missing | Evidence |
|---|---|---|---|
| 1 | Members list | No `GET /api/orgs/{orgId}/members`. `OrgServlet`'s sub-resource map has no `members` entry — an OWNER cannot see their own team | `OrgServlet.init()` registers 15 sub-resources (`products`…`impersonate`); none is `members`/`users` |
| 2 | Role edit / remove | Org-role grant/revoke exists **only** at `POST\|DELETE /api/admin/users/{id}/org-roles`, both gated `AuthzHelper.requireAdmin` (system ADMIN) | `UserAdminHandler.orgRoles()` lines 186–216 |
| 3 | Invite / add member | User creation is `POST /api/admin/users` (`requireAdmin`); no org-scoped invite or attach-by-email | `UserAdminHandler`; no org-scoped path |
| 4 | Profile identity panel | No `GET /me`. Login returns only `{expires_in, user_id, actor_type}`; the org-role map lives in the JWT and is never echoed back | `AuthResponse` = 3 fields; `AuthServlet` routes have no `/me` |
| 5 | Profile → security | Password reset is admin-only (`POST /api/admin/users/{id}/reset-password`). `AuthService` has `login` only — no `changePassword` | `AuthService`; `UserAdminService.resetPassword` |
| 6 | Any name UI | `app_user` is email-only. `AppUser` = `{id, email, password_hash, actor_type, active, token_version, created_at, updated_at}` — **no `display_name`** | `AppUser.java`; `V11__Create_app_user_table.sql`; no later `ALTER` |

Membership is stored in **`user_org_role (user_id, org_id, role)`** — PK is all three columns, so a
user can hold **multiple roles** in one org (`V12`). `SystemRole` lives separately in
`user_system_role` (`V14`). There is **no** `org_member` table — membership is derived from role rows.

---

## Non-negotiable: do **NOT** wire Settings to `/api/admin/*`

`/api/admin/users/*` and `/api/admin/orgs/*` are the **platform-operator** console
(`requireAdmin` / `requirePlatformRead` = system `ADMIN` / `SUPPORT`). A normal org OWNER holds **no
system role**, so every one of those calls 403s for them. Faking the members/roles screen against
the admin console ships a screen that is blank-or-broken for exactly the people who open Settings.
The endpoints below are **org-scoped and membership-gated** — that is the whole point. The platform
console stays out of scope; if a platform-admin web UI is ever wanted it is its own app, not a tab
in org Settings.

---

## Prerequisite — migration V48: `display_name`

```sql
-- V48__Add_app_user_display_name.sql
ALTER TABLE app_user ADD COLUMN display_name TEXT;   -- nullable; existing rows stay NULL
```

- **[BACKEND DECISION — nullable, no backfill]** `display_name` is optional. Existing users keep
  `NULL`; every payload below serializes it only when set (the ObjectMapper omits nulls), and the
  frontend falls back to `email` for the label. No unique constraint, no length cap beyond TEXT
  (validate ≤ 200 chars in the service if we ever let users set it).
- `AppUser` POJO gains `String displayName`; regenerate jOOQ (`mvn generate-sources -Pcodegen -pl
  repository`) after the migration so the generated record carries the column.
- **Setting it** is a thin add: `PATCH /api/me {display_name}` is the natural home, but it is **[OUT
  — deferred]** for v1 (the Profile screen ships read-only names + the password form; renaming
  yourself is a follow-up). The column lands now so `/me` and members can *read* it the moment
  anyone (e.g. a future admin tool) writes it.

---

## 1. `GET /api/me` — the caller's own profile

The identity read the whole app is missing. Any authenticated actor (`requireAuth` — no org, no
role bar). Powers "signed in as alice@… — OWNER in this org" and the role-aware permission display.

```
GET /api/me
```

`200 OK` (`MeResponse`):

```json
{
  "user_id": "…uuid…",
  "email": "alice@acme.test",
  "display_name": "Alice Adams",
  "actor_type": "USER",
  "active": true,
  "system_roles": ["ADMIN"],
  "org_roles": [
    { "org_id": "…uuid…", "role": "OWNER" },
    { "org_id": "…uuid…", "role": "VIEWER" }
  ],
  "impersonation": { "read_only": false }
}
```

- **[BACKEND DECISION — identity from DB, roles from the token]** `email` / `display_name` /
  `active` are **read fresh from `app_user`** by `actorId` (they are not in the JWT — one cheap
  keyed read). `system_roles` / `org_roles` come from the **live `SecurityContext`** (already parsed
  from the JWT and `token_version`-validated by `JwtAuthFilter`, so they cannot be stale — a
  revoked role bumps `token_version` and kills the session). No second DB round-trip for roles.
- **`org_roles`** is **flattened** to one `{org_id, role}` per row. A user holding two roles in one
  org (allowed by the `user_org_role` PK) yields two rows for that org; the frontend renders the
  **highest** (`OWNER > MANAGER > STAFF > VIEWER`). This mirrors exactly what the JWT carries.
- **`impersonation`** exposes the SUPPORT view-as overlay so the frontend can render the calm
  "You're viewing as support — read only" banner and **disable write affordances** instead of
  letting every save bounce (see §Read-only impersonation). When
  `SecurityContext.impersonatorId != null`: `{ read_only, impersonator_id, tier, scope_org }`;
  otherwise `{ read_only: false }`.
- **Customers** never reach this — they don't authenticate (no `app_user` row). Only `USER` (and
  the internal `SERVICE`) actor types have a session.
- No org scoping, no 404 — it is *your* row. `401` only if unauthenticated.

**Home:** a new `MeServlet` mounted at `/api/me/*` (mirrors `AuthServlet`'s standalone mount),
dispatching `GET /` here and `POST /password` below. (Kept off `/api/auth/*` because it is
identity-of-self, not credential exchange — but a reviewer may prefer `/api/auth/me`; either mount
is fine, the path string in the contract is what the frontend binds to.)

---

## 2. `POST /api/me/password` — self password change

```
POST /api/me/password
{ "current_password": "…", "new_password": "…" }
```

`requireAuth` (any authenticated `USER`). Verifies the current password before rotating — the
security section of Profile.

- **Flow** (one txn): load `app_user` by `actorId`; `PasswordHasher.verify(current_password,
  password_hash)` — mismatch ⇒ **400** `"current password is incorrect"`; validate `new_password`
  (**[BACKEND DECISION]** ≥ 8 chars, ≠ current — reject weak/no-op early with 400); `PasswordHasher.hash`
  → `updatePasswordHash`; `incrementTokenVersion`.
- **[BACKEND DECISION — log out other devices, keep this one]** Rotating the password bumps
  `token_version` (like `UserAdminService.resetPassword` → `propagateLogoutAll`), which invalidates
  **every** existing session. To avoid logging the user out of the device they just changed the
  password on, the response **re-issues a fresh `access_token` + `refresh_token` cookie pair** for
  the current session at the new `token_version` (new family). Net effect = the industry-standard
  "changing your password signs you out everywhere else." `204 No Content` with the two `Set-Cookie`
  headers.
- **[BACKEND DECISION — blocked under impersonation]** If `impersonatorId != null` (any
  impersonation, read-only or not) ⇒ **403** `"Cannot change password while impersonating"`. A
  SUPPORT overlay must never rotate the target's credential. (Read-only impersonation already 403s
  all writes; this makes the full-impersonation case explicit too.)
- **New service:** `AuthService.changePassword(actorId, current, next)` — reuses `PasswordHasher`,
  `updatePasswordHash`, `incrementTokenVersion`, `propagateLogoutAll`, and the cookie writers
  (`AuthCookies.writeAccess/writeRefresh`) already used by `login`/`refresh`.

| Status | Cause |
|---|---|
| `204` | rotated; other sessions revoked; current session re-cookied |
| `400` | missing field · `new_password` weak (<8) or == current · `current_password` incorrect |
| `401` | unauthenticated |
| `403` | called while impersonating |

---

## 3. `GET /api/orgs/{orgId}/members` — the team roster

New `members` sub-resource → `MemberHandler` (one map entry in `OrgServlet.init()`).

```
GET /api/orgs/{orgId}/members
```

- **[BACKEND DECISION — MANAGER read]** `AuthzHelper.requireOrgAccess(orgId, MANAGER)`. Reading the
  team is a lead's read; **role edits below are OWNER-only.** (VIEWER/STAFF get 403 — Settings hides
  the whole Members card for them, see §Authorization.) This is the one read in the app above VIEWER;
  the frontend's `availableActions()` must gate the Members nav on MANAGER+.
- **No pagination** — an org team is bounded and small (same call as the reservations reads). Sort
  **`email ASC`**. A `?q=` substring filter is **[OUT — deferred]**.
- **Member row** — one row per **user**, roles aggregated:

```json
{
  "data": [
    {
      "user_id": "…uuid…",
      "email": "alice@acme.test",
      "display_name": "Alice Adams",
      "roles": ["OWNER"],
      "active": true,
      "created_at": "2026-01-04T10:15:30Z"
    }
  ]
}
```

- **`roles`** is the **array** of every role the user holds in this org (`user_org_role` allows more
  than one). Normally one; the frontend renders the highest as the primary chip. `created_at` is the
  `app_user` creation time (not a join date — there is no membership-created column; **[BACKEND
  DECISION]** we do not add one this slice).
- **`active`** is the `app_user.active` flag — a disabled account still listed, greyed.
- **New repository read:** `List<OrgMember> findMembers(orgId)` — join `user_org_role` → `app_user`
  on `org_id = :orgId`, aggregating roles per user (`array_agg(role)` or group in Java). This is the
  member-list query the codebase lacks (`findActiveUserIdsByOrgAndRoles` returns only UUIDs for
  notification fan-out and filters by role — not reusable here).

---

## 4. `POST /api/orgs/{orgId}/members` — add / invite a member

```
POST /api/orgs/{orgId}/members
{ "email": "bob@acme.test", "role": "STAFF" }
```

`requireOrgAccess(orgId, OWNER)`. Grants `role` in `:orgId` to the account with `email`.

- **[OPEN — confirm: invite scope].** Two honest v1 shapes; **recommend (a)**:
  - **(a) attach-existing-only (recommended v1).** If a `USER` `app_user` with that email exists →
    grant the role. If not → **404** `"No user with that email"`. Bringing in a brand-new person
    (create account + email a set-password link) is deferred to Phase 2 below. The invite form
    (email + role) works unchanged; it just can't conjure accounts yet.
  - **(b) full invite.** Unknown email → create an `app_user` (no password), grant the role, and
    email a magic-link to set a password. This needs a **new** `magic_token` capability
    (`ACCEPT_INVITE`) + a public `GET|POST /api/public/invite/{token}` set-password endpoint + a
    welcome email template — a materially bigger slice. Reuses `MagicLinkService` /`EmailSender`
    /`NotificationService`, but it is its own story-sized surface.
- **On grant** (existing user): insert `user_org_role`; **[BACKEND DECISION]** bump the invitee's
  `token_version` + `propagateLogoutAll` so their next login/refresh mints a token that *includes*
  the new org (org membership is baked into the JWT — without a re-mint they wouldn't see the org
  until natural expiry). Idempotent: already holds `role` in `:orgId` ⇒ **409** `"already a member"`.
- Unknown `role` ⇒ **400**. `201 Created` (or `200`) returning the new/updated member row (§3 shape).
- Reuses `UserRepository.insertOrgRole` + `incrementTokenVersion`; new `UserRepository.findByEmail`
  for the lookup.

---

## 5. `PUT /api/orgs/{orgId}/members/{userId}` — set a member's role

```
PUT /api/orgs/{orgId}/members/{userId}
{ "role": "MANAGER" }
```

`requireOrgAccess(orgId, OWNER)`. **[BACKEND DECISION — set-replaces]** PUT makes `{role}` the
member's **sole** role in `:orgId`: delete every existing `user_org_role` row for `(userId, orgId)`,
insert the one. The org UI treats a member as having exactly one effective role; the multi-role
capability stays a platform-admin nicety, never surfaced here.

- **404** if `userId` is not currently a member of `:orgId` — PUT edits existing members only;
  adding is §4.
- **[BACKEND DECISION — last-owner guard]** If the target currently holds `OWNER` and this change
  demotes them to non-`OWNER`, and they are the **only** `OWNER` in `:orgId` ⇒ **409**
  `"Org must have at least one owner"`. The guard is on the **OWNER count**, not identity — so an
  OWNER *may* demote themselves as long as another OWNER remains (self-lockout falls out of the same
  check, no separate rule).
- **[BACKEND DECISION — de-privilege logout]** Any change that **reduces** the target's authority
  (demotion) bumps their `token_version` + `propagateLogoutAll` — their old, higher `org_roles` are
  baked into their JWT and must die at once (the exact rail `UserAdminService.revokeOrgRole` uses).
  A promotion also re-mints (so the new power appears) — simplest to always bump on a role change.
- Unknown `role` ⇒ **400**. `200 OK` with the updated member row.

---

## 6. `DELETE /api/orgs/{orgId}/members/{userId}` — remove a member

```
DELETE /api/orgs/{orgId}/members/{userId}
```

`requireOrgAccess(orgId, OWNER)`. Deletes **all** `user_org_role` rows for `(userId, orgId)` — the
user loses access to the org (the `app_user` row itself is untouched; they may still belong to other
orgs / have a system role).

- **[BACKEND DECISION — last-owner guard]** Removing the sole remaining `OWNER` ⇒ **409**
  `"Org must have at least one owner"` (same count check as §5). An OWNER may remove themselves iff
  another OWNER remains.
- **[BACKEND DECISION — de-privilege logout]** On removal, bump the target's `token_version` +
  `propagateLogoutAll` — their JWT still lists the org until it dies.
- **404** if not a member. `204 No Content` on success.

---

## Read-only impersonation (the write-heavy-screen constraint)

A SUPPORT "view-as" session carries `impersonationReadOnly` and `AuthzHelper.requireOrgAccess`
already throws **403 `"Read-only impersonation cannot perform writes"`** for any `minRole > VIEWER`
(`AuthzHelper.java:100-102`). So §4/§5/§6 (OWNER) and the org-config save all bounce automatically —
correct, but a wall of 403s is a poor UX. `GET /me`'s `impersonation.read_only` is the signal: the
frontend shows the calm banner and **renders every write affordance disabled** rather than letting
saves fail. The backend guarantee stands regardless of the UI; the flag just lets the UI be honest.

---

## Authorization summary

| Endpoint | Bar | Notes |
|---|---|---|
| `GET /api/me` | `requireAuth` | any authenticated actor; no org scope |
| `POST /api/me/password` | `requireAuth` | 403 if impersonating |
| `GET /members` | `requireOrgAccess(MANAGER)` | the app's only above-VIEWER **read** |
| `POST /members` | `requireOrgAccess(OWNER)` | 403 under read-only impersonation |
| `PUT /members/{userId}` | `requireOrgAccess(OWNER)` | last-owner 409; de-privilege logout |
| `DELETE /members/{userId}` | `requireOrgAccess(OWNER)` | last-owner 409; de-privilege logout |

`availableActions()` on the frontend is the sole source of every button: **VIEWER/STAFF** — no
Members nav, read-only Organization; **MANAGER** — Members *read* (roster, no edit); **OWNER** —
Organization edit + member add/edit/remove. System ADMIN bypasses org checks (can act in any org).

---

## Scope

### In
- **V48** migration + `AppUser.displayName` + jOOQ regen.
- `MeServlet` (`/api/me/*`): `GET /` (`MeResponse`), `POST /password`.
- `AuthService.changePassword(...)` (reuses hasher / token-version / cookie writers).
- `MemberHandler` (new `members` sub-resource in `OrgServlet.init()`) + `MemberService` with the
  last-owner guard and de-privilege-logout wiring.
- `UserRepository`: `findMembers(orgId)`, `findByEmail(email)`, plus reuse of `insertOrgRole` /
  `deleteOrgRole` / `deleteAllOrgRoles(userId, orgId)` / `incrementTokenVersion`.
- Wiring in `AppConfig` (compose `MeServlet`, `MemberHandler`/`MemberService`); mount `MeServlet` in
  `EmbeddedTomcatLauncher`.
- Docs: CLAUDE.md endpoint list (this story's §CLAUDE.md additions).

### Out (deferred)
- **Full invite-by-email for brand-new accounts** (§4 option b) — account creation + `ACCEPT_INVITE`
  magic-link + public set-password endpoint + welcome email. Its own story if confirmed.
- **`PATCH /api/me {display_name}`** — self-rename. Column lands now; the write is a follow-up.
- **Members pagination / `?q=` search** — teams are small; add when an org outgrows one screen.
- **Membership `joined_at`** — no column added; `created_at` is the account's, not the membership's.
- **Multi-role editing in the org UI** — PUT is set-replaces (one effective role); multi-role stays
  a platform-admin capability.

---

## Tests

- `MeServlet` / `AuthService.changePassword` (api IT, TestContainers):
  - `GET /me` returns email + display_name + flattened org_roles + system_roles; roles match the
    token; `impersonation.read_only` true under a read-only overlay.
  - password change: wrong current ⇒ 400; weak/`==current` new ⇒ 400; success ⇒ 204, other sessions
    dead (old `token_version` rejected), current session's re-issued cookies still valid; called
    while impersonating ⇒ 403.
- `MemberHandler` / `MemberService` (api IT):
  - `GET /members` — MANAGER sees roster with aggregated roles + active; STAFF/VIEWER ⇒ 403;
    non-member ⇒ 403; another org's member absent (scoping).
  - `POST /members` — OWNER attaches existing user (grant + token bump); unknown email ⇒ 404 (v1);
    already-member ⇒ 409; unknown role ⇒ 400; read-only impersonation ⇒ 403.
  - `PUT /members/{userId}` — set-replaces to one role; demoting the **last** OWNER ⇒ 409; demoting
    a non-last OWNER succeeds and fires logout-all on the target; non-member ⇒ 404.
  - `DELETE /members/{userId}` — removes all roles ⇒ 204; removing the last OWNER ⇒ 409; target's
    sessions die.

---

## Acceptance (what "done" means)

- [x] V48 adds `display_name`; `AppUser` + jOOQ carry it; `/me` and members read it (null → omitted).
- [x] `GET /me` returns identity (DB) + roles (token) + impersonation signal; `requireAuth` only.
- [x] `POST /me/password` verifies current, rotates, revokes other sessions, keeps current alive,
      403s under impersonation.
- [x] `GET /members` (MANAGER) lists the org's users with aggregated roles + active; org-scoped 403.
- [x] `POST/PUT/DELETE /members` (OWNER) add/set/remove with the **last-owner 409 guard** and
      de-privilege **logout-all** on every authority reduction.
- [x] Read-only impersonation blocks all member/config writes (existing `requireOrgAccess` guard) and
      `/me` surfaces the flag so the UI disables rather than bounces.
- [x] All snake_case, ISO-8601 UTC; CLAUDE.md updated.

---

## CLAUDE.md additions (applied)

New **Self (`/api/me`)** subsection and four **members** bullets under Org-scoped resources — see the
committed CLAUDE.md. The role-hierarchy footer notes the two exceptions this story introduces:
members **read** requires **MANAGER** (not VIEWER), and member **writes** require **OWNER**.
