package com.loai.inventory.api.dto;

import com.loai.inventory.service.platform.PlatformFunnelQuery;
import com.loai.inventory.service.platform.PlatformGrowthQuery;
import com.loai.inventory.service.platform.PlatformGrowthService.Growth;
import com.loai.inventory.service.platform.PlatformGrowthService.Point;
import com.loai.inventory.service.platform.PlatformGrowthService.StageSeries;
import java.time.Instant;
import java.util.List;

/**
 * Wire shape of {@code GET /api/admin/growth} (slice 8, {@code stories/platform_growth_series.md}).
 * Jackson is SNAKE_CASE with nulls omitted (CLAUDE.md), which carries the envelope rules for free:
 *
 * <ul>
 *   <li>{@code from} is {@code null} (hence absent) exactly for {@code window=all} — an unbounded
 *       window has no start to name.
 *   <li>{@code current_period} is always present: the still-open bucket is named once, on the
 *       envelope, and never flagged per-point — one fact, one place.
 *   <li>Every stage carries {@code points} even as {@code []} — the DTO has no way to omit a stage,
 *       on purpose ("nobody arrived" and "no data" are opposite messages). Within a stage the
 *       points are sparse, and here absence <em>means zero events</em> (the backend story's stated
 *       deviation from the reports' sparse contract), so the client zero-fills with confidence.
 * </ul>
 *
 * <p>No rate, delta, or percent-growth field exists here, ever — counts only, on every layer.
 */
public record PlatformGrowthResponse(
    Instant asOf,
    String window,
    String bucket,
    String path,
    Instant from,
    Instant currentPeriod,
    List<SeriesBlock> series) {

  public record SeriesBlock(String stage, List<PointBlock> points) {}

  public record PointBlock(Instant period, long count) {}

  public static PlatformGrowthResponse from(Growth growth) {
    return new PlatformGrowthResponse(
        growth.asOf().toInstant(),
        growth.window(),
        PlatformGrowthQuery.wireBucket(growth.bucket()),
        PlatformFunnelQuery.wirePath(growth.path()),
        growth.from() == null ? null : growth.from().toInstant(),
        growth.currentPeriod().toInstant(),
        growth.series().stream().map(PlatformGrowthResponse::series).toList());
  }

  private static SeriesBlock series(StageSeries s) {
    return new SeriesBlock(
        s.stage().name(), s.points().stream().map(PlatformGrowthResponse::point).toList());
  }

  private static PointBlock point(Point p) {
    return new PointBlock(p.period().toInstant(), p.count());
  }
}
