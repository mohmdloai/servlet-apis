package com.loai.inventory.service.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PlatformFunnelPath;
import com.loai.inventory.domain.model.PlatformFunnelStage;
import com.loai.inventory.domain.model.PlatformGrowthBucket;
import com.loai.inventory.domain.repository.PlatformFunnelRepository;
import com.loai.inventory.domain.repository.PlatformFunnelRepository.EventPoint;
import com.loai.inventory.domain.repository.PlatformFunnelRepositoryFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The parts of {@link PlatformGrowthService} an integration test cannot force as cleanly: the
 * parsing tables and their 400s naming the options, the {@code from}/{@code current_period}
 * arithmetic against a fixed {@code asOf} (UTC week and month edges included), and the series
 * assembly contract — all six stages in server order, an untouched stage as {@code points: []}, an
 * unknown milestone dropped.
 */
class PlatformGrowthServiceTest {

  /** 2026-07-28 is a Tuesday; its UTC week starts Monday 2026-07-27. */
  private static final OffsetDateTime AS_OF =
      OffsetDateTime.of(2026, 7, 28, 12, 0, 0, 0, ZoneOffset.UTC);

  private PlatformFunnelRepository repo;
  private PlatformGrowthService service;

  @BeforeEach
  void setUp() {
    repo = mock(PlatformFunnelRepository.class);
    PlatformFunnelRepositoryFactory factory = mock(PlatformFunnelRepositoryFactory.class);
    when(factory.create(any())).thenReturn(repo);
    when(repo.eventCounts(any(), any(), any())).thenReturn(List.of());
    service = new PlatformGrowthService(mock(DSLContext.class), factory);
  }

  // ------------------------------------------------------------------ window parsing

  @Test
  void window90d_boundsNinetyDaysBeforeAsOf() {
    service.growth("90d", "week", "all", AS_OF);
    verify(repo)
        .eventCounts(
            eq(AS_OF.minusDays(90)), eq(PlatformGrowthBucket.WEEK), eq(PlatformFunnelPath.ALL));
  }

  @Test
  void window365d_boundsAYearBeforeAsOf() {
    service.growth("365d", "week", "all", AS_OF);
    verify(repo)
        .eventCounts(
            eq(AS_OF.minusDays(365)), eq(PlatformGrowthBucket.WEEK), eq(PlatformFunnelPath.ALL));
  }

  /** {@code window=all} means no lower bound, ever — never "since epoch" as a date. */
  @Test
  void windowAll_hasNoLowerBound_andNoFromOnTheResponse() {
    PlatformGrowthService.Growth growth = service.growth("all", "week", "all", AS_OF);
    verify(repo).eventCounts(isNull(), eq(PlatformGrowthBucket.WEEK), eq(PlatformFunnelPath.ALL));
    assertNull(growth.from(), "an unbounded window has no start to name");
  }

  /** The funnel's 30d cohort is deliberately NOT a growth window — four week-points is noise. */
  @ParameterizedTest
  @ValueSource(strings = {"", "30d", "7d", "90days", "ALL", "year"})
  void unknownWindow_is400NamingTheThree(String raw) {
    ValidationException e =
        assertThrows(ValidationException.class, () -> service.growth(raw, "week", "all", AS_OF));
    assertTrue(e.getMessage().contains("90d"));
    assertTrue(e.getMessage().contains("365d"));
    assertTrue(e.getMessage().contains("all"));
  }

  @Test
  void missingWindow_is400() {
    assertThrows(ValidationException.class, () -> service.growth(null, "week", "all", AS_OF));
  }

  // ------------------------------------------------------------------ bucket parsing

  @Test
  void bucketMonth_reachesTheRepositoryAsMonth() {
    service.growth("90d", "month", "all", AS_OF);
    verify(repo).eventCounts(any(), eq(PlatformGrowthBucket.MONTH), eq(PlatformFunnelPath.ALL));
  }

