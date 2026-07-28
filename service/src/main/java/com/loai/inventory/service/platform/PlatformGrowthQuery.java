package com.loai.inventory.service.platform;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PlatformGrowthBucket;
import java.time.OffsetDateTime;

/**
 * Parses and validates {@code ?window=} and {@code ?bucket=} for {@code GET /api/admin/growth}
 * (slice 8, {@code stories/platform_growth_series.md}) — {@link PlatformFunnelQuery}'s shape,
 * sitting beside it rather than widening it: {@code window} differs from the funnel's {@code
 * cohort} in accepted values (no {@code 30d}; a 30-day series in week buckets is four points and in
 * month buckets is noise), so it is its own table, while {@code ?path=} is shared literally via
 * {@link PlatformFunnelQuery#parsePath} — one definition of that vocabulary, two callers.
 *
 * <p><strong>All parameters are required</strong>, the slice-7 convention for enum-shaped
 * parameters: an unknown value and a missing one collapse to the same 400 naming the options.
 *
 * <p><strong>No window cap.</strong> The reports' 366-day cap defends 183 MB commerce tables; this
 * table is ~72 kB at full perfdb scale, so copying that limit here would be a limit defending
 * nothing (the slice-6 lesson). {@code all} over week buckets is bounded by the platform's own
 * lifetime — and unlike the funnel, {@code all} is not a lying default for a <em>series</em>,
 * because a time axis shows age instead of hiding it.
 */
public final class PlatformGrowthQuery {

  private PlatformGrowthQuery() {}

  /** The three accepted windows. {@link #from} turns one into the {@code reached_at} bound. */
  public enum Window {
    D90("90d", 90),
    D365("365d", 365),
    ALL("all", -1);

    private final String wire;
    private final int days;

    Window(String wire, int days) {
      this.wire = wire;
      this.days = days;
    }

    public String wire() {
      return wire;
    }

    /**
     * The lower bound on {@code org_milestone.reached_at} for {@code asOf} — <strong>not</strong>
     * on {@code org.created_at}; this is the event surface — or {@code null} for {@link #ALL}.
     */
    public OffsetDateTime from(OffsetDateTime asOf) {
      return this == ALL ? null : asOf.minusDays(days);
    }
  }

  public static Window parseWindow(String raw) {
    for (Window w : Window.values()) {
      if (w.wire().equals(raw)) {
        return w;
      }
    }
    throw new ValidationException("Parameter 'window' must be one of: 90d, 365d, all");
  }

  public static PlatformGrowthBucket parseBucket(String raw) {
    if (raw == null) {
      throw new ValidationException("Parameter 'bucket' must be one of: week, month");
    }
    return switch (raw) {
      case "week" -> PlatformGrowthBucket.WEEK;
      case "month" -> PlatformGrowthBucket.MONTH;
      default -> throw new ValidationException("Parameter 'bucket' must be one of: week, month");
    };
  }

  /** The wire literal for a bucket, the inverse of {@link #parseBucket}. */
  public static String wireBucket(PlatformGrowthBucket bucket) {
    return switch (bucket) {
      case WEEK -> "week";
      case MONTH -> "month";
    };
  }
}
