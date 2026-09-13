package com.loai.inventory.service.platform;

import com.loai.inventory.domain.model.PlatformQueueCounts;
import com.loai.inventory.domain.model.PlatformTenantCounts;
import com.loai.inventory.domain.model.RecurringJobStats;
import com.loai.inventory.domain.model.TicketDeskCounts;
import com.loai.inventory.domain.repository.PlatformStatsRepository;
import com.loai.inventory.domain.repository.PlatformStatsRepositoryFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the platform operator's home read ({@code GET /api/admin/overview}).
 *
 * <p><strong>The honesty contract.</strong> A section that fails to compute is {@code null} and its
 * name appears in {@code degraded}. It is <em>never</em> zero. A {@code 0} that actually means "the
 * query threw" is the one failure mode that makes an operations dashboard worse than no dashboard —
 * it reports <em>all clear</em> on the exact incident it exists to catch. So each of {@code
 * tenants}/{@code queues}/{@code jobs}/{@code build} is assembled independently and caught
 * independently: one bad section degrades only itself and the read still returns 200 with the rest.
 *
 * <p><strong>One {@code asOf} for the whole screen.</strong> Stamped once, before anything is read.
 * Six reads would mean six different "now"s presented to an operator as one moment, which is the
 * first way a dashboard starts lying.
 */
public class PlatformOverviewService {

  private static final Logger log = LoggerFactory.getLogger(PlatformOverviewService.class);

  /**
   * How many of a job's own cron periods may pass with no success before it is called STALE. Read
   * off the configured cron rather than a fixed wall-clock window, so re-tuning an interval can
   * never make a healthy job look dead.
   */
  private static final int STALE_PERIODS = 3;

  /**
   * Floor under the staleness window. The delivery sweeper runs every 10s; without this, a single
   * slow tick would paint it red. Generous is the point — this panel exists to catch a dead
   * scheduler, not a slow one.
   */
  private static final Duration MIN_STALE_WINDOW = Duration.ofMinutes(2);

  /** How a recurring job is doing. {@code UNKNOWN} is never reported as {@code HEALTHY}. */
  public enum JobState {
    HEALTHY,
    FAILING,
    STALE,
    UNKNOWN
  }

  /**
   * A job as configured, not as observed: its JobRunr id and the period implied by the cron {@code
   * AppConfig} schedules it with. The period is supplied rather than parsed here so this service
   * stays free of a scheduler dependency — and so a test can re-tune an interval in one argument.
   */
  public record JobConfig(String id, Duration period) {}

  /** One job's row on the panel. */
  public record RecurringJobHealth(
      String id,
      Instant lastSuccessAt,
      Instant lastFailureAt,
      long consecutiveFailures,
      Instant nextScheduledAt,
      JobState state) {}

  /**
   * The jobs section. {@code enabled=false} (the {@code ORDER_SWEEPER_BACKGROUND_ENABLED=false}
   * deployment, and every test environment) is a real, calm state with an empty {@code recurring}
   * list — never three dead jobs, or operators learn to ignore the colour.
   */
  public record Jobs(boolean enabled, long servers, List<RecurringJobHealth> recurring) {}

  /**
   * Which build is live. {@code commit} is optional — unset locally, so the UI says "dev". There is
   * deliberately no version field: the runtime image copies {@code classes/} + {@code lib/} rather
   * than a jar, so there is no manifest to read, and {@code 1.0-SNAPSHOT} would tell nobody
   * anything.
   */
  public record Build(String commit, Instant startedAt) {}

  /**
   * @param degraded names of the sections that failed to compute; empty when everything resolved
   */
  public record Overview(
      Instant asOf,
      PlatformTenantCounts tenants,
      PlatformQueueCounts queues,
      TicketDeskCounts support,
      Jobs jobs,
      Build build,
      List<String> degraded) {}

  private final DSLContext rootDsl;
  private final PlatformStatsRepositoryFactory statsRepoFactory;
  private final boolean jobsEnabled;
  private final List<JobConfig> jobConfigs;
  private final String buildCommit;
  private final Instant startedAt;
  private final Clock clock;

  public PlatformOverviewService(
      DSLContext rootDsl,
      PlatformStatsRepositoryFactory statsRepoFactory,
      boolean jobsEnabled,
      List<JobConfig> jobConfigs,
      String buildCommit,
      Instant startedAt) {
    this(
        rootDsl,
        statsRepoFactory,
        jobsEnabled,
        jobConfigs,
        buildCommit,
        startedAt,
        Clock.systemUTC());
  }

  PlatformOverviewService(
      DSLContext rootDsl,
      PlatformStatsRepositoryFactory statsRepoFactory,
      boolean jobsEnabled,
      List<JobConfig> jobConfigs,
      String buildCommit,
      Instant startedAt,
      Clock clock) {
    this.rootDsl = rootDsl;
    this.statsRepoFactory = statsRepoFactory;
    this.jobsEnabled = jobsEnabled;
    this.jobConfigs = jobConfigs == null ? List.of() : List.copyOf(jobConfigs);
    this.buildCommit = blankToNull(buildCommit);
    this.startedAt = startedAt;
    this.clock = clock;
  }

