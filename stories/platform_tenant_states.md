# Fix: a tenant pending verification is not a suspended tenant

> Slice 1.5 of the platform-console epic — it corrects a number slice 1 shipped, so it runs
> **before** the cross-org queues. Branch `126_fix/tenant-states`, cut from **`master`** (slice 1
> merged as PR #124, so `tenantCounts()` and the perfdb runbook are both there).
> Frontend pair: `frontst` branch `73_fix/tenant-states` (story 76) — land this first.
> **No migration.** `suspended_at` / `suspended_reason` have existed since V43; nothing has ever
> read them back.

---

## The reality this corrects

The overview's `suspended` tile is `active = false`. That is exactly what the org list's
`?status=suspended` filter reduces to, and preserving that parity was the right call when slice 1
shipped — a tile and its drill-down disagreeing is the worse bug.

But `active = false` is two states wearing one label:

- an org an ADMIN **suspended** (`setSuspension` stamps `suspended_at` + `suspended_reason`), and
- an org **born inactive at self-serve registration** whose owner never clicked the verification
  link (`AccountService:134` — `org.setActive(false)`, story 88).

The schema already tells them apart, deliberately:
`OrgRepositoryImpl.activateRegistrationPendingOrgs` keys on `active = false AND suspended_at IS
NULL` with the comment *"setSuspension always stamps it, so the two states can't be confused."* The
platform console is the one place that confuses them. Observed on a live backend: a freshly
registered org rendered `active: 0, suspended: 1`.

**This gets worse with traffic, which is why it goes first.** Every self-serve registration mints a
pending org, and the purge job only reaps never-verified accounts after 7 days. On a platform with
open registration the "suspended" tile trends toward mostly-abandoned-signups — a red operational
signal made of noise. Slice 1's own rule was that a console which lies is worse than none; this is
that rule applied to slice 1.

**Second finding, folded in because the fix needs it anyway:** `Org` carries no `suspendedAt` or
`suspendedReason`. `toOrg` maps neither. So the reason an ADMIN types when suspending a tenant is
written to the database and readable by nobody — `POST /suspend {reason}` has always been
write-only. Reading `suspended_at` is precisely what distinguishes the two states, so the model
gains the field regardless; carrying `suspended_reason` beside it is one more line and closes a gap
that would otherwise need its own slice.

## The change

### One definition of the rule

A new domain enum `OrgStatus { ACTIVE, SUSPENDED, PENDING }` with the derivation as a static factory
on the enum itself:

| status | predicate |
|---|---|
| `ACTIVE` | `active = true` |
| `PENDING` | `active = false AND suspended_at IS NULL` |
| `SUSPENDED` | `active = false AND suspended_at IS NOT NULL` |

These **partition** the table — every org is in exactly one, there is no fourth case, and
`reactivate` clears `suspended_at` so a reactivated org returns cleanly to `ACTIVE`. Write the
derivation once and have both the SQL filters and the DTOs consume it; two statements of this rule
is how the tile and the list drifted in the first place.

### The server names the state; no client re-derives it

`AdminOrgSummaryResponse` **replaces** `active` with `status`. The frontend currently derives its
label from the boolean (`orgStatusOf(active)`), which is a second copy of a rule that just proved it
can be wrong. Deriving state from a boolean the server also derives from is the shape of this bug;
delete the derivation rather than teach it a third case.

`AdminOrgDetailResponse` additionally carries `status` plus `suspendedAt` and `suspendedReason`
(the latter two null unless `SUSPENDED`), so the detail page can say *why* and *since when*.
`status` belongs there as a **top-level sibling**, not left to the embedded org: without it the
detail page would have to derive from `org.active`, which is the exact derivation this slice bans.

**Admin DTOs only — not the shared `OrgResponse`.** The tenant-facing `GET /api/orgs/{orgId}` has no
business carrying the platform's internal suspension note, and members of a suspended org are 403'd
by `requireOrgAccess` anyway. Keeping the field out of the shared DTO keeps the diff tight and the
note private to the plane that wrote it.

### Filters

`OrgRepository.findAll(offset, limit, Boolean active)` and `count(Boolean active)` take `OrgStatus`
(nullable = all) instead. **Delete the `Boolean` overloads rather than adding beside them** — a
`Boolean active` filter is exactly the ambiguity being removed, and deleting it makes the compiler
find every caller, which is how this codebase prefers to be refactored.

`OrgAdminHandler.parseStatus` accepts `active|suspended|pending`; unknown → 400 naming all three
(today it names two).

`PlatformTenantCounts` gains `pending`, and `tenantCounts()` gains a fourth `FILTER` clause. Parity
with the list holds by construction, because both sides now consume the same enum.

### Say that the number moves

On any platform with self-serve traffic, `suspended` will drop and `pending` will absorb the
difference. That is the fix working. Record the before/after figures from a real database in the PR
body so the change is visible as a correction rather than discovered later as a mystery.

## Tests

`PlatformTenantStatesIT` (api module, TestContainers):

- `everyOrgIsInExactlyOneStatus` — a fixture with all three; `active + suspended + pending == total`
  and no org appears under two filters. The partition is the whole design.
- `aPendingOrgIsNotCountedAsSuspended` — the bug. Register an org, assert `pending = 1` and
  `suspended = 0` on the overview **and** that `?status=pending` returns it while
  `?status=suspended` does not.
- `suspendedOrgReadsBackItsReasonAndTimestamp` — the write-only gap closed; `reason` survives to the
  detail read.
- `reactivateClearsTheSuspensionAndReturnsToActive`.
- `suspendingAPendingOrgReadsAsSuspended_andLaterVerificationDoesNotReactivateIt` — the edge that
  makes the partition real: once `suspended_at` is stamped,
  `activateRegistrationPendingOrgs` (which requires `suspended_at IS NULL`) correctly leaves it
  alone. An admin suspension outranks a verification click.
- `listTotalsEqualTheOverviewTiles` — parameterised over all three statuses; the parity pin, now
  covering the state that did not previously exist.
- `unknownStatusIs400NamingAllThree`.

Existing ITs that assert `active` on the summary DTO will fail to compile against the new field —
that is the refactor working, not collateral. Update them; do not add a compatibility field.

## Definition of done

- [ ] `mvn -o test` green; full `*IT` battery green (`DOCKER_HOST` at the colima socket).
- [ ] `mvn spotless:apply` clean.
- [ ] Before/after tenant figures from a real database in the PR body.
- [ ] `CLAUDE.md` §Platform admin updated: `?status=` now takes three values, and the org summary
      carries `status` rather than `active` (gitignored here).
