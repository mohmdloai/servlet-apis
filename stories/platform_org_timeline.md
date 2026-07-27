# Per-org timeline — what has been done to this tenant, and by whom

> Slice 4 of the platform-console epic. Branch `131_feat/platform-org-timeline`, cut from **`master`**
> (highest PR is #130 — re-verify at implementation time; the epic's stale-e2e fix may take a number
> first). Frontend pair: `frontst` branch `76_feat/platform-org-timeline` (story 78) — **land this
> backend first**.
> **Migration: V76, and it is the point of the slice, not a side effect** (see §"The ledger is not
> tenant-shaped").
>
> Slice 3 answered "whose is this identifier". This answers the question the operator asks
> *immediately after* opening the tenant it named: **what has happened to this account?**

---

## The reality this corrects

`GET /api/admin/orgs/{orgId}` gives an operator a rollup: counts, config, and — since slice 1.5 — a
status story that says *why* the tenant is suspended and *since when*. That is the current state and
nothing else. There is no way to ask "who suspended it, and had anyone touched it before that", or
"when did this person get OWNER", or "did anyone from support look inside this tenant last week".

The data exists. `platform_audit` (V42) has recorded every mutating platform action since the admin
plane shipped, and `impersonation_event` (V41) has recorded every overlay. Neither is readable
per-tenant: the audit ledger is paged newest-first across the whole platform with `?target_type=` and
`?actor_id=` filters and **no `?target_id=`**, and the impersonation ledger has no read endpoint at
all.

## The ledger is not tenant-shaped, and that is the finding

The obvious build is "filter `platform_audit` by the org". It does not work, and the reason is
structural rather than a missing parameter. Every audit row is written with an **actor** and a
**target**, and for a third of the vocabulary the target is the *user*, not the tenant:

| action | `target_type` | `target_id` | is the org recoverable? |
|---|---|---|---|
| `ORG_CREATE` | `ORG` | orgId | ✅ directly |
| `ORG_UPDATE` | `ORG` | orgId | ✅ directly |
| `ORG_SUSPEND` · `ORG_REACTIVATE` | `ORG` | orgId | ✅ directly |
| `ORG_ROLE_GRANT` · `ORG_ROLE_REVOKE` | **`USER`** | userId | ⚠️ only as `detail->>'org_id'` |
| `USER_CREATE` (via org provisioning) | **`USER`** | userId | ⚠️ not in its own row — but **deterministically joinable**, see below |
| `USER_CREATE` (plain, `UserAdminService`) · `USER_ENABLE` · `USER_DISABLE` · `PASSWORD_RESET` · `FORCE_LOGOUT_ALL` | `USER` | userId | ❌ platform-wide by nature; correctly not a tenant event |
| `SESSION_REVOKE` | `SESSION` | familyId | ❌ platform-wide; the user is in `detail`, no org exists |
| `SYSTEM_ROLE_GRANT` · `SYSTEM_ROLE_REVOKE` | `USER` | userId | ❌ platform-wide; correctly not a tenant event |

So a naive `WHERE target_type = 'ORG' AND target_id = ?` returns the tenant's lifecycle and **omits
every membership change to it**. "Who was given access to this tenant, and when" is not a footnote
of a support timeline — for an access-review question it is the whole thing. A timeline that
silently drops it is the console-that-lies failure with better manners.

Two ways to recover the org, and only one of them is honest long-term:

- **Query `detail->>'org_id'` at read time.** Works for the two role actions, costs an expression
  index, does nothing for the provisioned owner, and leaves the next person writing the next audited
  action free to forget again — because nothing in the schema asks them the question.
- **Give `platform_audit` an `org_id` column.** One definition, in the place a reader and an index
  can both see it. This is the epic's recurring fix — *the same rule written twice is the defect;
  one definition with two callers is the cure* — applied to a rule that is currently written zero
  times and re-inferred at every read site.

**Take the column.** `ix_platform_audit_target (target_type, target_id, created_at)` cannot serve a
JSONB probe, and the alternative is a second index on an expression that would still not cover the
whole vocabulary.

### The provisioned owner is recoverable, and the join is recorded rather than guessed

It is tempting to write this row off — its detail is `{email, via:"org_provision"}` and there is no
org in it. But the *pairing* is explicit, not inferential. `PlatformOrgService` writes both rows in
one transaction, and the `ORG_CREATE` carries
`{slug, owner_id, owner_minted}` — the owner's id **and** a boolean saying whether that owner was
minted right then, which is true exactly when the `USER_CREATE` was written.

```sql
UPDATE platform_audit u
   SET org_id = o.target_id
  FROM platform_audit o
 WHERE u.org_id IS NULL
   AND u.action = 'USER_CREATE'
   AND u.detail->>'via' = 'org_provision'
   AND o.action = 'ORG_CREATE'
   AND o.detail->>'owner_minted' = 'true'
   AND (o.detail->>'owner_id')::uuid = u.target_id;
```

**No timestamp window, and no ambiguity to resolve.** `owner_minted = true` means the user did not
exist a moment earlier, and a user is created once — so at most one `ORG_CREATE` row can ever carry
`(owner_id = U, owner_minted = true)`. Provisioning that same user as owner of a *second* org finds
them already present, writes no `USER_CREATE`, and records `owner_minted = false`. The join is a
functional dependency the ledger already stores, which is a different thing from a heuristic that
happens to work on this data.

Do not add a `created_at` proximity clause "for safety". It would not tighten a join that is already
unique, and it would silently drop rows if a future provisioning path ever writes the two entries a
second apart.

**Measure the residual rather than assuming it.** After the backfill, count the
`USER_CREATE … via = 'org_provision'` rows still `NULL` and put the number in V76's header. The
expectation is zero — `ORG_CREATE` has carried `owner_id`/`owner_minted` since the provisioning
slice, and nothing else writes that action — but "expected to be zero" is the class of claim this
epic keeps disproving. If the count is not zero, the header says so and those rows stay `NULL`;
inventing an attribution for them would be the console-that-lies failure in a migration.

### V76

```
ALTER TABLE platform_audit ADD COLUMN org_id UUID REFERENCES org(id);
CREATE INDEX ix_platform_audit_org ON platform_audit (org_id, created_at DESC);
```

Nullable on purpose: `SYSTEM_ROLE_GRANT` and `FORCE_LOGOUT_ALL` concern no tenant, and a
`NOT NULL` here would force a lie. **Backfill what is recoverable and no more:**

- `ORG_*` actions → `org_id = target_id`.
- `ORG_ROLE_GRANT` / `ORG_ROLE_REVOKE` → `org_id = (detail->>'org_id')::uuid`.
- `USER_CREATE` via org provisioning → the recorded join above.
- **Everything else stays `NULL`, and that is the correct answer, not a gap.** `SYSTEM_ROLE_GRANT`,
  `FORCE_LOGOUT_ALL`, `SESSION_REVOKE`, a plain admin-plane `USER_CREATE` — none of these concerns a
  tenant, and a nullable column is how the schema gets to say so. Resist any instinct to attribute
  them to an org the user happens to belong to: "this operator was force-logged-out" is not an event
  that happened to a merchant.

Then **close the hole at the write site**, or the column rots: `PlatformAuditService.record` and
`recordInTx` take the org as a parameter — not an optional overload, a required argument that is
explicitly `null` for platform-wide actions. Every existing call site passes it, and
`PlatformOrgService`'s provisioning `USER_CREATE` passes the org it is provisioning. A parameter you
must decide is the only kind that survives the next contributor.

> Measure the backfill on `perfdb` before writing it, per V73's protocol. `platform_audit` there is
> small, so the expectation is that it is instant and the interesting number is the index size —
> but "expected to be trivial" is exactly the claim this epic keeps disproving, and an unmeasured
> `ALTER TABLE … REFERENCES` on a table you did not check is how a migration blocks a deploy.

## The change

`GET /api/admin/orgs/{orgId}/timeline?page=&size=` — a subpath of the org detail, on the existing
`OrgAdminHandler` beside `/{orgId}`, `/{orgId}/suspend` and `/{orgId}/reactivate`. **Not** a new
top-level resource: the question is "this tenant's history", the URL should say so, and slice 3
established that a route's shape is part of its honesty.

- `requirePlatformRead` — ADMIN and SUPPORT, byte-identical output. Reading a trail is a read, the
  same call the audit ledger already makes.
- `GET` only → 405. Unknown `orgId` → **404**, not an empty page: this is a lookup on a path segment,
  not a filter on a query parameter. That is the opposite of the `?org_id=` rule slice 2 set for the
  queues, and the difference is the point — a path segment names a thing, a query parameter narrows
  a set. State the contrast in the Javadoc so the next person does not "fix" one to match the other.
- **Newest-first, always**, `PageResponse` envelope. This is a ledger, not a queue: the queue-vs-
  ledger convention resolves to its ledger half here for the same reason slice 2's resolved to its
  queue half — nobody works a tenant's history oldest-first, they read the last thing that happened.
  `created_at DESC, id DESC`, matching `PlatformAuditRepositoryImpl` exactly.

### Two ledgers, one stream — and the second one is the honest part

The timeline merges **`platform_audit` (`org_id = ?`)** with **`impersonation_event`
(`tier = 'ORG' AND scope_org_id = ?`)**. V41's own header says the impersonation ledger "can be
folded in later"; a per-tenant history is later.

Leaving it out would be a defensible-sounding omission and a real one: **"who from the platform
looked inside this tenant, and when"** is among the first questions asked when a merchant reports
something they did not do, and it is the one question the audit ledger structurally cannot answer —
an overlay produces *no* `platform_audit` rows, because the operator's actions inside the tenant are
written as the impersonated user. A timeline built from `platform_audit` alone would render a period
of intense support activity as total silence.

`tier = 'PLATFORM'` events carry no `scope_org_id` (V41 pins that with `ck_imp_scope`) and are
correctly absent — they are not about any tenant.

Merge in SQL as a `UNION ALL` over a common projection ordered once, not by fetching two pages in
Java and interleaving them — paging two independently-paged sources and merging client-side produces
a page that is missing rows, which is a truncating filter wearing a different hat.

### Response shape

`PageResponse<AdminOrgTimelineEntryResponse>`, each entry:

```
{ "id", "at", "source": "audit" | "impersonation",
  "action",                       // 'ORG_SUSPEND' | 'IMPERSONATION_START' | …
  "actor": { "id", "email", "display_name"? },
  "detail": { … } | null }
```

- **`source` is on the wire.** The client must be able to say "this is an access event, not an
  administrative one" without pattern-matching on verb strings.
- **`actor` is batch-loaded**, one query for the page, never per row — the `sales_order_number`
  precedent from every worklist in this codebase. `impersonation_event.impersonator_id` is the actor
  for its rows; the impersonated `target_id` goes in `detail`.
- **`detail` ships parsed, as the audit ledger already ships it** (`AuditAdminHandler` re-parses the
  JSONB to a nested object). Do **not** invent a per-action whitelist here. Both surfaces are
  `requirePlatformRead`, both read the same rows, and having one redact what the other displays in
  full is incoherence, not caution. If the audit trail's payloads ever need narrowing, that is one
  change in one place — for both readers.
- **The org's own birth is an anchor, not an entry.** `org.created_at` gets its own top-level field
  on the response envelope, `created_at`, rather than being synthesized into the stream. Reason:
  **self-serve registration writes no audit row at all** (`AccountService` never calls
  `PlatformAuditService`), so most tenants have no `ORG_CREATE` and the timeline's oldest entry is
  routinely *not* the beginning. Synthesizing one would need an actor the row does not have.
  The frontend renders it as the list's terminal cap — "Tenant registered · {date}" — which is true
  for every org, provisioned or self-serve.

### `action` is open text; treat it that way

`platform_audit.action` is `VARCHAR(64)` with no enum behind it, deliberately — the same call V44
made for `notification.type`, so a new audited action needs no migration. The API therefore passes
the verb through verbatim and **never validates it against a known set**. Rendering is the client's
problem and story 78 states the fallback rule. Do not add a Java enum here; it would be a second
definition of a vocabulary the database is already the authority on, and it would 500 on the first
action someone adds without updating it.

## Tests

`PlatformOrgTimelineIT` (api module, TestContainers) — **two orgs throughout**, so every filter is
proven to filter rather than to coincide:

- `mergesBothLedgers_newestFirst` — an audit row and an impersonation event interleaved in time come
  back in one correctly ordered stream with distinct `source` values.
- `roleGrantAppearsOnTheOrgTimeline` — **the slice's whole point.** Grant an org role (which writes
  `target_type = USER`) and assert it appears on the *org's* timeline. This test fails on today's
  schema, which is why V76 exists.