  /** No {@code day} — tenant-lifecycle events are too sparse for day buckets to be signal. */
  @ParameterizedTest
  @ValueSource(strings = {"", "day", "WEEK", "weekly", "quarter"})
  void unknownBucket_is400NamingTheTwo(String raw) {
    ValidationException e =
        assertThrows(ValidationException.class, () -> service.growth("90d", raw, "all", AS_OF));
    assertTrue(e.getMessage().contains("week"));
    assertTrue(e.getMessage().contains("month"));
  }

  @Test
  void missingBucket_is400() {
    assertThrows(ValidationException.class, () -> service.growth("90d", null, "all", AS_OF));
  }

  // ------------------------------------------------------------------ path parsing (shared table)

  /** {@code ?path=} is {@link PlatformFunnelQuery#parsePath} — one definition, two callers. */
  @Test
  void pathSelfServe_reachesTheRepositoryAsSelfServe() {
    service.growth("90d", "week", "self_serve", AS_OF);
    verify(repo).eventCounts(any(), any(), eq(PlatformFunnelPath.SELF_SERVE));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "SELF_SERVE", "organic", "both"})
  void unknownPath_is400NamingTheThree(String raw) {
    ValidationException e =
        assertThrows(ValidationException.class, () -> service.growth("90d", "week", raw, AS_OF));
    assertTrue(e.getMessage().contains("all"));
    assertTrue(e.getMessage().contains("self_serve"));
    assertTrue(e.getMessage().contains("provisioned"));
  }

  @Test
  void missingPath_is400() {
    assertThrows(ValidationException.class, () -> service.growth("90d", "week", null, AS_OF));
  }

  // ------------------------------------------------------------------ current_period arithmetic

  /** A Tuesday-noon {@code asOf} opens the bucket at Monday 00:00 UTC — Postgres week semantics. */
  @Test
  void currentPeriod_weekIsTheUtcMondayStart() {
    assertEquals(
        OffsetDateTime.of(2026, 7, 27, 0, 0, 0, 0, ZoneOffset.UTC),
        service.growth("90d", "week", "all", AS_OF).currentPeriod());
  }

  @Test
  void currentPeriod_monthIsTheUtcFirstOfMonth() {
    assertEquals(
        OffsetDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC),
        service.growth("90d", "month", "all", AS_OF).currentPeriod());
  }

  /** Exactly at the week edge (Monday 00:00 UTC) the open bucket is that very instant. */
  @Test
  void currentPeriod_atTheWeekEdgeIsTheEdgeItself() {
    OffsetDateTime mondayMidnight = OffsetDateTime.of(2026, 7, 27, 0, 0, 0, 0, ZoneOffset.UTC);
    assertEquals(
        mondayMidnight, service.growth("90d", "week", "all", mondayMidnight).currentPeriod());
  }

  /**
   * A non-UTC {@code asOf} truncates in UTC, not in its own offset: Sunday 23:00 UTC rendered as
   * Monday 01:00 at +02:00 is still the <em>previous</em> UTC week's bucket.
   */
  @Test
  void currentPeriod_truncatesInUtcNotTheCallersOffset() {
    OffsetDateTime mondayCairoButSundayUtc =
        OffsetDateTime.of(2026, 7, 27, 1, 0, 0, 0, ZoneOffset.ofHours(2));
    assertEquals(
        OffsetDateTime.of(2026, 7, 20, 0, 0, 0, 0, ZoneOffset.UTC),
        service.growth("90d", "week", "all", mondayCairoButSundayUtc).currentPeriod());
  }

  // ------------------------------------------------------------------ series assembly

  /**
   * All six stages, always, in {@link PlatformFunnelStage} order — an untouched stage is {@code
   * points: []}, never omitted; an unknown milestone from the wire is dropped, not rendered.
   */
  @Test
  void allSixStagesInServerOrder_emptyIncluded_unknownDropped() {
    OffsetDateTime w1 = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    OffsetDateTime w2 = OffsetDateTime.of(2026, 6, 8, 0, 0, 0, 0, ZoneOffset.UTC);
    when(repo.eventCounts(any(), any(), any()))
        .thenReturn(
            List.of(
                new EventPoint("FIRST_ORDER", w1, 2),
                new EventPoint("FIRST_ORDER", w2, 5),
                new EventPoint("REGISTERED", w1, 4),
                new EventPoint("FIRST_EXPORT_V9", w1, 3)));

    List<PlatformGrowthService.StageSeries> series =
        service.growth("90d", "week", "all", AS_OF).series();

    assertEquals(
        List.of(
            PlatformFunnelStage.REGISTERED,
            PlatformFunnelStage.ACTIVATED,
            PlatformFunnelStage.CATALOGUED,
            PlatformFunnelStage.PUBLISHED,
            PlatformFunnelStage.FIRST_ORDER,
            PlatformFunnelStage.FIRST_PAYMENT),
        series.stream().map(PlatformGrowthService.StageSeries::stage).toList(),
        "server stage order, all six, no seventh for the unknown milestone");
    assertEquals(
        List.of(new PlatformGrowthService.Point(w1, 2), new PlatformGrowthService.Point(w2, 5)),
        pointsOf(series, PlatformFunnelStage.FIRST_ORDER),
        "points keep the repository's period-ASC order");
    assertEquals(
        List.of(new PlatformGrowthService.Point(w1, 4)),
        pointsOf(series, PlatformFunnelStage.REGISTERED));
    assertEquals(
        List.of(),
        pointsOf(series, PlatformFunnelStage.FIRST_PAYMENT),
        "an untouched stage is an empty list, never omitted");
  }

  /** No zero-count point is fabricated — sparsity is the repository's contract, kept verbatim. */
  @Test
  void sparseSeriesAreNotZeroFilledHere() {
    OffsetDateTime w1 = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    OffsetDateTime w3 = OffsetDateTime.of(2026, 6, 15, 0, 0, 0, 0, ZoneOffset.UTC);
    when(repo.eventCounts(any(), any(), any()))
        .thenReturn(
            List.of(new EventPoint("REGISTERED", w1, 1), new EventPoint("REGISTERED", w3, 2)));

    List<PlatformGrowthService.Point> points =
        pointsOf(
            service.growth("90d", "week", "all", AS_OF).series(), PlatformFunnelStage.REGISTERED);

    assertEquals(2, points.size(), "the interior gap week must not gain a fabricated zero point");
  }

  // ------------------------------------------------------------------ envelope echo, counts only

  @Test
  void envelopeEchoesWindowBucketPathAndAsOf() {
    PlatformGrowthService.Growth growth = service.growth("365d", "month", "provisioned", AS_OF);
    assertEquals(AS_OF, growth.asOf());
    assertEquals("365d", growth.window());
    assertEquals(PlatformGrowthBucket.MONTH, growth.bucket());
    assertEquals(PlatformFunnelPath.PROVISIONED, growth.path());
    assertEquals(AS_OF.minusDays(365), growth.from());
  }

  /**
   * Counts only — {@link PlatformGrowthService.Point} carries a period and a count and nothing
   * else; no rate, delta, or percent-growth field exists on any record here. The record shapes are
   * the assertion (a rate field would fail this file to compile against, not just at runtime).
   */
  @Test
  void noRateOrDeltaIsComputedAnywhere() {
    PlatformGrowthService.Growth growth = service.growth("90d", "week", "self_serve", AS_OF);
    assertEquals(6, growth.series().size());
    // A reviewer can grep this file for the absence of "rate"/"delta"/"growthPct" accessors.
  }

  private List<PlatformGrowthService.Point> pointsOf(
      List<PlatformGrowthService.StageSeries> series, PlatformFunnelStage stage) {
    return series.stream().filter(s -> s.stage() == stage).findFirst().orElseThrow().points();
  }
}
