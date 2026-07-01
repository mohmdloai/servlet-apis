# Impersonation — Two-Tier, Audited "Act-As"

> Design doc for the impersonation slice. Read before touching `AuthService`, `JwtUtil`,
> `JwtAuthFilter`, or `SecurityContext`. Grounded in the platform-vs-org authorization model
> (`SystemRole` cross-cut vs `OrgRole` ladder) and the existing layered-module conventions
> (`domain → repository → service → api`).

**Status:** Not built. This captures the design so it's ready to implement as one slice. The
token/session machinery it needs already exists — impersonation is *additive* and touches no
existing login/refresh path except to add one optional JWT claim.

## Why this exists

Two authority gaps, one mechanism:

1. **Platform scope.** `SystemRole{ADMIN, SUPPORT}` is fully decoded (`JwtAuthFilter` →
   `SecurityContext`) but only ever used as an org-access *bypass*. There is no way for a platform
   operator to *act as* a tenant user to reproduce and fix their problem. `SUPPORT` is decoded and
   gated on **nowhere** — it needs a purpose, and safe read-only "view-as" is exactly it.
2. **Org scope.** An `OrgRole.OWNER` needs to step into a subordinate's shoes *inside their own
   org* — to see what a STAFF user sees, or to act on their behalf — without knowing their password
   and without escaping the org.

Both are the same primitive: **swap who you are (`sub`) while permanently recording who you really
are (`act`)**. And both must be **auditable** — the whole point is accountability.

## The core principle: an access-token overlay, not a login

Impersonation mints **one short-lived access token** carrying the target's identity. The real
driver's session — their `refresh_token` cookie, their Redis refresh family — is **never touched**.
That single choice makes everything fall out cleanly: going back is trivial, and even total failure
just expires harmlessly (there is no server-side "impersonation session" to clean up).

Every access token already carries the principal in `sub`. Impersonation adds claims:

| Claim | Meaning |
|---|---|
| `sub` | the **target** user's id — their roles, status, token_version govern. Every existing RBAC gate (`requireOrgAccess`, `isOwnerOrAdmin`, `RANK`) Just Works, unchanged. |
| `act` | the **real driver's** id — the only trace, in the token, that someone else is behind the wheel. |
| `act_tier` | `PLATFORM` \| `ORG` — which authority was exercised to start it. |
| `act_scope_org` | `ORG` tier only — the single org the overlay is confined to. |
| `act_mode` | `READONLY` for a SUPPORT view-as; absent means full write. |

`token_version` in the overlay is the **target's** current version. Consequence, for free: if the
target does `logout-all` (or is force-logged-out), `JwtAuthFilter.isTokenVersionValid` fails and the
overlay dies instantly. Revocation of impersonation is revocation of the target.

## The two tiers

Impersonation runs on the same two authority axes as the rest of the system — the platform
cross-cut and the per-org ladder — so there are two tiers, not one.

| | **Platform-tier** | **Org-tier** |
|---|---|---|
| Who may start it | `SystemRole` ADMIN (write) / SUPPORT (read-only) | `OrgRole.OWNER` of the org |
| Endpoint | `POST /api/admin/impersonate/{userId}` (platform plane) | `POST /api/orgs/{orgId}/impersonate/{userId}` |
| Overlay grants | target's identity **across all their orgs** | target's identity **only inside `{orgId}`** |
| `act_tier` | `PLATFORM` | `ORG` (+ `act_scope_org = {orgId}`) |
| Never grants | any `system_roles` | any `system_roles`; any org **other than `{orgId}`** |

### The hazard org-tier introduces: cross-tenant leak

A target user can belong to **multiple orgs** — `orgRoles` is `Map<orgId, Set<OrgRole>>`. If org-tier
impersonation naively set `sub = target` and loaded the target's **full** `orgRoles`, then the OWNER
of `acme` impersonating a user who *also* works at `globex` would silently inherit `globex` access.
That is cross-tenant privilege escalation.

**Therefore org-tier impersonation mints a *scoped* overlay:** the token's `org_roles` is filtered to
`{ orgId: <target's roles in orgId> }` — every other org dropped, `system_roles` dropped. Platform-tier
can be an unscoped "become the user everywhere" overlay because ADMIN already holds global authority;
even so, it carries **zero `system_roles`** — an impersonation must never itself be a platform actor.

The scoping is baked in **at mint time in `AuthService`**. `JwtAuthFilter` just trusts the token — it
never re-derives the target's roles. This keeps the security-critical decision in one place.

## SecurityContext & filter changes

Three new nullable fields on the `SecurityContext` record:

