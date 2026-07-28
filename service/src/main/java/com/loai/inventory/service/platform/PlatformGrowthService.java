package com.loai.inventory.service.platform;

import com.loai.inventory.domain.model.PlatformFunnelPath;
import com.loai.inventory.domain.model.PlatformFunnelStage;
import com.loai.inventory.domain.model.PlatformGrowthBucket;
import com.loai.inventory.domain.repository.PlatformFunnelRepository;
import com.loai.inventory.domain.repository.PlatformFunnelRepositoryFactory;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;

/**
 * The growth series behind {@code GET /api/admin/growth} (slice 8, {@code
 * stories/platform_growth_series.md}) — window/bucket/path parsing plus series assembly, over
 * {@link PlatformFunnelRepository#eventCounts}.
 *
 * <p><strong>Event-based where the funnel is cohort-based.</strong> The window bounds {@code
 * reached_at} — what happened in each bucket — not {@code org.created_at}; an org registered two
 * years ago whose first payment lands this week belongs in this week's FIRST_PAYMENT point and
 * <em>not</em> in the same-window funnel cohort. The one deliberate bridge between the surfaces is
 * that Σ REGISTERED points equals the funnel's {@code cohort.size} for the same window+path — true
 * by construction because {@code REGISTERED.reached_at} <em>is</em> {@code org.created_at} — and
 * nothing else may be reconciled between them.
 *
 * <p><strong>Counts only — no rate, delta, or percent-growth, ever, on any layer.</strong> A
 * derivative over small counts is the funnel's percentage problem squared: one tenant in an empty
 * week is "+100 %". The wire carries events; a surface that ever wants a rate must clear its own
 * small-denominator bar client-side, where the room to phrase it lives.
 *
 * <p><strong>{@code current_period} is the partial-bucket honesty mechanism.</strong> The newest
 * bucket is incomplete until it closes, so every naive growth chart ends in an apparent collapse.
 * The server neither drops the open bucket (discarding data is its own lie) nor flags per-point
 * (one fact, one place): it names the still-open period on the envelope and the client renders any
 * point at that period as provisional.
 *
 * <p><strong>All six stages, always, in {@link PlatformFunnelStage} order</strong> — a stage nobody
 * reached this window is {@code points: []}, never omitted ("nobody arrived" and "no data" are
 * opposite messages; slice 7's rule, unchanged). Within a stage, points are sparse: an absent
 * bucket <em>means zero events</em> — {@code org_milestone} is the complete record — which is a
 * stated deviation of meaning from the reports' sparse contract, where an absent bucket merely
 * wasn't summed. The client may zero-fill with confidence the reports never gave it.
 */
public class PlatformGrowthService {

  private final DSLContext dsl;
  private final PlatformFunnelRepositoryFactory repoFactory;

  public PlatformGrowthService(DSLContext dsl, PlatformFunnelRepositoryFactory repoFactory) {
    this.dsl = dsl;
    this.repoFactory = repoFactory;
  }

  /** One bucket's event count for one stage. */
  public record Point(OffsetDateTime period, long count) {}

  /** One stage's sparse series, in server stage order. */
  public record StageSeries(PlatformFunnelStage stage, List<Point> points) {}

  /** The whole response: one {@code as_of}, one envelope-level {@code current_period}. */
  public record Growth(
      OffsetDateTime asOf,
      String window,
      PlatformGrowthBucket bucket,
      PlatformFunnelPath path,
      OffsetDateTime from,
      OffsetDateTime currentPeriod,
      List<StageSeries> series) {}

  /**
   * @throws com.loai.inventory.common.exception.ValidationException unknown/missing {@code window},
   *     {@code bucket}, or {@code path}, naming the accepted values.
   */
  public Growth growth(String windowRaw, String bucketRaw, String pathRaw, OffsetDateTime asOf) {
    PlatformGrowthQuery.Window window = PlatformGrowthQuery.parseWindow(windowRaw);
    PlatformGrowthBucket bucket = PlatformGrowthQuery.parseBucket(bucketRaw);
    PlatformFunnelPath path = PlatformFunnelQuery.parsePath(pathRaw);
    OffsetDateTime from = window.from(asOf);

    List<PlatformFunnelRepository.EventPoint> rows =
        repoFactory.create(dsl).eventCounts(from, bucket, path);

    Map<String, List<Point>> byMilestone = new LinkedHashMap<>();
    for (PlatformFunnelRepository.EventPoint row : rows) {
      byMilestone
          .computeIfAbsent(row.milestone(), k -> new ArrayList<>())
          .add(new Point(row.period(), row.count()));
    }
    List<StageSeries> series =
        Arrays.stream(PlatformFunnelStage.values())
            .map(
                s -> new StageSeries(s, List.copyOf(byMilestone.getOrDefault(s.name(), List.of()))))
            .toList();

    return new Growth(asOf, window.wire(), bucket, path, from, currentPeriod(bucket, asOf), series);
  }

  /**
   * {@code date_trunc(bucket, asOf)} in UTC, in Java — the still-open bucket named on the envelope.
   * Must agree with the Postgres expression the repository groups by ({@code date_trunc(…, 'UTC')}:
   * weeks start Monday, months on the 1st); {@code
   * PlatformGrowthIT.currentPeriodNamesTheOpenBucket_andItsEventsAreNotDropped} pins the two
   * against each other so they cannot drift.
   */
  static OffsetDateTime currentPeriod(PlatformGrowthBucket bucket, OffsetDateTime asOf) {
    LocalDate utcDate = asOf.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    LocalDate start =
        switch (bucket) {
          case WEEK -> utcDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
          case MONTH -> utcDate.withDayOfMonth(1);
        };
    return start.atStartOfDay().atOffset(ZoneOffset.UTC);
  }
}
