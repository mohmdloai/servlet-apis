package com.loai.inventory.service.platform;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PlatformFunnelPath;
import java.time.OffsetDateTime;

/**
 * Parses and validates {@code ?cohort=} and {@code ?path=} for {@code GET /api/admin/funnel} (slice
 * 7, {@code stories/platform_tenant_funnel.md}) — the {@code PlatformSearchQuery} precedent: both
 * rules stated once, as data, so a reviewer reads the accepted values here rather than
 * reconstructing them from scattered {@code if}s.
 *
 * <p><strong>Both parameters are required.</strong> The story's URL grammar ( {@code
 * ?cohort=30d|90d|365d|all&path=all|self_serve|provisioned}) names no default for either, and the
 * frontend always sends both explicitly (URL-driven state, story 81). An unknown value and a
 * missing one collapse to the same 400 naming the options — the {@code /admin/queues} convention
 * for an enum-shaped parameter, not the "absent = unfiltered" convention {@code ?status=} uses
 * elsewhere, because a funnel with no cohort silently defaulting to "every tenant ever" is exactly
 * the lie the story's decision 4 forbids ("never 'all tenants ever' as the default").
 */
public final class PlatformFunnelQuery {

  private PlatformFunnelQuery() {}

  /** The four accepted cohort windows. {@link #from} turns one into the lower bound on the wire. */
  public enum Cohort {
    D30("30d", 30),
    D90("90d", 90),
    D365("365d", 365),
    ALL("all", -1);

    private final String wire;
    private final int days;

    Cohort(String wire, int days) {
      this.wire = wire;
      this.days = days;
    }

    public String wire() {
      return wire;
    }

    /**
     * The lower bound on {@code org.created_at} for {@code asOf}, or {@code null} for {@link #ALL}.
     */
    public OffsetDateTime from(OffsetDateTime asOf) {
      return this == ALL ? null : asOf.minusDays(days);
    }
  }

  public static Cohort parseCohort(String raw) {
    for (Cohort c : Cohort.values()) {
      if (c.wire().equals(raw)) {
        return c;
      }
    }
    throw new ValidationException("Parameter 'cohort' must be one of: 30d, 90d, 365d, all");
  }

  public static PlatformFunnelPath parsePath(String raw) {
    if (raw == null) {
      throw new ValidationException(
          "Parameter 'path' must be one of: all, self_serve, provisioned");
    }
    return switch (raw) {
      case "all" -> PlatformFunnelPath.ALL;
      case "self_serve" -> PlatformFunnelPath.SELF_SERVE;
      case "provisioned" -> PlatformFunnelPath.PROVISIONED;
      default ->
          throw new ValidationException(
              "Parameter 'path' must be one of: all, self_serve, provisioned");
    };
  }

  /** The wire literal for a path, the inverse of {@link #parsePath}. */
  public static String wirePath(PlatformFunnelPath path) {
    return switch (path) {
      case ALL -> "all";
      case SELF_SERVE -> "self_serve";
      case PROVISIONED -> "provisioned";
    };
  }
}
