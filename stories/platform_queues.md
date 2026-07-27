# Cross-org queues — the drill-down behind the overview tiles

> Slice 2 of the platform-console epic. Branch `127_feat/platform-queues`, cut from
> `126_fix/tenant-states` — slice 1.5 lands first and this depends on its `OrgStatus`. Frontend
> pair: `frontst` branch `74_feat/platform-queues` (story 75) — land this backend first.
> **Migration: likely, and in scope** (see §Indexes). Slice 1 needed none because a count tolerates
> a sequential scan; a paged, ordered read across every tenant does not.
> Not in this slice: the **JobRunr `v016` drift** slice 1 raised — still open, still its own slice.
> (The other finding slice 1 raised, the `suspended`-vs-`pending` conflation, was closed by slice
> 1.5; this branch depends on the `OrgStatus` that fix introduced.)

---

## The reality this corrects

Slice 1 gave the operator five cross-org backlog numbers and, deliberately, nothing to click. The
`QueueStrip` tiles carry no `href` and their doc comment says why: there was no cross-org list to
link to, and pointing a platform-wide number at one tenant's org-scoped list would be worse than
not clicking. `AttentionTile` was moved to `shared/ui` with an **optional** `href` for exactly this
slice.

So today an operator learns that seven refunds are owed across the platform and has no way to find
out which ones. Worse for two of the five: **`failed_emails` and `expired_pending_orders` have no
org-scoped list either.** The org plane has `?status=` worklists for refunds, disputes and orphans,
but a failed email delivery is invisible on every surface in this system, and the org order
worklist filters on `status`, not on `expires_at < now()`. For those two the platform queue is not
a convenience view — it is the only view that will ever exist.

(Slice 1's story said each count matched "the org-scoped list it will link to". That holds for
three of five. Correct the claim rather than carrying it forward.)

## The change

`GET /api/admin/queues/{kind}?org_id=&page=&size=` — `requirePlatformRead` (ADMIN and SUPPORT), on
a new `QueuesAdminHandler` registered in `AdminServlet.init()` beside `overview`/`orgs`/`users`/
`audit`. `GET` only → 405; `PageResponse` envelope, as every worklist in this codebase.

`kind` ∈ `failed-emails` · `pending-refunds` · `open-disputes` · `orphan-transactions` ·
`expired-pending-orders`.

- **Unknown `kind` → 400 naming the five**, not 404. It is an enum value that happens to sit in the
  path, so it follows the `?status=` convention (unknown → cause-naming 400), not the unknown-
  resource convention.
- **Bare `GET /api/admin/queues` → 400 naming the five**, mirroring the reserved-route 400 on
  `GET /sales-orders` and `GET /credit-notes`.
- `org_id` narrows to one tenant. Malformed UUID → 400 (`AuditAdminHandler.uuidParam` precedent);
  **unknown org → an empty page, not a 404** — it is a filter, not a lookup, exactly as
  `credit_note_id` behaves.

**Ordering is oldest-first, always.** Every org worklist switches between queue order (filtered,
oldest-first) and ledger order (unfiltered, newest-first). There is no ledger mode here: a
newest-first list of every failed email across every tenant is not a thing anyone works. All five
are queues by construction, so the convention collapses to its queue half. Say so in the handler
Javadoc, because a reviewer who knows the convention will otherwise read its absence as an
oversight.

### Where the rows live — do not weaken `PlatformStatsRepository`

`PlatformStatsRepository`'s contract is **counts only**, and its Javadoc leans on that: *"there is
no DTO here that could carry one, so a future change that wanted to leak data would have to change
this interface, in a review, on purpose."* That guarantee is worth more than the convenience of
adding row methods to it.

Add a sibling: **`PlatformQueueRepository`** (domain interface, `repository` impl, factory, like
its siblings), under its own stated three rules:

1. **Platform-gated** — reached only through `GET /api/admin/queues/*` behind `requirePlatformRead`.
2. **Read-only** — no mutation, ever.
3. **Rows, against a declared per-kind field whitelist.** Each row carries the tenant's identity
   plus the minimum facts needed to triage it, and nothing else. No customer name, phone or
   address. No order lines. No payment-proof URL. No object keys. The whitelist is the contract,
   and there is a test that pins it (§Tests).