- `provisionedOwnerCreationIsAttributed` — provision an org; the `USER_CREATE` for the minted owner
  carries the new `org_id` and appears on the tenant's timeline. The forward half.
- `provisionedOwnerCreation_backfillJoinIsUnique` — the backward half, and the one worth writing
  even though it tests SQL rather than Java: insert two `ORG_CREATE`/`USER_CREATE` pairs in the
  **pre-V76 shape** (`org_id` NULL) sharing one actor, plus a third `ORG_CREATE` naming the same
  owner with `owner_minted = false`, then run the backfill statement and assert each `USER_CREATE`
  landed on its own org and the `owner_minted = false` row attracted nothing. The uniqueness
  argument is the load-bearing part of this migration; asserting it beats asserting it in prose.
- `platformWideActionsAreAbsent` — `SYSTEM_ROLE_GRANT` and `FORCE_LOGOUT_ALL` on a user who happens
  to be a member of the org do **not** appear. `org_id IS NULL` means "not a tenant event", and a
  timeline that swept them in would be claiming the tenant was touched when it was not.
- `otherOrgsEventsAreAbsent` — the second org's suspension never appears on the first's timeline.
- `platformTierImpersonationIsAbsent` · `orgTierImpersonationIsPresent` — the `ck_imp_scope`
  distinction, as behaviour.
