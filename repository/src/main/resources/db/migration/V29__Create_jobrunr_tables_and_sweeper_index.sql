-- V29 — Two unrelated additions sharing one migration for sequencing convenience:
--   (1) a sweeper-friendly partial index for the order-TTL sweeper, and
--   (2) JobRunr 7.2.2 storage tables.
--
-- See stories/expire_pending_orders.md §Schema.

-- ─────────────────────────────────────────────────────────────────────────────
-- (1) Sweeper-friendly partial index for SalesOrderRepository.findExpiredPendingIds
--
-- Leading column is expires_at (not org_id like V17's idx_so_pending), so the
-- cross-org candidate query
--     WHERE status='PENDING_PAYMENT' AND expires_at < now() ORDER BY expires_at ASC LIMIT :n
-- does an index-ordered scan and stops at :n rows — no sort, no full partial scan.
-- The existing idx_so_pending(org_id, expires_at) stays for per-org queries.
-- ─────────────────────────────────────────────────────────────────────────────
CREATE INDEX idx_so_pending_global ON sales_order (expires_at)
    WHERE status = 'PENDING_PAYMENT';

-- ─────────────────────────────────────────────────────────────────────────────
-- (2) JobRunr 7.2.2 storage schema — PINNED to JobRunr 7.2.2.
--
-- These statements are the canonical PostgreSQL DDL emitted verbatim by JobRunr's
-- own DatabaseSqlMigrationFileProvider for v7.2.2 (its v000..v015 migrations, in
-- order). We run them under Flyway and configure JobRunr with
-- DatabaseOptions.SKIP_CREATE so the schema stays reproducible and under Flyway's
-- control rather than being auto-created on first boot.
--
-- DO NOT hand-edit. Bumping the JobRunr dependency version is a future slice and
-- must regenerate this block from the new version in lockstep with the pom pin.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── JobRunr migration: v000__create_migrations_table.sql ────────────────────────────────────────────
CREATE TABLE jobrunr_migrations
(
    id          nchar(36) PRIMARY KEY,
    script      varchar(64) NOT NULL,
    installedOn varchar(29) NOT NULL
);

-- ── JobRunr migration: v001__create_job_table.sql ────────────────────────────────────────────
CREATE TABLE jobrunr_jobs
(
    id           NCHAR(36) PRIMARY KEY,
    version      int          NOT NULL,
    jobAsJson    text         NOT NULL,
    jobSignature VARCHAR(512) NOT NULL,
    state        VARCHAR(36)  NOT NULL,
    createdAt    TIMESTAMP    NOT NULL,
    updatedAt    TIMESTAMP    NOT NULL,
    scheduledAt  TIMESTAMP
);
CREATE INDEX jobrunr_state_idx ON jobrunr_jobs (state);
CREATE INDEX jobrunr_job_signature_idx ON jobrunr_jobs (jobSignature);
CREATE INDEX jobrunr_job_created_at_idx ON jobrunr_jobs (createdAt);
CREATE INDEX jobrunr_job_updated_at_idx ON jobrunr_jobs (updatedAt);
CREATE INDEX jobrunr_job_scheduled_at_idx ON jobrunr_jobs (scheduledAt);

-- ── JobRunr migration: v002__create_recurring_job_table.sql ────────────────────────────────────────────
CREATE TABLE jobrunr_recurring_jobs
(
    id        NCHAR(128) PRIMARY KEY,
    version   int  NOT NULL,
    jobAsJson text NOT NULL
);

-- ── JobRunr migration: v003__create_background_job_server_table.sql ────────────────────────────────────────────
CREATE TABLE jobrunr_backgroundjobservers
(
    id                     NCHAR(36) PRIMARY KEY,
    workerPoolSize         int           NOT NULL,
    pollIntervalInSeconds  int           NOT NULL,
    firstHeartbeat         TIMESTAMP(6)  NOT NULL,
    lastHeartbeat          TIMESTAMP(6)  NOT NULL,
    running                int           NOT NULL,
    systemTotalMemory      BIGINT        NOT NULL,
    systemFreeMemory       BIGINT        NOT NULL,
    systemCpuLoad          NUMERIC(3, 2) NOT NULL,
    processMaxMemory       BIGINT        NOT NULL,
    processFreeMemory      BIGINT        NOT NULL,
    processAllocatedMemory BIGINT        NOT NULL,
    processCpuLoad         NUMERIC(3, 2) NOT NULL
);
CREATE INDEX jobrunr_bgjobsrvrs_fsthb_idx ON jobrunr_backgroundjobservers (firstHeartbeat);
CREATE INDEX jobrunr_bgjobsrvrs_lsthb_idx ON jobrunr_backgroundjobservers (lastHeartbeat);

-- ── JobRunr migration: v004__create_job_stats_view.sql ────────────────────────────────────────────
CREATE TABLE jobrunr_job_counters
(
    name   NCHAR(36) PRIMARY KEY,
    amount int NOT NULL
);

INSERT INTO jobrunr_job_counters (name, amount)
VALUES ('AWAITING', 0);
INSERT INTO jobrunr_job_counters (name, amount)
VALUES ('SCHEDULED', 0);
INSERT INTO jobrunr_job_counters (name, amount)
VALUES ('ENQUEUED', 0);
INSERT INTO jobrunr_job_counters (name, amount)
VALUES ('PROCESSING', 0);
INSERT INTO jobrunr_job_counters (name, amount)
VALUES ('FAILED', 0);
INSERT INTO jobrunr_job_counters (name, amount)
VALUES ('SUCCEEDED', 0);

CREATE VIEW jobrunr_jobs_stats
as
select count(*)                                                                           as total,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'AWAITING')             as awaiting,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'SCHEDULED')            as scheduled,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'ENQUEUED')             as enqueued,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'PROCESSING')           as processing,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'FAILED')               as failed,
       (select((select count(*) from jobrunr_jobs jobs where jobs.state = 'SUCCEEDED') +
               (select amount from jobrunr_job_counters jc where jc.name = 'SUCCEEDED'))) as succeeded,
       (select count(*) from jobrunr_backgroundjobservers)                                as nbrOfBackgroundJobServers,
       (select count(*) from jobrunr_recurring_jobs)                                      as nbrOfRecurringJobs
