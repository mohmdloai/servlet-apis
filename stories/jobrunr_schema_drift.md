# Fix: the JobRunr storage schema is one view behind the library

> Branch `128_fix/jobrunr-schema-drift`, cut from **`master`** (highest PR is #127; 125 went to
> dependabot, 126/127 to the console slices). **Migration: V74** — V73 shipped with the platform
> queues this morning, so the number moved.
>
> **No frontend pair, deliberately.** This is invisible above the database: the platform overview's
> jobs section reads `jobrunr_recurring_jobs`/`jobrunr_jobs` directly and never touches the view
> being repaired, so no endpoint, DTO or screen changes. A frontend story here would be ceremony.
> The three preceding console slices each had a pair; this one does not, and that is the correct
> shape rather than an omission.
>
> This is the last of the two findings slice 1 raised. Closing it closes the epic's open list.

---

## The reality this corrects

`0d22001` bumped the pom from JobRunr 7.2.2 to 8.7.1. V29's vendored DDL was not regenerated. The
drift has been silent ever since because `DatabaseOptions.SKIP_CREATE` only checks that the tables
*exist* — never that they *match*.

The process was documented and skipped anyway. V29's own header says:

> *DO NOT hand-edit. Bumping the JobRunr dependency version is a future slice and must regenerate
> this block from the new version in lockstep with the pom pin.*

Nothing enforced it, so it did not happen. That is the fact worth carrying out of this slice: a
comment is not a control.

### The scope, measured jar-to-jar

Both versions are in `~/.m2`, so this was compared file by file rather than by eye:

- **Four files exist in 8.7.1 and not 7.2.2**, all `v016__alter_jobs_stats_add_awaiting_jobs.sql` —
  in `common`, `db2`, `h2` and `oracle`. **Postgres has no `v016` override**, so it inherits the
  `common` one. That is the only file we need.

  The rule that licenses this, verified rather than assumed: **a dialect file wholly *replaces* the
  common file of the same version number — it does not supplement it.** `postgres/v014` is 22 lines
  and ends by restating the `DROP INDEX`/`CREATE INDEX` pair that constitutes the whole of
  `common/v014`, because it replaces it. V29 vendored the Postgres variant, correctly. With no
  `postgres/v016` in the jar, `common/v016` applies to us unmodified.
- **Postgres has exactly one dialect override in the whole jar**: `postgres/migrations/
  v014__improve_job_stats.sql` — and it **does** differ between the two versions. Diffed
  case- and whitespace-insensitively it is `select` → `SELECT` and reformatted parens: **no
  semantic change**. Worth stating explicitly, because comparing only `common/` (where the
  differing files are `v004`, `v005`, `v009`, `v010`) would miss the one file that actually applies
  to a Postgres deployment and reach the right answer by luck.
- **No table, column or index differs.** `v001`/`v002`/`v003` are byte-identical across versions.
  `jobrunr_jobs.state` is `VARCHAR(36)` with **no CHECK constraint**, so JobRunr 8 writing its new
  `AWAITING`/`PROCESSED` state values stores fine. There is no data risk anywhere in this slice.

**So the entire drift is: `jobrunr_jobs_stats` is missing `awaiting` and `processed`.**

### The severity is lower than slice 1 reported, and the fix is still worth doing

Slice 1 recorded this as "any `getJobStats()` call throws", which is true, and inferred that the
stats API is broken today, which is not. `AbstractStorageProvider` reaches `getJobStats()` only from
`notifyJobStatsOnChangeListenersOnCurrentThread()`, which first filters `onChangeListeners` for
`JobStatsChangeListener`. Those come only from the dashboard, the JMX extension, or the micrometer
binder. `AppConfig:841-845` is `useStorageProvider(…SKIP_CREATE).useBackgroundJobServer()` and
nothing else, and no pom in the repo carries a `jobrunr-*` extension or micrometer artifact. Nothing
registers a listener, so nothing calls it, and a live run logged no errors.

**It is an armed trap, not a live break.** It fires the first time anyone enables the dashboard —
which is exactly when an operator is reaching for it because something else is already wrong. Fix
it while it is cheap and boring.

### `v016` is a rewrite, not an `ALTER`

Despite the filename, the file is `DROP VIEW` + `CREATE VIEW`, and it changes the aggregation
strategy: `GROUP BY ROLLUP (state)` with the NULL row as `total` becomes plain `GROUP BY state` with
`sum()` as `total`, plus the two new columns. Same numbers, wholly redefined view. Do not
hand-write an `ALTER … ADD COLUMN` that appears to achieve the same thing — the view JobRunr 8
expects is the one in its jar.

## The change

### V74 — vendor `v016` verbatim

Copy `org/jobrunr/storage/sql/common/migrations/v016__alter_jobs_stats_add_awaiting_jobs.sql` out of
the **8.7.1** jar, unmodified, into `V74__Jobrunr_v016_jobs_stats_view.sql` under a header matching
V29's provenance style.

