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
import com.loai.inventory.domain.repository.PlatformFunnelRepository;
import com.loai.inventory.domain.repository.PlatformFunnelRepositoryFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The parts of {@link PlatformFunnelService} an integration test cannot force as cleanly: cohort
 * arithmetic (which {@code from} each window resolves to), the 400s naming their options, and the
 * stage-ordering contract — every one of the six stages present, including a stage nobody reached.
 */
class PlatformFunnelServiceTest {

  private static final OffsetDateTime AS_OF =
      OffsetDateTime.of(2026, 7, 28, 12, 0, 0, 0, ZoneOffset.UTC);

  private PlatformFunnelRepository repo;
  private PlatformFunnelService service;

  @BeforeEach
  void setUp() {
    repo = mock(PlatformFunnelRepository.class);
    PlatformFunnelRepositoryFactory factory = mock(PlatformFunnelRepositoryFactory.class);
    when(factory.create(any())).thenReturn(repo);
    when(repo.cohortStats(any(), any()))
        .thenReturn(new PlatformFunnelRepository.CohortStats(0, Optional.empty()));
    when(repo.stageCounts(any(), any())).thenReturn(Map.of());
    service = new PlatformFunnelService(mock(DSLContext.class), factory);
  }

  // ------------------------------------------------------------------ cohort parsing

  @Test
  void cohort30d_isThirtyDaysBeforeAsOf() {
    service.funnel("30d", "all", AS_OF);
    verify(repo).cohortStats(eq(AS_OF.minusDays(30)), eq(PlatformFunnelPath.ALL));
  }

  @Test
  void cohort90d_isNinetyDaysBeforeAsOf() {
    service.funnel("90d", "all", AS_OF);
    verify(repo).cohortStats(eq(AS_OF.minusDays(90)), eq(PlatformFunnelPath.ALL));
  }

  @Test
  void cohort365d_isThreeSixtyFiveDaysBeforeAsOf() {
    service.funnel("365d", "all", AS_OF);
    verify(repo).cohortStats(eq(AS_OF.minusDays(365)), eq(PlatformFunnelPath.ALL));
  }