This is a **widening of default exposure, not of capability** — a platform ADMIN already bypasses
org checks and SUPPORT already has read-only impersonation, so nothing here is newly reachable.
What changes is what an operator sees *without asking*, which is why it is enumerated per kind
rather than joined to whatever is convenient.

**One PII field crosses, on one kind:** `to_address` on `failed-emails`. A failed-delivery queue
without the recipient cannot be triaged — you cannot tell three unrelated failures from three
failures to one dead domain, which is the first question worth asking. It crosses there and
nowhere else.

**Reads stay unaudited.** `platform_audit` records mutations; `/orgs` and `/users` reads are not
audited either. Keeping this consistent is the right call. If cross-org reads *should* be audited,
that is a policy change spanning the whole plane — raise it, do not special-case it here.

### Row shapes

Every row carries `org: {id, name, slug, status}` from a join (one row per entity — a join, not a
batch load; batch loading is for one-to-many fan-out, which none of these have). `status` is slice
1.5's `OrgStatus`, not a boolean — the server names the state and no client re-derives it.

**Queues span suspended tenants, and `status` is on the row so an operator can see it.** Do not
filter on org status. A suspended merchant still owes real customers real money — pending refunds,
open disputes, orphaned transfers all survive the suspension — and the merchant is locked out, so
nobody on the org side is working that queue. The platform is the only actor left, which makes those
rows *more* urgent, not less. A queue that quietly dropped them would hide exactly the money nobody
else can see. (Orgs born inactive at registration and never verified contribute nothing here — they
have no orders — so this is about genuine suspensions.)

| kind | fields beyond `org` | source |
|---|---|---|
| `failed-emails` | `id, notification_type, to_address, attempts, last_error, failed_at, created_at` | `notification_delivery` ⋈ `notification` (org, type) ⋈ `notification_delivery_email` (to_address) |
| `pending-refunds` | `id, amount, method, sales_order_number?, credit_note_number?, created_at` | `refund` |
| `open-disputes` | `id, amount, sales_order_number?, received_at` | `payment` |
| `orphan-transactions` | `id, amount, provider, provider_ref, occurred_at` | `payment_transaction` |
| `expired-pending-orders` | `id, order_number, grand_total, expires_at, placed_at` | `sales_order` |

`notification_delivery` carries **no `org_id`** — the org lives on `notification`. That join is
mandatory both for the row's org context and for the `?org_id=` filter.

### The predicates are shared with slice 1, in code

The overview count and the queue `total` **must** be the same number, and the way to guarantee that
is not a test — it is not writing the predicate twice. Extract the five `Condition`s into one
package-private holder in `repository` (`PlatformQueuePredicates` or equivalent), and refactor
`PlatformStatsRepositoryImpl.queueCounts()` to consume it alongside the new row queries. A test
that merely asserts the two agree today will pass right up until someone edits one of them.

### Indexes

This is the part slice 1 could defer and this slice cannot. **Every V66 index leads with
`org_id`** — `refund_org_status_idx (org_id, status, created_at, id)`,
`payment_org_status_idx (org_id, status, received_at, id)`, and friends. A cross-org
`WHERE status = … ORDER BY created_at LIMIT 20` matches none of them on its leading column, so
without new indexes each of these reads sorts an entire table to show twenty rows. `COUNT(*)`
tolerated that; keyset-less pagination will not.

`notification_delivery` is worse: V44's only index is partial on `PENDING`, so `FAILED` has none
at all.

The exception is `expired-pending-orders`, already served exactly by V29's
`idx_so_pending_global (expires_at) WHERE status = 'PENDING_PAYMENT'` — which is also the shape to
copy. Partial indexes keyed on each queue's own predicate are small, precise, and cannot slow any
existing write path meaningfully.

**Measure before writing the migration.** `EXPLAIN ANALYZE` each of the five against the seeded
`perfdb`, then add a V73 carrying only the indexes the plans actually ask for, each with a comment
naming the query it serves. Record before/after plans in the PR body. Do not add five indexes
because five felt symmetrical.

`perfdb` is the right bed for this — it seeds **200 orgs**, which is what a cross-org query needs to
behave like production. Its launch command also sets `ORDER_SWEEPER_BACKGROUND_ENABLED=false`, so it
doubles as the jobs-disabled environment story 74's DoD asks for.

#### perfdb safety — read this before running anything

