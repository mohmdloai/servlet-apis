package com.loai.inventory.service.platform;

import com.loai.inventory.domain.model.PlatformFunnelPath;
import com.loai.inventory.domain.model.PlatformFunnelStage;
import com.loai.inventory.domain.repository.PlatformFunnelRepository;
import com.loai.inventory.domain.repository.PlatformFunnelRepositoryFactory;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;

/**
 * The tenant-lifecycle funnel behind {@code GET /api/admin/funnel} (slice 7, {@code
 * stories/platform_tenant_funnel.md}) — cohort/path parsing plus the stage-ordering arithmetic,
 * over {@link PlatformFunnelRepository}'s counts-only cross-org read.
 *
 * <p><strong>Counts only — no percentage is computed here, ever.</strong> The story's decision 1 is
 * explicit: a percentage over a small cohort is noise wearing a decimal point, and the client is
 * the only layer that knows how much room it has to say "3 of 7" instead of "43%". This service
 * hands back {@code reached} and {@code size}; nothing derived.
 *
 * <p><strong>All six stages, always, in {@link PlatformFunnelStage} order</strong> — including a
 * stage with {@code reached: 0}. Omitting a stage the cohort never reached would read as "no data"
 * when it means "nobody got here", and those are opposite messages (the story's Tests section, and
 * {@code PlatformFunnelServiceTest}).
 */
public class PlatformFunnelService {

  private final DSLContext dsl;
  private final PlatformFunnelRepositoryFactory repoFactory;

  public PlatformFunnelService(DSLContext dsl, PlatformFunnelRepositoryFactory repoFactory) {
    this.dsl = dsl;
    this.repoFactory = repoFactory;
  }

  /** One stage's reached count, in server order. */
  public record StageCount(PlatformFunnelStage stage, long reached) {}

  /**
   * The cohort's window label (echoing the request), lower bound, size, and the age in days of its
   * youngest (most recently registered) member — {@code null} only when the cohort is empty, so the
   * client can render the "young cohort" caveat exactly when there is a member young enough to need
   * it (story 81, decision 2).
   */
  public record Cohort(String window, OffsetDateTime from, long size, Long youngestAgeDays) {}

  /** The whole response: one {@code as_of}, one cohort, one path, all six stages. */
  public record Funnel(
      OffsetDateTime asOf, Cohort cohort, PlatformFunnelPath path, List<StageCount> stages) {}

  /**
   * @throws com.loai.inventory.common.exception.ValidationException unknown/missing {@code cohort}
   *     or {@code path}, naming the accepted values.
   */
  public Funnel funnel(String cohortRaw, String pathRaw, OffsetDateTime asOf) {
    PlatformFunnelQuery.Cohort cohort = PlatformFunnelQuery.parseCohort(cohortRaw);
    PlatformFunnelPath path = PlatformFunnelQuery.parsePath(pathRaw);
    OffsetDateTime from = cohort.from(asOf);

    PlatformFunnelRepository repo = repoFactory.create(dsl);
    PlatformFunnelRepository.CohortStats stats = repo.cohortStats(from, path);
    Map<String, Long> reached = repo.stageCounts(from, path);

    List<StageCount> stages =
        Arrays.stream(PlatformFunnelStage.values())
            .map(s -> new StageCount(s, reached.getOrDefault(s.name(), 0L)))
            .toList();

    Long youngestAgeDays =
        stats
            .youngestCreatedAt()
            .map(t -> Math.max(0, Duration.between(t, asOf).toDays()))
            .orElse(null);

    return new Funnel(
        asOf, new Cohort(cohort.wire(), from, stats.size(), youngestAgeDays), path, stages);
  }
}