from jobrunr_jobs j;

-- ── JobRunr migration: v005__update_job_stats_view.sql ────────────────────────────────────────────
DROP VIEW jobrunr_jobs_stats;

CREATE VIEW jobrunr_jobs_stats
as
select count(*)                                                                           as total,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'AWAITING')             as awaiting,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'SCHEDULED')            as scheduled,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'ENQUEUED')             as enqueued,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'PROCESSING')           as processing,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'FAILED')               as failed,
       (select((select count(*) from jobrunr_jobs jobs where jobs.state = 'SUCCEEDED') +
               (select amount from jobrunr_job_counters jc where jc.name = 'SUCCEEDED'))) as succeeded,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'DELETED')              as deleted,
       (select count(*) from jobrunr_backgroundjobservers)                                as nbrOfBackgroundJobServers,
       (select count(*) from jobrunr_recurring_jobs)                                      as nbrOfRecurringJobs
from jobrunr_jobs j;

-- ── JobRunr migration: v006__alter_table_jobs_add_recurringjob.sql ────────────────────────────────────────────
ALTER TABLE jobrunr_jobs
    ADD recurringJobId VARCHAR(128);
CREATE INDEX jobrunr_job_rci_idx ON jobrunr_jobs (recurringJobId);

-- ── JobRunr migration: v007__alter_table_backgroundjobserver_add_delete_config.sql ────────────────────────────────────────────
ALTER TABLE jobrunr_backgroundjobservers
    ADD deleteSucceededJobsAfter VARCHAR(32);
ALTER TABLE jobrunr_backgroundjobservers
    ADD permanentlyDeleteJobsAfter VARCHAR(32);

-- ── JobRunr migration: v008__alter_table_jobs_increase_jobAsJson_size.sql ────────────────────────────────────────────
-- Empty migration so all databases follow the same numbering;

-- ── JobRunr migration: v009__change_jobrunr_job_counters_to_jobrunr_metadata.sql ────────────────────────────────────────────
CREATE TABLE jobrunr_metadata
(
    id        varchar(156) PRIMARY KEY,
    name      varchar(92) NOT NULL,
    owner     varchar(64) NOT NULL,
    value     text        NOT NULL,
    createdAt TIMESTAMP   NOT NULL,
    updatedAt TIMESTAMP   NOT NULL
);

INSERT INTO jobrunr_metadata (id, name, owner, value, createdAt, updatedAt)
VALUES ('succeeded-jobs-counter-cluster', 'succeeded-jobs-counter', 'cluster',
        cast((select amount from jobrunr_job_counters where name = 'SUCCEEDED') as char(10)), CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP);

