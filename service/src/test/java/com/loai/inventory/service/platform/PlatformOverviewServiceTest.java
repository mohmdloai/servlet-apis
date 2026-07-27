package com.loai.inventory.service.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loai.inventory.domain.model.PlatformQueueCounts;
import com.loai.inventory.domain.model.PlatformTenantCounts;
import com.loai.inventory.domain.model.RecurringJobStats;
import com.loai.inventory.domain.repository.PlatformStatsRepository;
import com.loai.inventory.domain.repository.PlatformStatsRepositoryFactory;
import com.loai.inventory.service.platform.PlatformOverviewService.JobConfig;
import com.loai.inventory.service.platform.PlatformOverviewService.JobState;
import com.loai.inventory.service.platform.PlatformOverviewService.Overview;
import com.loai.inventory.service.platform.PlatformOverviewService.RecurringJobHealth;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

/**
 * The parts of {@code stories/platform_overview.md} an integration test cannot force: a throwing
 * collaborator (you cannot make a live Postgres fail one query on demand) and the job-state
 * classification across its four outcomes.
 *
 * <p>The headline assertion in the degradation tests is not "it returned 200" — it is that the
 * failed section is {@code null} <em>and not</em> {@code 0}. A zero standing in for a thrown query
 * is the failure mode this whole slice exists to prevent.
 */
class PlatformOverviewServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-27T09:14:02Z");
  private static final Instant STARTED = Instant.parse("2026-07-27T08:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

  private static final String SWEEPER = "order-ttl-sweeper";
  private static final Duration EVERY_30S = Duration.ofSeconds(30);
  private static final Duration DAILY = Duration.ofDays(1);

  private final DSLContext dsl = mock(DSLContext.class);
  private final PlatformStatsRepository repo = mock(PlatformStatsRepository.class);

  private PlatformOverviewService service(List<JobConfig> jobs, boolean jobsEnabled) {
    PlatformStatsRepositoryFactory factory = mock(PlatformStatsRepositoryFactory.class);
    when(factory.create(any())).thenReturn(repo);
    return new PlatformOverviewService(dsl, factory, jobsEnabled, jobs, "9f3c1ab", STARTED, FIXED);
  }

  private PlatformOverviewService service() {
    return service(List.of(new JobConfig(SWEEPER, EVERY_30S)), true);
  }

  private void healthyRepo() {
    when(repo.tenantCounts()).thenReturn(new PlatformTenantCounts(42, 37, 3, 2, 4));
    when(repo.queueCounts()).thenReturn(new PlatformQueueCounts(3, 7, 2, 1, 0));
    when(repo.backgroundJobServerCount()).thenReturn(1L);
    when(repo.recurringJobStats(any()))
        .thenReturn(List.of(new RecurringJobStats(SWEEPER, NOW.minusSeconds(20), null, 0, null)));
  }

  // The honesty contract

  @Test
  void everythingResolves_noDegradedSection() {
    healthyRepo();
    Overview o = service().overview();

    assertEquals(NOW, o.asOf());
    assertTrue(o.degraded().isEmpty());
    assertEquals(42, o.tenants().total());
    assertEquals(3, o.queues().failedEmails());
    assertTrue(o.jobs().enabled());
    assertEquals("9f3c1ab", o.build().commit());
    assertEquals(STARTED, o.build().startedAt());
  }

  @Test
  void aThrowingQueueRead_degradesOnlyQueues_andIsNullNotZero() {
    healthyRepo();
    when(repo.queueCounts()).thenThrow(new IllegalStateException("relation does not exist"));

    Overview o = service().overview();

    // The point of the slice: a failed count is absent, never a reassuring zero.
    assertNull(o.queues());
    assertEquals(List.of("queues"), o.degraded());
    // Every other section is intact — one bad section degrades itself and nothing else.
    assertNotNull(o.tenants());
    assertEquals(42, o.tenants().total());
    assertNotNull(o.jobs());
    assertNotNull(o.build());
  }

  @Test
  void aThrowingTenantRead_degradesOnlyTenants() {
    healthyRepo();
    when(repo.tenantCounts()).thenThrow(new IllegalStateException("boom"));

    Overview o = service().overview();

    assertNull(o.tenants());
    assertEquals(List.of("tenants"), o.degraded());
    assertNotNull(o.queues());
    assertEquals(3, o.queues().failedEmails());
  }

  @Test
  void aThrowingJobRead_degradesOnlyJobs() {
    healthyRepo();
    when(repo.recurringJobStats(any())).thenThrow(new IllegalStateException("no such column"));

    Overview o = service().overview();

    assertNull(o.jobs());
    assertEquals(List.of("jobs"), o.degraded());
    assertNotNull(o.tenants());
    assertNotNull(o.queues());
    assertNotNull(o.build());
  }

  @Test
  void severalFailures_areAllNamed_andTheReadStillReturns() {
    when(repo.tenantCounts()).thenThrow(new IllegalStateException("a"));
    when(repo.queueCounts()).thenThrow(new IllegalStateException("b"));
    when(repo.recurringJobStats(any())).thenReturn(List.of());
    when(repo.backgroundJobServerCount()).thenReturn(0L);

    Overview o = service().overview();

    assertEquals(List.of("tenants", "queues"), o.degraded());
    assertNull(o.tenants());
    assertNull(o.queues());
    assertNotNull(o.jobs());
    assertEquals(NOW, o.asOf());
  }

  @Test
  void everySectionSharesTheOneAsOf() {
    healthyRepo();
    Overview a = service().overview();
    Overview b = service().overview();
    // The clock is fixed, so this pins that as_of is stamped once per read rather than per section.
    assertEquals(a.asOf(), b.asOf());
    assertEquals(NOW, a.asOf());
  }

  // Build identity

  @Test
  void anUnsetBuildCommit_isNull_notAPlaceholder() {
    healthyRepo();
    PlatformStatsRepositoryFactory factory = mock(PlatformStatsRepositoryFactory.class);
    when(factory.create(any())).thenReturn(repo);
    PlatformOverviewService svc =
        new PlatformOverviewService(
            dsl, factory, true, List.of(new JobConfig(SWEEPER, EVERY_30S)), "  ", STARTED, FIXED);

    assertNull(svc.overview().build().commit());
    assertEquals(STARTED, svc.overview().build().startedAt());
  }

  // Jobs disabled

  @Test
  void jobsDisabled_isACalmState_notThreeDeadJobs() {
    healthyRepo();
    Overview o = service(List.of(new JobConfig(SWEEPER, EVERY_30S)), false).overview();

    assertFalse(o.jobs().enabled());
    assertTrue(o.jobs().recurring().isEmpty());
    assertEquals(0, o.jobs().servers());
    // Not a failure — nothing is degraded by turning background jobs off.
    assertTrue(o.degraded().isEmpty());
  }

  // Job-state classification

  private JobState classify(RecurringJobStats stats, Duration period) {
    when(repo.tenantCounts()).thenReturn(new PlatformTenantCounts(0, 0, 0, 0, 0));
    when(repo.queueCounts()).thenReturn(new PlatformQueueCounts(0, 0, 0, 0, 0));
    when(repo.backgroundJobServerCount()).thenReturn(1L);
    when(repo.recurringJobStats(any())).thenReturn(List.of(stats));
    List<RecurringJobHealth> jobs =
        service(List.of(new JobConfig(stats.jobId(), period)), true).overview().jobs().recurring();
    assertEquals(1, jobs.size());
    return jobs.get(0).state();
  }

  @Test
  void aRecentSuccess_isHealthy() {
    assertEquals(
        JobState.HEALTHY,
        classify(new RecurringJobStats(SWEEPER, NOW.minusSeconds(20), null, 0, null), EVERY_30S));
  }

  @Test
  void failuresNewerThanTheLastSuccess_areFailing() {
    assertEquals(
        JobState.FAILING,
        classify(
            new RecurringJobStats(SWEEPER, NOW.minusSeconds(90), NOW.minusSeconds(10), 3, null),
            EVERY_30S));
  }

  @Test
  void aFailingJobStaysFailing_evenWhenTheLastSuccessIsAlsoStale() {
    // Precedence matters: "it is erroring" is the more actionable statement than "it is quiet".
    assertEquals(
        JobState.FAILING,
        classify(
            new RecurringJobStats(SWEEPER, NOW.minusSeconds(3600), NOW.minusSeconds(5), 12, null),
            EVERY_30S));
  }

  @Test
  void noSuccessAndNoFailureOnRecord_isUnknown_notHealthy() {
    // JobRunr reaps its own SUCCEEDED history, so this is "no success on record", not "never ran".
    // Reporting it as HEALTHY would be a guess; reporting it as FAILING would be a lie.
    assertEquals(
        JobState.UNKNOWN, classify(new RecurringJobStats(SWEEPER, null, null, 0, null), DAILY));
  }

  @Test
  void aSuccessOlderThanThreeCronPeriods_isStale() {
    assertEquals(
        JobState.STALE,
        classify(new RecurringJobStats(SWEEPER, NOW.minusSeconds(600), null, 0, null), EVERY_30S));
  }

  @Test
  void reTuningTheCronDoesNotTurnAHealthyJobStale() {
    // The same observation — a success 10 minutes ago — read against two configured cadences.
    RecurringJobStats tenMinutesAgo =
        new RecurringJobStats(SWEEPER, NOW.minus(Duration.ofMinutes(10)), null, 0, null);

    // At a 30s cadence, ten minutes of silence is dead.
    assertEquals(JobState.STALE, classify(tenMinutesAgo, EVERY_30S));
    // Re-tuned to hourly, the very same job is simply between runs. The window follows the cron,
    // so an operator who widens an interval does not wake up to a red dashboard.
    assertEquals(JobState.HEALTHY, classify(tenMinutesAgo, Duration.ofHours(1)));
    assertEquals(JobState.HEALTHY, classify(tenMinutesAgo, DAILY));
  }

  @Test
  void aFastCronStillGetsAGenerousFloor() {
    // 3 × 10s would be a 30s window — one slow tick would paint the delivery sweeper red. The floor
    // keeps the panel about a dead scheduler rather than a busy one.
    assertEquals(
        JobState.HEALTHY,
        classify(
            new RecurringJobStats(SWEEPER, NOW.minusSeconds(45), null, 0, null),
            Duration.ofSeconds(10)));
    assertEquals(
        JobState.STALE,
        classify(
            new RecurringJobStats(SWEEPER, NOW.minusSeconds(600), null, 0, null),
            Duration.ofSeconds(10)));
  }

  @Test
  void anUnparseableCronPeriod_readsUnknown_ratherThanGuessing() {
    // AppConfig hands null when it cannot derive a period. There is then no definition of "on
    // time", so the honest answer is UNKNOWN — not HEALTHY, and not STALE.
    assertEquals(
        JobState.UNKNOWN,
        classify(new RecurringJobStats(SWEEPER, NOW.minusSeconds(20), null, 0, null), null));
  }

  @Test
  void theJobRowCarriesTheRawFactsThrough() {
    Instant success = NOW.minusSeconds(20);
    Instant failure = NOW.minusSeconds(400);
    Instant next = NOW.plusSeconds(10);
    when(repo.tenantCounts()).thenReturn(new PlatformTenantCounts(0, 0, 0, 0, 0));
    when(repo.queueCounts()).thenReturn(new PlatformQueueCounts(0, 0, 0, 0, 0));
    when(repo.backgroundJobServerCount()).thenReturn(2L);
    when(repo.recurringJobStats(any()))
        .thenReturn(List.of(new RecurringJobStats(SWEEPER, success, failure, 0, next)));

    RecurringJobHealth job = service().overview().jobs().recurring().get(0);

    assertEquals(SWEEPER, job.id());
    assertEquals(success, job.lastSuccessAt());
    assertEquals(failure, job.lastFailureAt());
    assertEquals(0, job.consecutiveFailures());
    assertEquals(next, job.nextScheduledAt());
    assertEquals(JobState.HEALTHY, job.state());
    assertEquals(2, service().overview().jobs().servers());
  }
}