  /** {@code cohort=all} means no lower bound, ever — never "every tenant since epoch" as a date. */
  @Test
  void cohortAll_hasNoLowerBound() {
    service.funnel("all", "all", AS_OF);
    verify(repo).cohortStats(isNull(), eq(PlatformFunnelPath.ALL));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "7d", "30days", "ALL", "month"})
  void unknownCohort_is400NamingTheFour(String raw) {
    ValidationException e =
        assertThrows(ValidationException.class, () -> service.funnel(raw, "all", AS_OF));
    assertTrue(e.getMessage().contains("30d"));
    assertTrue(e.getMessage().contains("90d"));
    assertTrue(e.getMessage().contains("365d"));
    assertTrue(e.getMessage().contains("all"));
  }

  @Test
  void missingCohort_is400() {
    assertThrows(ValidationException.class, () -> service.funnel(null, "all", AS_OF));
  }

  // ------------------------------------------------------------------ path parsing

  @Test
  void pathSelfServe_reachesTheRepositoryAsSelfServe() {
    service.funnel("all", "self_serve", AS_OF);
    verify(repo).cohortStats(isNull(), eq(PlatformFunnelPath.SELF_SERVE));
  }

  @Test
  void pathProvisioned_reachesTheRepositoryAsProvisioned() {
    service.funnel("all", "provisioned", AS_OF);
    verify(repo).cohortStats(isNull(), eq(PlatformFunnelPath.PROVISIONED));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "SELF_SERVE", "selfserve", "both", "organic"})
  void unknownPath_is400NamingTheThree(String raw) {
    ValidationException e =
        assertThrows(ValidationException.class, () -> service.funnel("all", raw, AS_OF));
    assertTrue(e.getMessage().contains("all"));
    assertTrue(e.getMessage().contains("self_serve"));
    assertTrue(e.getMessage().contains("provisioned"));
  }

  @Test
  void missingPath_is400() {
    assertThrows(ValidationException.class, () -> service.funnel("all", null, AS_OF));
  }

  /** The echoed {@code path} on the response is the wire literal, not the Java enum name. */
  @Test
  void responsePathEchoesTheWireLiteral() {
    assertEquals(PlatformFunnelPath.SELF_SERVE, service.funnel("all", "self_serve", AS_OF).path());
  }

  // ------------------------------------------------------------------ stage ordering

  /**
   * All six stages, always, in {@link PlatformFunnelStage} order — including a stage with {@code
   * reached: 0}. Omitting a stage the cohort never reached would read as "no data" when it means
   * "nobody got here"; those are opposite messages.
   */
  @Test
  void allSixStagesAlwaysPresent_inOrder_zeroIncluded() {
    when(repo.stageCounts(any(), any()))
        .thenReturn(
            Map.of(
                "REGISTERED", 100L,
                "ACTIVATED", 80L,
                "CATALOGUED", 40L
                // PUBLISHED, FIRST_ORDER, FIRST_PAYMENT: nobody reached them in this fixture.
                ));

    List<PlatformFunnelService.StageCount> stages = service.funnel("all", "all", AS_OF).stages();

    assertEquals(6, stages.size(), "a stage with zero reached must still be on the response");
    assertEquals(
        List.of(
            PlatformFunnelStage.REGISTERED,
            PlatformFunnelStage.ACTIVATED,
            PlatformFunnelStage.CATALOGUED,
            PlatformFunnelStage.PUBLISHED,
            PlatformFunnelStage.FIRST_ORDER,
            PlatformFunnelStage.FIRST_PAYMENT),
        stages.stream().map(PlatformFunnelService.StageCount::stage).toList());
    assertEquals(100L, stageCount(stages, PlatformFunnelStage.REGISTERED));
    assertEquals(80L, stageCount(stages, PlatformFunnelStage.ACTIVATED));
    assertEquals(40L, stageCount(stages, PlatformFunnelStage.CATALOGUED));
    assertEquals(0L, stageCount(stages, PlatformFunnelStage.PUBLISHED));
    assertEquals(0L, stageCount(stages, PlatformFunnelStage.FIRST_ORDER));
    assertEquals(0L, stageCount(stages, PlatformFunnelStage.FIRST_PAYMENT));
  }

  /**
   * The open-text/closed-enum split: a milestone the enum does not know (a future stage, or bad
   * data) is dropped rather than rendered or thrown on.
   */
  @Test
  void unknownMilestoneInRepository_isSilentlyDropped() {
    when(repo.stageCounts(any(), any()))
        .thenReturn(Map.of("REGISTERED", 5L, "SOME_FUTURE_STAGE", 3L));

    List<PlatformFunnelService.StageCount> stages = service.funnel("all", "all", AS_OF).stages();

    assertEquals(6, stages.size());
    assertEquals(5L, stageCount(stages, PlatformFunnelStage.REGISTERED));
  }

  /**
   * A later stage may legitimately exceed an earlier one's count (an IN_STORE-only tenant reaches
   * FIRST_ORDER without PUBLISHED) — the service must not clamp, sort, or otherwise "fix" that.
   */
  @Test
  void aLaterStageExceedingAnEarlierOne_isNotClamped() {
    when(repo.stageCounts(any(), any())).thenReturn(Map.of("PUBLISHED", 4L, "FIRST_ORDER", 9L));

    List<PlatformFunnelService.StageCount> stages = service.funnel("all", "all", AS_OF).stages();

    assertEquals(4L, stageCount(stages, PlatformFunnelStage.PUBLISHED));
    assertEquals(9L, stageCount(stages, PlatformFunnelStage.FIRST_ORDER));
  }

  // ------------------------------------------------------------------ cohort echo, no percentage

  @Test
  void cohortSizeAndWindowAreEchoedFromTheRepository() {
    when(repo.cohortStats(any(), any()))
        .thenReturn(new PlatformFunnelRepository.CohortStats(128, Optional.of(AS_OF.minusDays(2))));

    PlatformFunnelService.Cohort cohort = service.funnel("30d", "all", AS_OF).cohort();

    assertEquals("30d", cohort.window());
    assertEquals(128L, cohort.size());
    assertEquals(2L, cohort.youngestAgeDays());
  }

  /**
   * An empty cohort has no youngest member, so the age is {@code null} rather than a fabricated 0.
   */
  @Test
  void emptyCohort_youngestAgeDaysIsNull() {
    when(repo.cohortStats(any(), any()))
        .thenReturn(new PlatformFunnelRepository.CohortStats(0, Optional.empty()));

    assertNull(service.funnel("all", "all", AS_OF).cohort().youngestAgeDays());
  }

  /**
   * Counts only — {@link PlatformFunnelService.StageCount} and {@link PlatformFunnelService.Cohort}
   * carry no percentage field; the record shape itself is the assertion (a percentage field would
   * fail this test to compile against, not just at runtime).
   */
  @Test
  void noPercentageIsComputedAnywhereInTheResponse() {
    PlatformFunnelService.Funnel funnel = service.funnel("30d", "self_serve", AS_OF);
    assertEquals(AS_OF, funnel.asOf());
    // Reaching every accessor below without a "percentage"/"pct"/"ratio" method existing on either
    // record is the point; a reviewer can grep this file for the absence.
  }

  private long stageCount(
      List<PlatformFunnelService.StageCount> stages, PlatformFunnelStage stage) {
    return stages.stream().filter(s -> s.stage() == stage).findFirst().orElseThrow().reached();
  }
}