```java
public record SecurityContext(
    UUID actorId,                       // = sub = the target during an overlay
    ActorType actorType,
    Set<SystemRole> systemRoles,        // always empty during an overlay
    Map<UUID, Set<OrgRole>> orgRoles,   // ORG tier: exactly one org
    Set<String> allowedActions,
    int tokenVersion,
    UUID impersonatorId,                // act — null on a normal session
    ImpersonationTier impersonationTier,// PLATFORM | ORG | null
    boolean impersonationReadOnly) {    // act_mode == READONLY
```

`JwtAuthFilter` lifts `act`/`act_tier`/`act_scope_org`/`act_mode` into those fields. Present ⇒ overlay
active; absent ⇒ every impersonation check is a no-op. Nothing downstream changes shape.

`JwtUtil.generateAccessToken` gets an **overload** that accepts the `act*` values and an optional
shorter TTL (impersonation tokens should be tighter than the 15-min default — ~5 min bounds the blast
radius). The existing signature stays for login/refresh.

## Guard rails (all in `AuthService`, mirroring the login-path defensiveness)

**Both tiers:**
- **No nesting** — reject if the caller's own token already carries `act`.
- **Not self** — reject `caller == target`.
- Target must be **ACTIVE** (`AppUser.isActive()`).
- Mint an **access token only** — no `refreshTokenStore.store(...)`, no refresh cookie.

**Platform tier:**
- Caller has `SystemRole.ADMIN` (full write) or `SystemRole.SUPPORT` (⇒ `act_mode = READONLY`).
- Target is **not** a system admin (admin→admin grants nothing; forbid it). Defensively strip
  `system_roles` from the overlay regardless.

**Org tier:**
- Caller's **real** `OrgRole` in `{orgId}` is `OWNER` — checked *without* the system-admin bypass (a
  platform admin uses the platform tier, not this one).
- Target is a **member** of `{orgId}`.
- Target's max rank in `{orgId}` is **strictly below** the caller's (via `AuthzHelper.RANK`): an
  OWNER may impersonate MANAGER/STAFF/VIEWER but **not** another OWNER.
- Overlay `org_roles = { orgId: target-roles-in-orgId }`, `system_roles` empty.

## Stopping — two ways home, both safe by construction

`POST /api/auth/stop-impersonating`:
1. Reads `act` from the current token; 400 if absent ("Not impersonating").
2. Reloads that admin from the DB and re-verifies they're still ACTIVE (+ still ADMIN for a platform
   overlay — guards against being suspended mid-impersonation).
3. Re-signs the admin's **own** access token with **no** `act` claim and returns it.

The fallback is free: the overlay is short-lived and the admin's `refresh_token` cookie was never
disturbed, so clearing the access cookie and hitting `/refresh` also lands back on the admin. There
is nothing server-side to tear down.

## Read-only enforcement (SUPPORT view-as)

When `impersonationReadOnly` is true, only safe reads are permitted. A single choke point in
`AuthzHelper` — consulted by the write guards (`requireOrgAccess` at STAFF+/MANAGER, the
`isOwnerOrAdmin` money gates) — throws `403` on any mutating call. Reads (VIEWER) pass. This makes
SUPPORT the safe debugging tool: it can *see* exactly what the user sees and change nothing.

## Audit — two layers, matched to this codebase's explicit-actor design

The reference implementation this is modelled on uses `AsyncLocalStorage` to smuggle the impersonator
into every audit row, because its actor is an *implicit* request context. **This system is the
opposite** — the multi-tenancy rule is "explicit actor parameters, no thread-locals," and the actor
already flows to every layer inside `SecurityContext`. So there is no ALS equivalent to build: the
impersonator is *already in scope everywhere `SecurityContext` is*.

### Layer 1 — explicit start/stop events (new table, migration `V41`)

```sql
CREATE TABLE impersonation_event (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    impersonator_id UUID        NOT NULL REFERENCES app_user(id),
    target_id       UUID        NOT NULL REFERENCES app_user(id),
    tier            VARCHAR(16) NOT NULL,             -- 'PLATFORM' | 'ORG'
    scope_org_id    UUID        REFERENCES org(id),   -- NULL for PLATFORM tier
    event           VARCHAR(16) NOT NULL,             -- 'START' | 'STOP'
    reason          TEXT,                             -- optional, house-style {reason}
    source_ip       VARCHAR(64),
    user_agent      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_impersonation_event_target ON impersonation_event (target_id, created_at);
CREATE INDEX ix_impersonation_event_actor  ON impersonation_event (impersonator_id, created_at);
```

