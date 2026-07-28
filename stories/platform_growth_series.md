# Growth series — how fast is the platform growing, stage by stage

> Slice 8 of the platform-console epic. Branch `137_feat/platform-growth-series`, cut from
> **`master`** (highest PR/issue was #136 — re-verify at implementation time; the epic has now paid
> for that check three times). Frontend pair: `frontst` story 84
> (`84_st_platform_growth_series.md`, branch `82_feat/…`) — **land this first**.
> **No migration.** That is not an accident: V77 built `org_milestone` precisely so questions like
> this one become a cheap read of a table shaped for them. If this slice appears to need DDL,
> stop — something in the design has drifted.
>
> Slice 7 answers *how far do tenants get* — a *cohort* question (follow one set of tenants
> through the stages). This slice answers *how fast are they arriving* — an *event* question
> (what happened in each week). The two share a table, a path filter, and nothing else; the first
> honesty rule below is refusing to blur them.

---

## The reality this corrects

The console has exactly one growth figure: `provisioned_last_7d` on the overview — one number, no
history, one acquisition path. Nothing answers:

- are signups accelerating or stalling, and since when?
- did the verification-email change move weekly activations?
- how many tenants took their first payment each month this year?

Every one of those is a count of **milestone events per time bucket**, and V77 stores every
milestone event with the timestamp it happened at. The read this slice adds is the one V77's
header promised would be cheap.

## Grounding (verified against the code this story reads)

- `org_milestone (org_id, milestone, reached_at, PK (org_id, milestone))` — append-only, stamped
  once, six write sites in-transaction. **At most 6 rows per org, ever**; on `perfdb` (200 orgs /
  1M orders) it holds 1,003 rows and the planner refuses every index candidate (V77's header —
  do not re-litigate an index here).
- `reached_at` is the event's own time, including for backfilled history: `REGISTERED` **is**
  `org.created_at` by construction, `FIRST_ORDER` is `MIN(COALESCE(placed_at, created_at))`, etc.
  A series over history is therefore honest — with one exception, below.
- **`PUBLISHED` history is a floor before V77 ran.** The backfill could not recover launches that
  were unpublished and never republished (`published_at` was nulled — finding 1 of slice 7), and
  `org_milestone` has no column that distinguishes a backfilled row from a live one. Adding one
  (a `stamped_at`) was considered and rejected: a migration whose only consumer is a footnote.
  The consequence is the frontend's to phrase (story 84 requires caveat copy on the PUBLISHED
  panel); the wire carries no field for it.
- The acquisition-path split is slice 7's: `EXISTS platform_audit ORG_CREATE` for the org —
  provisioned writes one, self-serve registration writes none. **One definition**: the growth read
  consumes `PlatformFunnelRepositoryImpl.cohortCondition` (with a null cohort bound), never a
  re-derived copy.

## The change

### The endpoint

`GET /api/admin/growth?window=90d|365d|all&bucket=week|month&path=all|self_serve|provisioned`

