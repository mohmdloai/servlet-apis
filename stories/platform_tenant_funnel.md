# Tenant lifecycle funnel — how far do signups actually get

> Slice 7 of the platform-console epic (the row the table carried as "5 — Tenant lifecycle funnel"
> until slices 5 and 6 were promoted ahead of it). Branch `134_feat/platform-tenant-funnel`, cut from
> **`master`** (highest PR/issue was #133 — re-verify at implementation time). Frontend pair:
> `frontst` story 81 (`81_st_platform_tenant_funnel.md`, branch `79_feat/…`) — **land this first**.
> **Migration: V77, and it is the point of the slice, not a side effect** (see §"The catalog is not
> history-shaped").
>
> Slices 1–4 answer *what is happening right now*. Slices 5–6 let the operator *act*. This is the
> first surface that answers **is the product working** — and it is the first one whose honesty
> problem is statistical rather than structural.

---

## The reality this corrects

`GET /api/admin/overview` counts tenants by current status: `{total, active, suspended, pending,
provisioned_last_7d}`. That is a **census**. It answers "how many tenants are there" and cannot
answer any of:

- of the tenants that signed up last month, how many ever published a listing?
- how many got as far as one order and then stopped?
- is the drop-off at verification, at cataloguing, or at the first sale?

Every one of those is a question about a tenant's *path*, and the platform has never stored a path —
only the state at the end of it. `provisioned_last_7d` is the closest thing to a lifecycle figure in
the product, and it is a count of rows created, not of anything reached.

## Two findings that decide the design

### 1. The catalog is not history-shaped — `unpublish` erases the evidence

`ProductListingService.publish` sets `published_at = now()`. **`unpublish` sets it back to `null`**
(`ProductListingService:415`), and so does the DRAFT reset on create. So `MIN(published_at)` over an
org answers *"the earliest of the listings currently published"* — **not** "when this tenant first
went live".

A tenant that launched in March and has since unpublished everything reads as **never having reached
the stage**. That makes the funnel *retroactively* lose a conversion it already recorded: refresh the
page and a bar gets shorter. A funnel whose stages move backwards is worse than no funnel — it is the
console's founding rule in its most literal form, because the number was true and became false
without anything happening in the world.

This is the same class of finding as slice 4's: **the data was never shaped for the question.**
`platform_audit` was actor-shaped when the question was tenant-shaped; `product_listing` is
state-shaped when the question is history-shaped. The answer is the same shape too — store the fact
once, where a reader and an index can both see it.

### 2. Provisioned tenants skip a stage, so they are a different funnel

`PlatformOrgService` sets `org.setActive(true)` at provisioning (:203). A provisioned tenant is
**born ACTIVE** — it never passes through PENDING and never has an email-verification step, because
the platform vouched for it.

Pool those with self-serve signups and stage 2 reports a **fake conversion**: 100 % of provisioned
tenants "convert" instantly at a stage that does not apply to them, inflating exactly the number an
operator would use to decide whether the verification email is working. **Acquisition path is a
required dimension of this funnel, not a filter someone might add later.**

And the paths are distinguishable post-hoc without guessing, using slice 4's finding: **provisioning
writes an `ORG_CREATE` audit row; self-serve registration writes no audit row at all**
(`AccountService` never calls `PlatformAuditService`). That is a recorded distinction, not a
heuristic — and since V76 those rows carry `org_id`, the join is direct.

### 3. There is no `activated_at`, but it is recoverable

Nothing stamps when an org went live. For **self-serve** it is the owner's
`app_user.email_verified_at` — the same field slice 5 just put on the wire, and the same event that
calls `activateRegistrationPendingOrgs`. For **provisioned** it is `org.created_at` by definition.
Both recoverable; neither stored.

## The change

### V77 — `org_milestone`

```sql
CREATE TABLE org_milestone (
    org_id     UUID        NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    milestone  VARCHAR(32) NOT NULL,
    reached_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (org_id, milestone)
);
CREATE INDEX ix_org_milestone_stage_time ON org_milestone (milestone, reached_at);
```

**Append-only, stamped once, never cleared, never updated.** Every write is
`INSERT … ON CONFLICT (org_id, milestone) DO NOTHING`, so the first stamp wins and every later one is
a no-op **by the primary key rather than by a check someone can forget**. That property is the whole
reason the table exists — it is precisely what `published_at` failed to be.

`ON DELETE CASCADE` because a deleted org's milestones are not a historical record of anything; the
tenant is gone. (Contrast `platform_audit`, which is a ledger and keeps its rows.)

**Why a table rather than computing the funnel from the source tables at read time.** The
alternative was considered and lost on three counts, and it is worth writing down because it is the
obvious first instinct:

1. It cannot answer stage 4 honestly at all — that is finding 1, and no query shape fixes an erased
   column.
2. It turns one indexed read into **five cross-org aggregates over the largest tables in the
   system** (`sales_order` and `payment` are the 183 MB pair slice 3 measured against a 128 MB
   `shared_buffers`). Every cross-org read in this epic has had to justify its plan; this one would
   arrive already unjustifiable.
3. It writes each stage's definition into an aggregate expression, five of which then drift from the
   funnel's meaning independently. One definition, N callers — the epic's recurring cure.

### The stages, and the fact that they are not a chain

`PlatformFunnelStage` (Java enum) defines the **ordered** sequence:

| # | stage | stamped when |
|---|---|---|
| 1 | `REGISTERED` | org row created (both paths) |
| 2 | `ACTIVATED` | owner verifies (self-serve) · provisioning commits (provisioned) |
| 3 | `CATALOGUED` | first `product_listing` created |
| 4 | `PUBLISHED` | first listing published |
| 5 | `FIRST_ORDER` | first `sales_order` placed |
| 6 | `FIRST_PAYMENT` | first `payment` received |

**The `milestone` column is open text; the ordering is code.** Same split as
`platform_audit.action` (V44's precedent): a future stage needs no migration to be *recorded*, and a
milestone the enum does not know is stored and simply not rendered by the funnel. Writing is
permissive, ordering is explicit — do not add a CHECK constraint enumerating the values, and do not
render a stage the enum has no position for.

**Stages 4 and 5 are not prerequisites of each other, and the funnel must not pretend they are.** An
`IN_STORE` sale needs no listing at all, so a counter-only merchant reaches `FIRST_ORDER` having
never reached `PUBLISHED`. Therefore:

- a tenant is counted at stage N **iff it reached stage N** — never "N-1 implies N", never
  "N implies N-1". Inferring either direction invents data.
- **a later stage's count may legitimately exceed an earlier one's**, and that is information, not a
  rendering bug: it means tenants are selling without a storefront. Do not clamp, do not sort, do not
  "fix" it. An IT pins it (`inStoreOnlyTenant_reachesFirstOrderWithoutPublished`).

### Write sites — one helper, six callers

`OrgMilestoneService.reach(DSLContext ctx, UUID orgId, PlatformFunnelStage stage, OffsetDateTime at)`,
called **inside the business transaction** that causes the milestone, never after it. A milestone
written outside its txn is a milestone that can disagree with the thing it records.

Callers: `AccountService.register` + `PlatformOrgService.provision` (`REGISTERED`);
`AccountService.verifyEmail` per activated org + `provision` (`ACTIVATED`);
`ProductListingService.create` (`CATALOGUED`) and `.publish` (`PUBLISHED`);
`SalesOrderService` placement (`FIRST_ORDER`); the payment-recording path (`FIRST_PAYMENT`).

The helper swallows nothing and returns nothing — a failed milestone insert must roll back its
transaction like any other write. **Do not make it best-effort.** A silently-dropped milestone is a
funnel that under-reports forever, with no way to notice.

### Backfill, and the one stage that cannot be fully recovered

- `REGISTERED` ← `org.created_at`, every org.
- `ACTIVATED` ← self-serve: the owner's `app_user.email_verified_at`; provisioned: `org.created_at`.
  Split the two by **`EXISTS` an `ORG_CREATE` audit row for the org** — provisioned writes one,
  self-serve writes none.
- `CATALOGUED` ← `MIN(product_listing.created_at)`.
- `PUBLISHED` ← `MIN(published_at)` — **lossy, by construction**. A tenant that unpublished
  everything has nothing to recover from, and there is no other durable trace of the event.
- `FIRST_ORDER` ← `MIN(COALESCE(placed_at, created_at))`.
- `FIRST_PAYMENT` ← `MIN(payment.received_at)`.

**Count the residual and put it in V77's header** — the slice-4 discipline: how many orgs have
`CATALOGUED` (or orders) but no `PUBLISHED` stamp, i.e. how many pre-V77 launches the backfill could
not see. State the number; **do not synthesise a timestamp for them**. An invented reached-at would
be indistinguishable from a real one forever after, which is the one thing worse than a gap. Going
forward the write site closes it.

### The endpoint

`GET /api/admin/funnel?cohort=30d|90d|365d|all&path=all|self_serve|provisioned`

`requirePlatformRead` (ADMIN and SUPPORT, byte-identical). **GET only → 405; 404 on any subpath** —
`OverviewAdminHandler`'s shape, not `QueuesAdminHandler`'s 400, because `/funnel` takes no path
segment. Unknown `cohort` or `path` → **400 naming the options** (the `?status=` convention).

```
{ "as_of": "…",
  "cohort": { "window": "30d", "from": "…", "size": 128, "youngest_age_days": 0 },
  "path": "self_serve",
  "stages": [ { "stage": "REGISTERED", "reached": 128 }, … ] }
```

**A cohort, always — never "all tenants ever" as the default.** A funnel over every org that ever
existed mixes a tenant that registered yesterday with one from two years ago, and the drop-off it
reports then measures **age, not friction**. `?cohort=` filters on `org.created_at`; `size` is the
denominator and it is on the response so the client never computes one; `all` remains available and
is honest as long as it is chosen rather than defaulted.

**`youngest_age_days` is on the wire because a young cohort is not a failing one.** A tenant that
registered this morning has not failed to place a first order. The client needs to be able to say so
(story 81 does), and it cannot infer it from the window alone.

**Counts only — no percentages, ever.** The DTO carries `reached` and `size` and nothing derived. A
percentage over a small cohort is noise wearing a decimal point, and the client is the only layer
that knows how much room it has to say "3 of 7" instead of "43 %". Same reflex as slice 3's exact
`total`: give the true numbers and let the surface phrase them.

## Measurement

- `EXPLAIN (ANALYZE, BUFFERS)` warm-twice for the funnel read at each `cohort` value, before and
  after `ix_org_milestone_stage_time` — **drop the index if the planner declines it; skipping is a
  result.** Report cold→warm ranges with the cache state named.
- Measure the **backfill** on a scratch clone, not by eye: it touches `product_listing`,
  `sales_order` and `payment`, and slice 3 measured those last two at 183 MB each.
- `perfdb` seeds commerce only — `platform_audit` there is empty ([[perfdb-platform-tables-empty]]),
  which matters here because the self-serve/provisioned split reads that table. `count(*)` before
  believing any plan, and if `perfdb` cannot host the measurement, build the scratch dataset the way
  slice 4 did and say so.
- Record the numbers as an attached document under `tools/seed/results/` on the branch
  ([[measurements-as-attached-docs]]), not inflated into Javadoc.

## Tests

`PlatformFunnelIT` (api module, TestContainers) — **several orgs, both acquisition paths**:

- `stagesAreStampedOnce_andNeverMove` — publish, unpublish, republish; `PUBLISHED.reached_at` is the
  **first** publish throughout. This is the test the whole migration exists for.
- `inStoreOnlyTenant_reachesFirstOrderWithoutPublished` — and the funnel reports it that way, with
  stage 5 exceeding stage 4 rather than being clamped.
- `provisionedTenantIsActivatedAtCreation` · `selfServeTenantIsActivatedAtVerification`.
- `pathFilterSeparatesTheTwoFunnels` — a provisioned tenant never appears in `path=self_serve`, and
  `all` is the sum of the two.
- `cohortExcludesOlderTenants` · `cohortSizeIsTheDenominatorOnTheResponse` ·
  `youngestAgeDaysReflectsTheNewestMemberOfTheCohort`.
- `backfill_recoversEachStageFromItsSource` and
  **`backfill_leavesUnpublishedLaunchesUnstamped_andCountsThem`** — the honest half of finding 1,
  asserted rather than promised.
- `milestoneWriteIsInTheBusinessTxn` — a rolled-back order leaves no `FIRST_ORDER` row.
- `unknownMilestoneIsStoredAndNotRendered` — the open-text guarantee.
- `unknownCohort_is400` · `unknownPath_is400` · `post_is405` · `subpath_is404`.
- `support_reads200` · `orgOwner_is403` · `anonymous_is401`.
- `deletingAnOrgCascadesItsMilestones`.

`PlatformFunnelServiceTest` (Mockito) — cohort arithmetic and the stage ordering, including a stage
with zero reached (it must appear with `reached: 0`, not be omitted — a missing stage would read as
"no data" when it means "nobody got here", and those are opposite messages).

## Definition of done

- [ ] `mvn -o test` green; full `*IT` battery green. Export **both**
      `DOCKER_HOST=unix:///Users/ninja/.colima/default/docker.sock` and
      `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. **Not**
      `TESTCONTAINERS_RYUK_DISABLED=true`.
- [ ] `mvn spotless:apply` clean.
- [ ] V77's header records the backfill's measured cost, the index size, and **the counted residual**
      of orgs whose first publish could not be recovered — with an explicit line saying those stay
      unstamped rather than synthesised.
- [ ] `EXPLAIN` before/after for the funnel read; index dropped if declined. Numbers filed under
      `tools/seed/results/`.
- [ ] Every milestone write site is inside its business transaction; none is best-effort.
- [ ] `CLAUDE.md` §"Platform admin" gains `GET /api/admin/funnel` and the note that `org_milestone`
      is append-only and stamped once (gitignored — edit it anyway).
- [ ] `perfdb` and the dev `inventorydb` both intact. **Never `docker compose down -v`**, and never
      point Flyway at `perfdb`.
