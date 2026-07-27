package com.loai.inventory.domain.model;

import java.time.Instant;

/**
 * Raw outcome facts for one recurring job, read off JobRunr's own tables. Deliberately free of any
 * judgement — the {@code HEALTHY | FAILING | STALE | UNKNOWN} classification lives in the service,
 * where the job's configured cron period is known and can be unit-tested.
 *
 * <p><strong>{@code lastSuccessAt == null} means "no success on record", not "never ran".</strong>
 * JobRunr deletes SUCCEEDED rows once its retention window passes, so for a low-frequency job an
 * absent success is the normal steady state, not an incident. Callers (and the UI) must word it
 * that way.
 *
 * @param jobId the recurring-job id as registered with JobRunr (e.g. {@code order-ttl-sweeper})
 * @param lastSuccessAt newest {@code SUCCEEDED} row's {@code updatedat}, or null if none survives
 * @param lastFailureAt newest {@code FAILED} row's {@code updatedat}, or null if there is none
 * @param consecutiveFailures {@code FAILED} rows newer than {@code lastSuccessAt} (all of them when
 *     there is no success on record)
 * @param nextScheduledAt earliest {@code SCHEDULED} row's {@code scheduledat}, or null
 */
public record RecurringJobStats(
    String jobId,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    long consecutiveFailures,
    Instant nextScheduledAt) {}
