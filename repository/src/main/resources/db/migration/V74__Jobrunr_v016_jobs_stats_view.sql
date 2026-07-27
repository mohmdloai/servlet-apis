-- V74 — JobRunr storage schema: catch the vendored DDL up to the pinned library version.
--
-- V29 vendored JobRunr's canonical PostgreSQL DDL for **7.2.2** (its v000..v015, emitted verbatim
-- by DatabaseSqlMigrationFileProvider). Commit 0d22001 later bumped the pom to 8.7.1 without
-- re-vendoring, and DatabaseOptions.SKIP_CREATE only checks that the jobrunr_* tables *exist*,
-- never that they *match* — so the drift stayed silent. V29's own header says the block must be
-- regenerated in lockstep with the pom pin; V29 cannot be edited (Flyway checksum), so this
-- migration carries that correction forward instead. See stories/jobrunr_schema_drift.md.
--
-- The whole 7.2.2 → 8.7.1 delta, measured jar-to-jar, is ONE migration:
--     org/jobrunr/storage/sql/common/migrations/v016__alter_jobs_stats_add_awaiting_jobs.sql
-- Five other files differ between the versions (common/v004, v005, v009, v010, and postgres/v014)
-- and none of them applies — not because their changes are cosmetic, but because each is
-- superseded later in the same chain: v004/v005/v010 define a view that v014 drops and rebuilds,
-- and v014 defines a view that v016 drops and rebuilds. No table, column or index differs between
-- the two versions; jobrunr_jobs.state is VARCHAR(36) with no CHECK, so JobRunr 8's new state
-- values store fine. The effective delta is v016 alone, for a structural reason not a lucky one.
--
-- Postgres has no v016 override in the jar, and a dialect file *replaces* rather than supplements
-- the common file of the same version number, so common/v016 applies to us unmodified. (V29
-- correctly vendored the Postgres variant of v014, which is the jar's only Postgres override.)
--
-- Despite the filename this is a DROP VIEW + CREATE VIEW, not an ALTER: the aggregation strategy
-- changes from GROUP BY ROLLUP (state) with the NULL row as total, to plain GROUP BY state with
-- sum() as total, and gains `awaiting` + `processed`. Same numbers, wholly redefined view. 8.7.1's
-- JobStatsView reads `awaiting` (that is the column whose absence throws); nothing reads
-- `processed` yet, but it ships in the file, so it ships here.
--
-- COPIED VERBATIM out of jobrunr-8.7.1.jar. The `cASt(cASt(value AS char(10)) ...)` below is
-- **JobRunr's own typo**, not ours — an upstream global s/as/AS/ ate the inside of the word
-- `cast`. It parses (SQL keywords are case-insensitive) and it applies. Do NOT tidy it: byte
-- identity with the jar is the only thing that makes vendored DDL auditable, and a file that has
-- been touched can no longer be diffed against its source to prove what it is.
--
-- DO NOT hand-edit, and do not bump org.jobrunr in the pom without re-vendoring the delta the same
-- way (read the jar; do NOT re-run DatabaseSqlMigrationFileProvider, which emits the whole v000..
-- set that Flyway already records as applied under V29). JobRunrSchemaIT is the pin: it calls
-- storageProvider.getJobStats() against the migrated schema and fails the moment the library reads
-- a column we have not vendored.

DROP VIEW jobrunr_jobs_stats;
CREATE VIEW jobrunr_jobs_stats
AS
with job_stat_results AS (SELECT state, count(*) AS count
    FROM jobrunr_jobs
    GROUP BY state
)
SELECT coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results), 0)                            AS total,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'AWAITING'), 0)   AS awaiting,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'SCHEDULED'), 0)  AS scheduled,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'ENQUEUED'), 0)   AS enqueued,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'PROCESSING'), 0) AS processing,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'PROCESSED'), 0)  AS processed,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'FAILED'), 0)     AS failed,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'SUCCEEDED'), 0)  AS succeeded,
       coalesce((SELECT cASt(cASt(value AS char(10)) AS decimal(10, 0))
                 FROM jobrunr_metadata jm
                 WHERE jm.id = 'succeeded-jobs-counter-cluster'),
                0)                                                                                        AS allTimeSucceeded,
       coalesce((SELECT sum(job_stat_results.count) FROM job_stat_results WHERE state = 'DELETED'), 0)    AS deleted,
       (SELECT count(*) FROM jobrunr_backgroundjobservers)                                                AS nbrOfBackgroundJobServers,
       (SELECT count(*) FROM jobrunr_recurring_jobs)                                                      AS nbrOfRecurringJobs;