**Copy it verbatim including its upstream typo.** The shipped file contains
`cASt(cASt(value AS char(10)) AS decimal(10, 0))` — JobRunr ran a global `s/as/AS/` that ate the
inside of the word `cast`. It parses correctly (SQL keywords are case-insensitive) and it has been
verified to apply. Normalising it would be tidier and is the wrong call: V29's whole auditability
claim is "emitted verbatim by JobRunr's own generator", and a file that has been touched can no
longer be diffed against the jar to prove it. **Note the typo in the V74 header** so it does not
read as ours.

Do not run `DatabaseSqlMigrationFileProvider` to produce this. That generator emits the whole
`v000…v016` set, and `v000…v015` are **already recorded as applied** in our Flyway history under
V29 — replaying them would fail on `CREATE TABLE … already exists`, and would be rewriting history
Flyway owns even if it didn't. The generator is how you author a fresh V29; it is not how you patch
a live schema. Reading the jar is sufficient and is what this analysis did.

**"Take the delta" needs care.** The literal file-level delta between the two versions is *six*
files: `common/v016` (new) plus `common/v004`, `v005`, `v009`, `v010` and `postgres/v014` (changed).
Five of those must not be applied — and not merely because their changes are cosmetic, but because
**each is superseded later in the chain**: `v004`/`v005`/`v010` define a view that `v014` drops and
rebuilds, and `v014` defines a view that `v016` drops and rebuilds. The effective delta is `v016`
alone, for a structural reason rather than a lucky one.

### Correct both stale pins — the pom one matters more

`V29` cannot be edited (Flyway checksum), so its header stays wrong and V74's header carries the
forward pointer instead.

But `pom.xml:29` is editable and is the more dangerous of the two:

```xml
<!-- Pinned: the V29 JobRunr DDL is generated from this exact version. Bump in lockstep. -->
<jobrunr.version>8.7.1</jobrunr.version>
```

That comment is false — V29 is generated from 7.2.2 — and it sits **at the exact point of edit
where the next bump happens**, which is where the next dependabot PR gets reviewed. It should name
both migrations and say what re-vendoring actually requires. Fixing V74's header without fixing this
one leaves the trap where people read it.

### Recurrence prevention — not the way slice 1 assumed

The obvious guard is `DatabaseCreator.validateTables()` at boot. **It would not have caught this.**
Its implementation is `getAllTableNames()` → `removeAll(expected)` → `isEmpty()`: a comparison of
**table name sets**, with no column inspection at all. It is the same existence check `SKIP_CREATE`
already performs, which is precisely the check this drift walked past. Building a startup guard on
it would produce a green light that means nothing — worse than no guard, because someone would
trust it.

**Pin the thing that actually breaks instead.** Add one integration test that calls
`storageProvider.getJobStats()` against the migrated schema and asserts it returns. That is the
exact call that throws today, it exercises the real view through the real library, and it fails on
the next version bump that changes the view — no matter which column moves. One test, no
production code, no startup cost.

**`jobrunr_migrations` stays empty.** `SKIP_CREATE` means JobRunr never records rows there, and
seeding 17 rows guards only against someone flipping the flag to `CREATE` — a deliberate act that
would come with its own thinking and its own slice. Leave the door alone.

## Tests

`JobRunrSchemaIT` (api module, TestContainers — the container runs Flyway, so it is the honest bed):

- `getJobStats_returnsAgainstTheMigratedSchema` — the pin. Obtain the configured `StorageProvider`
  and call `getJobStats()`. Today this throws on the missing `awaiting` column; after V74 it
  returns. Assert on a field that only exists post-`v016` (`awaiting`), so the test cannot pass
  against the old view.
- `jobStatsView_carriesEveryColumnJobRunr8Reads` — select from `jobrunr_jobs_stats` and assert the
  column set contains `awaiting` and `processed`. Redundant with the above by design: one test
  fails if the *library* changes, the other if the *schema* does, and being told which is the
  whole diagnostic value.

`PlatformOverviewIT` must keep passing untouched — the overview reads the tables, not the view, and
that separation is what kept this from being an outage. If it needs editing, something in V74 went
wider than intended.

## Definition of done

- [x] `mvn -o test` green; full `*IT` battery green (`DOCKER_HOST` at the colima socket).
- [x] `mvn spotless:apply` clean.
- [x] V74's DDL is **byte-identical** to the 8.7.1 jar's `common/v016` — state the `diff` in the PR
      body. This is the one file in the repo where "I tidied it slightly" is a defect.
- [x] `pom.xml`'s pin comment corrected to name V29 **and** V74 and to state that a bump requires
      re-vendoring the delta.
- [x] The new IT **observed failing before V74 and passing after** — reported as two runs, not
      asserted. A regression pin nobody watched fail is a pin nobody knows works.
- [x] No jOOQ codegen re-run required — confirm `JobrunrJobsStats` is generated but unread by our
      code (`grep` for it), so the schema change needs no downstream regeneration. If that turns out
      false, say so rather than quietly running codegen against the shared dev DB.
- [x] `perfdb` and the dev `inventorydb` both still intact — V74 is one `DROP VIEW`/`CREATE VIEW`,
      but it is still a migration, so the `tools/seed/README.md` §"Migration mismatches" rules
      apply. **Never `docker compose down -v`.**
- [x] `CLAUDE.md` unchanged — no API surface moves in this slice (gitignored here, noted for
      completeness).