- `actorIsBatchLoadedAndNamed` — every entry carries its actor's email; assert on a page of ≥ 2
  distinct actors.
- `unknownAction_passesThrough` — insert a row with a verb no Java code knows; it serialises rather
  than 500s. The open-text guarantee, pinned.
- `suspendedOrgHasATimeline` — nothing filters on org status, same rule as the queues and search.
- `unknownOrg_is404` · `post_is405` · `paging_isNewestFirstAcrossPageBoundary`.
- `support_reads200` · `orgOwner_is403` · `anonymous_is401` — note the third: an OWNER cannot read
  their own tenant's platform timeline. That is deliberate (it names operators and impersonations),
  and it is the kind of decision worth a test so removing it later is visible.

`PlatformOrgTimelineServiceTest` (Mockito) — the merge and paging arithmetic in isolation: a page
boundary that falls between two rows with identical `created_at`, and a page whose rows all come
from one source.

## Definition of done

- [ ] `mvn -o test` green; full `*IT` battery green. Export **both**
      `DOCKER_HOST=unix:///Users/ninja/.colima/default/docker.sock` and
      `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` — with the second one Ryuk starts
      normally. **Do not reach for `TESTCONTAINERS_RYUK_DISABLED=true`**; it leaks containers and is
      not needed here.
- [ ] `mvn spotless:apply` clean.
- [ ] V76's header records the backfill's measured cost on `perfdb`, the resulting index size, and
      **the counted residual**: how many rows of each action are still `org_id IS NULL` afterwards,
      with one line saying which of those are correct (`SYSTEM_ROLE_*`, `SESSION_REVOKE`, …) and
      whether any `USER_CREATE … via = 'org_provision'` failed to join. Expected zero for the last —
      counted, not assumed.
- [ ] `EXPLAIN (ANALYZE, BUFFERS)` warm-twice for the merged read on `perfdb`, before and after
      `ix_platform_audit_org` — and drop the index if the planner declines it. Skipping is a result.
- [ ] Every `PlatformAuditService` call site passes an explicit org (or an explicit `null`); no
      overload exists that lets a caller omit it.
- [ ] `perfdb` and the dev `inventorydb` both intact at the end, stated as an observation. **Never
      `docker compose down -v`.**
- [ ] `CLAUDE.md` §"Platform admin" gains the `GET /api/admin/orgs/{orgId}/timeline` entry and the
      note that `platform_audit` now carries `org_id` (gitignored here — edit it anyway).