The runbook is `tools/seed/README.md` §"Running the full stack against perfdb" and §"Migration
mismatches" (on `master` since PR #123). Read both before running anything below. Its rules,
restated because this slice is the first to trigger them:

- **`perfdb` and the dev `inventorydb` share one Postgres cluster on one volume.** `docker compose
  down -v` destroys both, permanently. It has happened once. Never run it; ask first if you think
  you need it.
- **This slice adds a migration, which is the "applied but missing" trap.** Codegen applies V73 to
  the shared dev `inventorydb`; switching back to a branch without V73 then fails Flyway
  validation. That failure is **not** a reason to reset anything. The non-destructive fixes, in
  order: run codegen against a scratch clone; or delete the stray `flyway_schema_history` row and
  revert its DDL by hand; or temporarily copy the missing `V*.sql` in just for codegen.
- **Never point Flyway at `perfdb`.** Its schema arrives by copy and its Flyway history by a
  `pg_dump` of `inventorydb`'s history table — migrating it directly is not how it is maintained.
- **To measure the *after* state, apply the candidate index to `perfdb` by hand** (plain
  `CREATE INDEX` in psql against `perfdb`), exactly as its schema is maintained everywhere else.
  Expect it to take a while on a seeded table. Keep it once V73 is final so `perfdb` keeps modelling
  the schema it is meant to model, and note in the PR body that you applied it out of band.
- At merge time, whichever branch merges **second** re-checks that its migration number is still
  above every applied version, and renumbers if not.

Index the ordering key each queue actually uses, and use the **same** key the org-scoped queue uses
where one exists (refunds `created_at`, payments `received_at`) — a platform queue that orders by a
different clock than its org twin will list the same rows in a different order, which reads as a
bug every time.

## Tests

`PlatformQueuesIT` (api module, TestContainers) — seeded with **two** orgs throughout, since
single-org fixtures cannot fail the way this slice can:

- `eachQueue_totalEqualsTheOverviewCount` — parameterised over all five. The pin the shared
  predicate exists to make trivially true.
- `eachQueue_matchesItsOrgScopedListWhereOneExists` — refunds / disputes / orphans only, against
  the org worklists. State in a comment that `failed-emails` and `expired-pending-orders` are
  deliberately absent from this test because they have no org-scoped twin.
- `rows_carryTheirTenant_andOrgIdFiltersToOne` — a two-org fixture; unfiltered names both orgs,
  `?org_id=` narrows to one and its `total` drops accordingly.
- `suspendedOrgsRowsStillAppear` — suspend one of the two orgs mid-fixture; its pending refund is
  still listed, still counted in `total`, and its row reports `org.status = SUSPENDED`. The money a
  locked-out merchant owes is the money most likely to go unworked.
- `unknownOrgId_isAnEmptyPage_notA404` · `malformedOrgId_is400` · `unknownKind_is400NamingTheFive`
  · `bareQueuesRoot_is400` · `post_is405`.
- `rows_carryNoCustomerPii` — **the whitelist pin.** Serialize a row of every kind from a fixture
  whose customer has a name, phone and address, and assert none of those values appears anywhere in
  the JSON. Mirrors the storefront whitelist test; it is the test that keeps rule 3 true as fields
  get added later.
- `failedEmails_carryTheRecipientAndTheError` — the one deliberate PII crossing, asserted on
  purpose so removing it later is a visible decision.
- `support_reads200` · `orgOwner_is403` · `anonymous_is401`.

`PlatformQueueServiceTest` (Mockito) — paging clamps (`size` bounds, negative page, the
`safeOffset` overflow guard `PlatformOrgService` already established) and kind parsing.

## Definition of done

- [ ] `mvn -o test` green; full `*IT` battery green (`DOCKER_HOST` at the colima socket).
- [ ] `mvn spotless:apply` clean.
- [ ] `EXPLAIN ANALYZE` before/after for all five queues on `perfdb` in the PR body, with the
      verdict on each index added or skipped, and a note of any DDL applied to `perfdb` by hand.
- [ ] `perfdb` and the dev `inventorydb` both still intact and queryable at the end of the slice —
      stated as an observation, not an assumption.
- [ ] `queueCounts()` demonstrably shares its predicates with the row queries — one definition, two
      callers. A reviewer can see it without running the tests.
- [ ] `CLAUDE.md` §Platform admin gains the `GET /api/admin/queues/{kind}` entry (gitignored here).
