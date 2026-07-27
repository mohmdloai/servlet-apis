# Platform overview — the operator's home read

> Slice 1 of the platform-console epic. Branch `124_feat/platform-overview`. Frontend pair:
> `frontst` branch `72_feat/platform-overview` (story 74) — land this first; the whole page is one
> read of this endpoint.
> **No migration.** Every number below comes off tables that already exist, and the one index the
> heaviest query wants (`idx_so_pending_global`) shipped in V29.

---

## The reality this corrects

The platform plane can list orgs, list users, and read an audit ledger. It cannot answer a single
question about **itself**: is the delivery sweeper still running, did last night's order emails go
out, how much refund money is owed across every tenant, which build is live. The frontend console
reflects that exactly — `/admin` is a bare `redirect('/admin/orgs')`, so an operator lands on a
table of tenant names and has to guess where to look.

Meanwhile the tenant plane already has the answer shape: `GET /api/orgs/{orgId}/health` plus the
`AttentionStrip` fan-out. The platform tier is a level *above* those and has nothing.

The gap is structural, not lazy. Every repository method takes `orgId` as its first parameter —
compiler-enforced tenancy, per CLAUDE.md §Key Patterns. There is no read in this codebase that
deliberately omits the org filter, so there is nothing to aggregate *with*. This slice introduces
that capability, once, narrowly, and says so out loud.

## The change

`GET /api/admin/overview` — `requirePlatformRead` (ADMIN **and** SUPPORT; the whole thing is a
read), on a new `OverviewAdminHandler` registered in `AdminServlet.init()` beside `orgs`/`users`/
`audit`. `GET` only → 405 on anything else, 404 on any subpath, mirroring `AuditAdminHandler`.

```jsonc
{
  "as_of": "2026-07-27T09:14:02Z",
  "tenants":  { "total": 42, "active": 39, "suspended": 3, "provisioned_last_7d": 4 },
  "queues":   { "failed_emails": 3, "pending_refunds": 7, "open_disputes": 2,
                "orphan_transactions": 1, "expired_pending_orders": 0 },
  "jobs":     { "enabled": true, "servers": 1, "recurring": [ /* see below */ ] },
  "build":    { "version": "1.0-SNAPSHOT", "commit": "9f3c1ab", "started_at": "…" },
  "degraded": ["jobs"]
}
```

### One call, not a fan-out

The org home fans out to six `size=1` list reads because those lists already exist. Here they do
not, and inventing six cross-org list endpoints to serve six numbers is the wrong order of work
(cross-org queues are slice 2, and they will reuse *these* predicates). One call is also the only
way to hand the client a single coherent `as_of` — six reads means six different "now"s presented
as one moment, which is the first way a dashboard starts lying.

### The honesty contract

**A section that fails to compute is `null` and named in `degraded[]`. It is never zero.** A `0`
that means "the query threw" is the single failure mode that makes an operations dashboard worse
than no dashboard — it reports *all clear* on the exact incident it exists to catch. Each of
`tenants` / `queues` / `jobs` / `build` is assembled independently and caught independently; one
bad section degrades itself and the read still returns 200 with the rest.

`degraded` is omitted entirely when everything resolved (Jackson drops nulls; an empty list should
be omitted too, so a client branches on presence).

### Cross-org counts — the deliberate tenancy exception

New `PlatformStatsRepository` (interface in `domain`, impl in `repository`, factory-wired like its
siblings). Its methods take **no `orgId`**, and the Javadoc must say why: this is the platform tier,
gated on `SystemRole`, and it returns **counts only** — no tenant row, no customer datum, no money
amount crosses. Keeping the exception in one named repository with one gate is what keeps the
"every method takes orgId" rule true everywhere else instead of quietly eroding it.

| Count | Predicate — identical to the org-scoped list it will link to in slice 2 |
|---|---|
| `failed_emails` | `notification_delivery` `status='FAILED'` |
| `pending_refunds` | `refund` `status='PENDING'` |
| `open_disputes` | `payment` `status='DISPUTED'` |
| `orphan_transactions` | `payment_transaction` `reconciliation_status='ORPHAN'` **and no `payment` row** — the same `has_payment=false` definition the org queue uses |
| `expired_pending_orders` | `sales_order` `status='PENDING_PAYMENT' AND expires_at < now()` — served by `idx_so_pending_global` (V29), the sweeper's own index |

`orphan_transactions` must reuse the existing org-plane predicate rather than re-deriving it. The
org health rollup already learned this lesson once (it retired an ORPHAN proxy because the tile and
the list disagreed); do not re-open it.

`failed_emails` has no supporting index — V44's partial index covers `PENDING` only. At current row
counts a sequential scan is correct and an index is premature. **Measure it** (`EXPLAIN ANALYZE` on
the seeded `perfdb`, per `tools/seed/README.md`) and record the number in the PR; add the partial
index in a later slice only if the measurement asks for it.

### Job health

Source: `jobrunr_recurring_jobs` for the registered set, `jobrunr_jobs` for outcomes, filtered to
the three ids `AppConfig.startSweeperScheduler()` registers — `order-ttl-sweeper`,
`notification-delivery-sweeper`, `unverified-account-purge`.

Per job: `{ id, last_success_at, last_failure_at, consecutive_failures, next_scheduled_at, state }`
where `state ∈ HEALTHY | FAILING | STALE | UNKNOWN`.