`requirePlatformRead` (ADMIN and SUPPORT, byte-identical). GET only → 405; **404 on any subpath**
(`OverviewAdminHandler`'s shape — no enum lives in the path). Unknown **or missing** `window`,
`bucket`, or `path` → 400 naming the options (slice 7's convention: all params explicit, no
silent defaults on an enum-shaped parameter).

- The window bounds **`reached_at`** — events in the period — *not* `org.created_at`. An org
  registered two years ago whose first payment lands this week **belongs in this week's
  `FIRST_PAYMENT` point**; that is the event/cohort distinction, and an IT pins it so nobody
  "fixes" the growth series to agree with the funnel.
- **No window cap.** The reports' 366-day cap defends 183 MB commerce tables; this table is 72 kB
  at full perfdb scale. Copying that limit here would be a limit defending nothing (the slice-6
  lesson). `all` is fine — and unlike the funnel, `all` is not a lying default for a *series*,
  because a time axis shows age instead of hiding it. `week` over `all` is bounded by the
  platform's own lifetime.
- No `bucket=day`: tenant-lifecycle events are too sparse for day buckets to read as anything but
  noise, and a `day|week|month` triple invites the reports comparison this endpoint must not
  make. Two options, both defensible on this data. (Revisit when a day genuinely means something
  — a launch, a campaign.)

### The response

```
{ "as_of": "…", "window": "90d", "bucket": "week", "path": "self_serve",
  "from": "…",                 // UTC start of the window (absent for window=all)
  "current_period": "…",       // UTC date_trunc(bucket, now()) — the still-open bucket
  "series": [
    { "stage": "REGISTERED", "points": [ { "period": "…", "count": 4 }, … ] },
    …                          // all six stages, always — an empty points[] included
  ] }
```

- **The reports dialect, verbatim** (`stories/reporting_reads.md`): `period` is
  `date_trunc(bucket, reached_at)` in **UTC**, points ordered `period ASC`, and **series are
  sparse** — a bucket with no events is omitted, zero-fill is the client's presentation concern.
  One deviation of meaning, stated so no client generalises it: in the *reports*, an absent
  bucket merely wasn't summed; here an absent bucket **means zero events** — `org_milestone` is
  the complete record of the thing being counted, so absence is a fact, not a gap. The client may
  zero-fill with confidence the reports never gave it.
- **All six stages, always** — a stage nobody reached this window is `points: []`, never omitted
  ("nobody arrived" and "no data" are opposite messages; slice 7's rule, unchanged).
- **Counts only. No rates, no deltas, no percent-growth — ever, on any layer.** A derivative over
  small counts is the funnel's percentage problem squared: one tenant in an empty week is
  "+100 %". The wire carries events; if a surface ever wants a rate it must clear its own
  small-denominator bar, client-side, where the room to phrase it lives.
- **`current_period` is the partial-bucket honesty mechanism.** The newest bucket is incomplete
  until it closes, so every naive growth chart ends in an apparent collapse. The server neither
  drops the open bucket (discarding data is its own lie) nor flags points (one fact, one place):
  it names the still-open period on the envelope, and the client renders any point at that period
  as provisional. An IT pins that an event stamped "now" appears under `current_period`, not
  vanished.
- `milestone` values the `PlatformFunnelStage` enum does not know are stored-but-not-rendered,
  exactly as the funnel treats them — the open-text write / closed-enum read split.

### Where the read lives

**A second read on `PlatformFunnelRepository` — deliberately not a fifth sibling.** The epic's
sibling rule exists to stop a counts-only type growing row reads or a whitelist widening; this is
the same table, the same counts-only contract, the same gate, and — decisively — the same
`cohortCondition` definition for `path`. Splitting it into a new type would put the
self-serve/provisioned predicate in two files, which is the exact defect (one rule, written
twice) this epic keeps finding. The interface comment gains a line saying the type now serves
both the funnel and the growth series, both as aggregates.

`PlatformGrowthService` (new, `service.platform`) owns window/bucket parsing —
`PlatformFunnelQuery`'s shape, shared literally where the vocabulary overlaps (`path` parsing is
one function already; `window` differs from the funnel's `cohort` in accepted values, so it is
its own table beside it, not a widened one). `GrowthAdminHandler` on `AdminServlet` under
`"growth"`.

## Measurement

- `count(*)` on `perfdb.org_milestone` **before believing any plan** (the standing lesson — it
  holds 1,003 rows as of the 2026-07-28 hand-migration; see
  `tools/seed/README.md` §"Running the full stack against perfdb").
- `EXPLAIN (ANALYZE, BUFFERS)` warm-twice for each `bucket` × a bounded and an unbounded window,
  on perfdb. Expected: a trivial Seq Scan + HashAggregate in single-digit ms — **say so with the
  numbers rather than assuming it**; if anything exceeds ~10 ms warm, that is a finding.
- Check the **time spread** of perfdb's backfilled `reached_at` values before reading any series
  off it: if the seeder stamped everything in one burst, the series is one bucket tall and proves
  nothing about ordering or sparseness — build the usual scratch fixture for the ITs instead and
  note it. (The ITs never depend on perfdb either way.)
- Results file under `tools/seed/results/` on the branch ([[measurements-as-attached-docs]]).

## Tests

`PlatformGrowthIT` (api module, TestContainers) — several orgs, both paths, events spread across
buckets by direct `org_milestone` inserts (the write sites are slice 7's tested property; this
slice's property is the read):

- `registeredSeriesSum_equalsFunnelCohortSize` — for the same window+path, Σ `REGISTERED` points
  == the funnel's `cohort.size`. The one deliberate bridge between the two surfaces
  (`REGISTERED.reached_at` **is** `org.created_at`), pinned so it cannot silently break.
- `seriesIsEventBased_notCohortBased` — an org registered far outside the window whose
  `FIRST_ORDER` lands inside it appears in the series and **not** in the same-window funnel
  cohort. The distinction is the slice; assert it in both directions.
- `sparseBucketsAreOmitted_andEmptyStageIsAnEmptyArray` — no zero-count points; all six stages
  present always.
- `currentPeriodNamesTheOpenBucket_andItsEventsAreNotDropped`.
- `bucketBoundariesAreUtc` — an event at a UTC week edge lands in exactly one bucket.
- `pathFilterSeparatesTheSeries` — provisioned events never in `path=self_serve`; `all` is the
  per-bucket sum of the two.
- `windowBoundsReachedAt` — the same org contributes different stages to different windows.
- `unknownMilestoneRowIsNotRendered` — the open-text guarantee, again.
- `unknownWindow_is400` · `unknownBucket_is400` · `unknownPath_is400` · `post_is405` ·
  `subpath_is404` · `support_reads200` · `orgOwner_is403` · `anonymous_is401`.

`PlatformGrowthServiceTest` (Mockito) — parsing tables and their 400 messages; series assembly
keeps server stage order; `from`/`current_period` arithmetic against a fixed `asOf`.

## Definition of done

- [ ] `mvn -o test` green; full `*IT` battery green (Colima socket vars; never
      `TESTCONTAINERS_RYUK_DISABLED`).
- [ ] `mvn spotless:apply` clean.
- [ ] **No migration shipped.** If one crept in, the design drifted — see the header.
- [ ] EXPLAIN capture + the perfdb `reached_at` spread check under `tools/seed/results/`.
- [ ] `CLAUDE.md` §"Platform admin" gains `GET /api/admin/growth` (gitignored — edit it anyway).
- [ ] perfdb hand-migration note: nothing to do this slice (no DDL); the standing per-version
      procedure in `tools/seed/README.md` applies only when a future slice ships one.
- [ ] Both databases intact, stated as an observation.