DROP VIEW jobrunr_jobs_stats;
DROP TABLE jobrunr_job_counters;

CREATE VIEW jobrunr_jobs_stats
as
select count(*)                                                                 as total,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'AWAITING')   as awaiting,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'SCHEDULED')  as scheduled,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'ENQUEUED')   as enqueued,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'PROCESSING') as processing,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'FAILED')     as failed,
       (select((select count(*) from jobrunr_jobs jobs where jobs.state = 'SUCCEEDED') +
               (select cast(cast(value as char(10)) as decimal(10, 0))
                from jobrunr_metadata jm
                where jm.id = 'succeeded-jobs-counter-cluster')))               as succeeded,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'DELETED')    as deleted,
       (select count(*) from jobrunr_backgroundjobservers)                      as nbrOfBackgroundJobServers,
       (select count(*) from jobrunr_recurring_jobs)                            as nbrOfRecurringJobs
from jobrunr_jobs j;

-- ── JobRunr migration: v010__change_job_stats.sql ────────────────────────────────────────────
DROP VIEW jobrunr_jobs_stats;

CREATE VIEW jobrunr_jobs_stats
as
select count(*)                                                                 as total,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'AWAITING')   as awaiting,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'SCHEDULED')  as scheduled,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'ENQUEUED')   as enqueued,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'PROCESSING') as processing,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'FAILED')     as failed,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'SUCCEEDED')  as succeeded,
       (select cast(cast(value as char(10)) as decimal(10, 0))
        from jobrunr_metadata jm
        where jm.id = 'succeeded-jobs-counter-cluster')                         as allTimeSucceeded,
       (select count(*) from jobrunr_jobs jobs where jobs.state = 'DELETED')    as deleted,
       (select count(*) from jobrunr_backgroundjobservers)                      as nbrOfBackgroundJobServers,
       (select count(*) from jobrunr_recurring_jobs)                            as nbrOfRecurringJobs
from jobrunr_jobs j;

-- ── JobRunr migration: v011__change_sqlserver_text_to_varchar.sql ────────────────────────────────────────────
-- Empty migration so all databases follow the same numbering;

-- ── JobRunr migration: v012__change_oracle_alter_jobrunr_metadata_column_size.sql ────────────────────────────────────────────
-- Empty migration so all databases follow the same numbering;

-- ── JobRunr migration: v013__alter_table_recurring_job_add_createdAt.sql ────────────────────────────────────────────
ALTER TABLE jobrunr_recurring_jobs
    ADD createdAt BIGINT NOT NULL DEFAULT '0';
CREATE INDEX jobrunr_recurring_job_created_at_idx ON jobrunr_recurring_jobs (createdAt);

-- ── JobRunr migration: v014__improve_job_stats.sql ────────────────────────────────────────────
DROP VIEW jobrunr_jobs_stats;
CREATE VIEW jobrunr_jobs_stats
as
with job_stat_results as (SELECT state, count(*) as count
                          FROM jobrunr_jobs
                          GROUP BY ROLLUP (state))
select coalesce((select count from job_stat_results where state IS NULL), 0)        as total,
       coalesce((select count from job_stat_results where state = 'SCHEDULED'), 0)  as scheduled,
       coalesce((select count from job_stat_results where state = 'ENQUEUED'), 0)   as enqueued,
       coalesce((select count from job_stat_results where state = 'PROCESSING'), 0) as processing,
       coalesce((select count from job_stat_results where state = 'FAILED'), 0)     as failed,
       coalesce((select count from job_stat_results where state = 'SUCCEEDED'), 0)  as succeeded,
       coalesce((select cast(cast(value as char(10)) as decimal(10, 0))
                 from jobrunr_metadata jm
                 where jm.id = 'succeeded-jobs-counter-cluster'), 0)                as allTimeSucceeded,
       coalesce((select count from job_stat_results where state = 'DELETED'), 0)    as deleted,
       (select count(*) from jobrunr_backgroundjobservers)                          as nbrOfBackgroundJobServers,
       (select count(*) from jobrunr_recurring_jobs)                                as nbrOfRecurringJobs;

DROP INDEX jobrunr_job_updated_at_idx;
CREATE INDEX jobrunr_jobs_state_updated_idx ON jobrunr_jobs (state ASC, updatedAt ASC);

-- ── JobRunr migration: v015__alter_table_backgroundjobserver_add_name.sql ────────────────────────────────────────────
ALTER TABLE jobrunr_backgroundjobservers
    ADD name VARCHAR(128);