- `last_success_at` = `MAX(updatedat)` over `state='SUCCEEDED'`; `consecutive_failures` = `FAILED`
  rows newer than that (all of them when there is no success on record).
- `STALE` = no success inside a generous multiple of the job's own cron period (read the cron from
  the same env vars `AppConfig` reads, so a re-tuned interval can't make a healthy job look dead).
- `UNKNOWN` = registered but nothing to judge on. **Not** `HEALTHY`.

Four traps, all of which will bite silently:

1. **JobRunr reaps its own history.** SUCCEEDED jobs are deleted after JobRunr's retention window.
   `last_success_at: null` therefore means *"no success on record"*, **not** *"never ran"* — and the
   frontend must word it that way. For the 10s/30s sweepers this is invisible; for the daily 04:00
   purge the window is the whole signal.
2. **The columns are lowercase.** V29's DDL writes `recurringJobId`, `updatedAt` unquoted, so
   Postgres folded them to `recurringjobid`, `updatedat`. That is what jOOQ generated (codegen
   excludes only `flyway_schema_history`, so the `jobrunr_*` tables *are* generated types).
3. **`createdat`/`updatedat` are `TIMESTAMP`, not `TIMESTAMPTZ`.** JobRunr writes UTC into a naive
   column. Read as `LocalDateTime` and attach `ZoneOffset.UTC` explicitly — never let the JVM
   default zone decide, or every timestamp on this dashboard shifts by the server's offset.
4. **V29's DDL may have drifted from the running JobRunr.** The file is labelled "PINNED to JobRunr
   7.2.2 — DO NOT hand-edit", but `0d22001` (dependabot) bumped the pom to **8.7.1** with no
   follow-up migration and `DatabaseOptions.SKIP_CREATE` set, so nothing has reconciled the two.
   **Read the live schema before writing the query** (`\d jobrunr_jobs`, and the rows in
   `jobrunr_migrations`); trust the database over V29's comment. If the schemas genuinely diverge
   that is its own slice — report it, do not fix it here.

**`ORDER_SWEEPER_BACKGROUND_ENABLED=false` means no jobs exist at all** (tests, and any deployment
driving expiry only through `POST /api/admin/sweep`). That is `jobs.enabled: false` with an empty
`recurring` list — an explicit, calm "background jobs are off" state. It must **not** render as
three dead jobs, or every CI and test environment shows a red dashboard and operators learn to
ignore the colour.

### Build identity

`{ commit?, started_at }` — `commit` from a `BUILD_COMMIT` env var, `started_at` from when
`AppConfig` was constructed. `commit` is **optional**: unset locally → omitted → the UI says "dev".

**Bake the SHA into the image, do not pass it at runtime.** Two lines in the `Dockerfile`
(`ARG BUILD_COMMIT=dev` / `ENV BUILD_COMMIT=${BUILD_COMMIT}`) and one `build-args:
BUILD_COMMIT=${{ github.sha }}` on the existing `docker/build-push-action` step in `deploy.yml`.
`docker-compose.prod.yml` is not touched.

The runtime-env alternative — a `BUILD_COMMIT: ${BUILD_COMMIT:-}` line on the `backend` service
plus the deploy script writing the SHA into `/opt/ststore/.env` — was rejected. It stores the commit
*beside* the image rather than *in* it, so any pull not accompanied by that write (a manual
`docker compose up -d backend` on the box, say) leaves this tile naming a build that is not running.
A build-identity tile that is confidently wrong is the failure this dashboard exists to prevent.

**No `version` field.** The runtime image copies `classes/` + `lib/` rather than a jar, so there is
no manifest to read, and no Maven resource filtering is configured. `1.0-SNAPSHOT` would tell an
operator nothing anyway. Do not add a build plugin to chase it — the commit is the answer to
"which build is live".

## Tests

`PlatformOverviewIT` (api module, TestContainers):

- `overview_countsEveryQueueAcrossOrgs` — seed two orgs with a pending refund each; the count is 2.
  The whole point of the slice, so it is the first test.
- `overview_queueCountsMatchTheOrgScopedLists` — for each queue, Σ per-org `?status=…` `total`
  equals the platform count. The pin against the tile and the drill-down ever disagreeing.
- `overview_isReadableBySupport` / `_rejectsAnOrgOwner` / `_rejectsAnonymous` — 200 / 403 / 401.
- `overview_reportsJobsDisabledRatherThanThreeDeadJobs` — with the background flag off.
- `overview_405sOnPost_and404sOnAnUnknownSubpath` — the handler-contract pair every admin resource
  has.

`PlatformOverviewServiceTest` (Mockito, unit) — the parts an IT cannot force:

- a throwing collaborator degrades **only** its section: 200, that section `null`, its name in
  `degraded`, every other section intact. Assert the section is `null` and not `0`.
- job-state classification: healthy / consecutive-failure / stale-past-its-cron / no-success-on-
  record, and that a re-tuned cron does not turn a healthy job stale.

## Definition of done

- [ ] `mvn -o test` green; the full `*IT` battery green (`DOCKER_HOST` pointed at the colima socket).
- [ ] `mvn spotless:apply` clean.
- [ ] `EXPLAIN ANALYZE` for the `failed_emails` count recorded in the PR body, with the verdict on
      whether an index is warranted yet.
- [ ] The live `jobrunr_jobs` schema checked against V29, and any drift reported in the PR body.
- [ ] `CLAUDE.md` §Platform admin gains the `GET /api/admin/overview` entry (note: gitignored here).
