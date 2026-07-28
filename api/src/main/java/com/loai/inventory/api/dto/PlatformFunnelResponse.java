package com.loai.inventory.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.loai.inventory.service.platform.PlatformFunnelQuery;
import com.loai.inventory.service.platform.PlatformFunnelService.Cohort;
import com.loai.inventory.service.platform.PlatformFunnelService.Funnel;
import com.loai.inventory.service.platform.PlatformFunnelService.StageCount;
import java.time.Instant;
import java.util.List;

/**
 * Wire shape of {@code GET /api/admin/funnel} (slice 7, {@code stories/platform_tenant_funnel.md}).
 * Jackson is SNAKE_CASE with nulls omitted (CLAUDE.md), which carries two of the story's honesty
 * rules onto the wire for free:
 *
 * <ul>
 *   <li>{@code cohort.youngest_age_days} is {@code null} (hence absent) only for an empty cohort —
 *       present as {@code 0} for a cohort registered today, so the client can render the
 *       young-cohort caveat exactly when it applies (decision 2).
 *   <li>Every stage carries {@code reached} even at {@code 0} — the DTO has no way to omit one, on
 *       purpose. A missing stage would read as "no data"; a stage present with {@code reached: 0}
 *       reads as "nobody got here", which is the true and different statement.
 * </ul>
 *
 * <p>No percentage field exists here, ever — decision 1 puts that arithmetic on the client, which
 * is the only layer that knows how much room it has to say "3 of 7" instead of "43%".
 */
public record PlatformFunnelResponse(
    Instant asOf, CohortBlock cohort, String path, List<StageBlock> stages) {

  public record CohortBlock(
      String window,
      Instant from,
      long size,
      @JsonProperty("youngest_age_days") Long youngestAgeDays) {}

  public record StageBlock(String stage, long reached) {}

  public static PlatformFunnelResponse from(Funnel funnel) {
    return new PlatformFunnelResponse(
        funnel.asOf().toInstant(),
        cohort(funnel.cohort()),
        PlatformFunnelQuery.wirePath(funnel.path()),
        funnel.stages().stream().map(PlatformFunnelResponse::stage).toList());
  }

  private static CohortBlock cohort(Cohort c) {
    return new CohortBlock(
        c.window(), c.from() == null ? null : c.from().toInstant(), c.size(), c.youngestAgeDays());
  }

  private static StageBlock stage(StageCount s) {
    return new StageBlock(s.stage().name(), s.reached());
  }
}