  /**
   * The whole read. Never throws for a section-level failure: the caller gets a 200 with the
   * sections that resolved, the ones that did not set to {@code null}, and their names in {@code
   * degraded}.
   */
  public Overview overview() {
    Instant asOf = clock.instant();
    List<String> degraded = new ArrayList<>();
    PlatformStatsRepository repo = statsRepoFactory.create(rootDsl);

    PlatformTenantCounts tenants = section("tenants", degraded, repo::tenantCounts);
    PlatformQueueCounts queues = section("queues", degraded, repo::queueCounts);
    // The support desk's counts (stories/support_tickets.md) — the same predicate the inbox tabs
    // read, so the tile and the tab cannot disagree; degradable like every other section.
    TicketDeskCounts support = section("support", degraded, repo::ticketCounts);
    Jobs jobs = section("jobs", degraded, () -> jobs(repo, asOf));
    Build build = section("build", degraded, () -> new Build(buildCommit, startedAt));

    return new Overview(asOf, tenants, queues, support, jobs, build, List.copyOf(degraded));
  }

  /**
   * Run one section's assembly, and on any failure record its name and hand back {@code null}. This
   * is the honesty contract in code: the caller has no way to turn a failure into a zero, because a
   * failure never produces a value.
   */
  private <T> T section(String name, List<String> degraded, Supplier<T> assemble) {
    try {
      return assemble.get();
    } catch (RuntimeException e) {
      log.error("Platform overview section '{}' failed to compute", name, e);
      degraded.add(name);
      return null;
    }
  }

  private Jobs jobs(PlatformStatsRepository repo, Instant asOf) {
    // The flag is the authority, not the absence of rows: with background jobs off there is nothing
    // registered, and saying so explicitly is a different statement from "three jobs are dead".
    if (!jobsEnabled) {
      return new Jobs(false, 0L, List.of());
    }
    // Built by hand, not Collectors.toMap: a null period is a legitimate value here (AppConfig
    // hands null for a cron it cannot parse, which reads as UNKNOWN downstream) and toMap NPEs on
    // a null value — which would degrade the whole jobs section instead of reporting one job's
    // state honestly.
    Map<String, Duration> periods = new LinkedHashMap<>();
    for (JobConfig config : jobConfigs) {
      periods.putIfAbsent(config.id(), config.period());
    }
    List<RecurringJobStats> stats = repo.recurringJobStats(List.copyOf(periods.keySet()));
    List<RecurringJobHealth> recurring =
        stats.stream()
            .map(
                s ->
                    new RecurringJobHealth(
                        s.jobId(),
                        s.lastSuccessAt(),
                        s.lastFailureAt(),
                        s.consecutiveFailures(),
                        s.nextScheduledAt(),
                        classify(s, periods.get(s.jobId()), asOf)))
            .toList();
    return new Jobs(true, repo.backgroundJobServerCount(), recurring);
  }

  /**
   * Classify one job. The order matters:
   *
   * <ol>
   *   <li>Failures newer than the last success ⇒ {@code FAILING}, whatever else is true.
   *   <li>No success on record and no failures ⇒ {@code UNKNOWN} — registered, but there is nothing
   *       to judge on. Deliberately <em>not</em> {@code HEALTHY}. Note this is also the honest
   *       reading after JobRunr reaps its own SUCCEEDED history, which is why the wording
   *       downstream is "no success on record", not "never ran".
   *   <li>The last success is older than {@link #STALE_PERIODS} of the job's own cron period ⇒
   *       {@code STALE}. Anchoring to the configured period is what stops a re-tuned interval from
   *       making a healthy job look dead.
   *   <li>Otherwise {@code HEALTHY}.
   * </ol>
   */
  private JobState classify(RecurringJobStats stats, Duration period, Instant asOf) {
    if (stats.consecutiveFailures() > 0) {
      return JobState.FAILING;
    }
    if (stats.lastSuccessAt() == null) {
      return JobState.UNKNOWN;
    }
    Duration window = staleWindow(period);
    if (window == null) {
      // No period to judge against (an id with no configured cron) — say so rather than guess.
      return JobState.UNKNOWN;
    }
    return stats.lastSuccessAt().isBefore(asOf.minus(window)) ? JobState.STALE : JobState.HEALTHY;
  }

  private Duration staleWindow(Duration period) {
    if (period == null || period.isZero() || period.isNegative()) {
      return null;
    }
    Duration scaled = period.multipliedBy(STALE_PERIODS);
    return scaled.compareTo(MIN_STALE_WINDOW) < 0 ? MIN_STALE_WINDOW : scaled;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