`source_ip`/`user_agent`/`created_at` come from the `Environment` the filter already attaches. Start
takes an optional `{reason}`, consistent with `cancel`/`void`.

### Layer 2 — stamp the audit rows that already exist

Any domain that writes a ledger/log row (today: `inventory_log`) also persists `impersonator_id` when
`SecurityContext.impersonatorId != null`. The ledger's principal stays the **target** — the row still
reads "Bob did X" (correct: Bob's identity acted) — while `impersonator_id` answers "…but admin Z was
holding the keyboard." On a normal session the column is null and nothing changes.

> **Deliberately out of scope:** a *generic* cross-cutting `audit_log` spine that every mutating
> service writes to. This system doesn't have one yet — only per-domain logs. Building it is its own
> slice; this one stamps what already exists and adds the dedicated `impersonation_event` ledger. When
> the generic spine lands, `impersonator_id` stamping extends to it for free (it's already in
> `SecurityContext`).

## Security invariants (non-negotiable)

1. Overlay carries **no `system_roles`, ever** — an impersonation can't perform platform ops.
2. Org-tier overlay `org_roles` is **filtered to the one scope org** — no cross-tenant leak.
3. **No refresh token** minted — overlay is throwaway; blast radius bounded by a short access TTL.
4. `token_version` = the **target's** — the target's `logout-all` kills the overlay instantly.
5. No nesting, not self, target ACTIVE; platform: target not a system admin; org: target strictly
   below the OWNER caller.
6. Every mutating action under an overlay is attributable via `act` → `impersonatorId`, persisted in
   whatever ledger the action writes.

## API surface

| Method + path | Auth | Effect |
|---|---|---|
| `POST /api/admin/impersonate/{userId}` | `SystemRole` ADMIN / SUPPORT | Mint a PLATFORM overlay (SUPPORT ⇒ read-only). Body `{reason?}`. Sets **access cookie only**; returns `{impersonator_id, tier, read_only}` for the "acting as…" banner. |
| `POST /api/orgs/{orgId}/impersonate/{userId}` | OWNER of `{orgId}` | Mint an ORG overlay scoped to `{orgId}`. Body `{reason?}`. Access cookie only. |
| `POST /api/auth/stop-impersonating` | any overlay session | Re-sign the driver's own token (no `act`); returns `{impersonator_id: null}`. |

`/api/admin/impersonate/*` is the **first real cross-org platform route** beyond the expiry sweep — it
belongs on the platform plane alongside `AdminSweepServlet` (see the platform-scope work).

## Implementation plan (file by file)

1. **domain** — add the three fields to `SecurityContext`; add `ImpersonationTier` enum; a
   `startedImpersonation`/scoping helper if useful. `UserRepository` gains a "roles in one org" +
   "is member of org" read if not already covered by `findOrgRoles`.
2. **common** — `JwtUtil.generateAccessToken` overload with `act*` claims + optional TTL.
3. **repository** — `ImpersonationEventRepository` (insert START/STOP); `V41` migration above;
   add nullable `impersonator_id` to `inventory_log` (+ any other ledger) via the same migration.
4. **service** — `AuthService.impersonate(callerCtx, targetId, tier, scopeOrgId, reason, env)` with
   all guard rails + scoped-overlay minting; `AuthService.stopImpersonating(callerCtx, env)`; write
   Layer-1 events. Stamp `impersonatorId` where `inventory_log` rows are written.
5. **api** — new platform impersonation route (sibling of `AdminSweepServlet`); an
   `ImpersonationHandler` off `OrgServlet` for the org-tier route; `stop-impersonating` in
   `AuthServlet`; lift `act*` in `JwtAuthFilter`; the read-only write-gate in `AuthzHelper`; reuse
   `handleLogin`'s cookie writer but **skip the refresh cookie**.
6. **tests (api IT)** — cross-org leak blocked (org-tier, multi-org target sees only `{orgId}`);
   no-nesting; not-self; OWNER-can't-impersonate-OWNER; SUPPORT overlay 403s on write, 200s on read;
   overlay dies on target `logout-all`; stop returns to the admin identity; `impersonation_event`
   rows written START+STOP; `inventory_log` action under overlay carries `impersonator_id`.

## The model in one line

Impersonation swaps **who you are** (`sub`) while permanently recording **who you really are**
(`act` → `impersonatorId` in every audit row) — a throwaway access-token overlay on top of the
driver's untouched refresh session, scoped by tier: platform ADMIN/SUPPORT act across all orgs, an
org OWNER acts only within their own, and neither ever becomes a platform actor.
