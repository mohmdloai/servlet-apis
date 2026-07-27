package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * The lifecycle state of an {@link Org} — <b>the one definition of the rule</b> (see {@code
 * stories/platform_tenant_states.md}). Both the SQL filters ({@code OrgRepository.findAll/count},
 * {@code PlatformStatsRepository.tenantCounts}) and the admin DTOs consume <em>these</em>
 * constants; nothing restates the derivation. Two statements of it is how the platform console's
 * suspended tile and its drill-down list drifted in the first place.
 *
 * <table>
 *   <caption>The partition</caption>
 *   <tr><th>status</th><th>predicate</th></tr>
 *   <tr><td>{@link #ACTIVE}</td><td>{@code active = true}</td></tr>
 *   <tr><td>{@link #PENDING}</td><td>{@code active = false AND suspended_at IS NULL}</td></tr>
 *   <tr><td>{@link #SUSPENDED}</td><td>{@code active = false AND suspended_at IS NOT NULL}</td></tr>
 * </table>
 *
 * <p>These <b>partition</b> the {@code org} table: every row is in exactly one, there is no fourth
 * case, and {@code setSuspension} clears {@code suspended_at} on reactivate so a reactivated org
 * returns cleanly to {@link #ACTIVE}.
 *
 * <p>The distinction is not cosmetic. {@link #PENDING} is an org born inactive at self-serve
 * registration whose owner has not clicked the verification link yet (story 88/89) — the most
 * ordinary thing that happens on a signup form. {@link #SUSPENDED} is an operational alarm an ADMIN
 * raised. Reporting the first as the second turns the console's red tile into noise, and on a
 * platform with open registration it trends that way with traffic.
 */
public enum OrgStatus {
  /** Live. */
  ACTIVE(true, null),
  /** Born inactive at registration, never admin-suspended — awaiting email verification. */
  PENDING(false, Boolean.TRUE),
  /**
   * Deactivated by a platform ADMIN; {@code suspended_at} + {@code suspended_reason} are stamped.
   */
  SUSPENDED(false, Boolean.FALSE);

  private final boolean active;
  private final Boolean suspendedAtIsNull;

  OrgStatus(boolean active, Boolean suspendedAtIsNull) {
    this.active = active;
    this.suspendedAtIsNull = suspendedAtIsNull;
  }

  /** The {@code org.active} value this status requires. */
  public boolean activeFlag() {
    return active;
  }

  /**
   * Whether this status additionally requires {@code suspended_at} to be null ({@code TRUE}), to be
   * non-null ({@code FALSE}), or does not constrain it at all ({@code null}). A SQL filter builds
   * its predicate from this plus {@link #activeFlag()}; nothing else knows the rule.
   */
  public Boolean suspendedAtIsNull() {
    return suspendedAtIsNull;
  }

  /**
   * The derivation, stated once. Total by construction — the three constants partition the two
   * inputs, so there is no fourth case and no default to guess at.
   */
  public static OrgStatus of(boolean active, OffsetDateTime suspendedAt) {
    for (OrgStatus status : values()) {
      if (status.matches(active, suspendedAt)) {
        return status;
      }
    }
    // Unreachable: ACTIVE covers active=true, and PENDING/SUSPENDED partition active=false on the
    // nullness of suspended_at. Kept so a future constant cannot silently break the partition.
    throw new IllegalStateException(
        "org status is not derivable: active=" + active + " suspendedAt=" + suspendedAt);
  }

  /** The derivation applied to an org. */
  public static OrgStatus of(Org org) {
    return of(org.isActive(), org.getSuspendedAt());
  }

  private boolean matches(boolean activeValue, OffsetDateTime suspendedAt) {
    if (active != activeValue) {
      return false;
    }
    return suspendedAtIsNull == null || suspendedAtIsNull == (suspendedAt == null);
  }

  /** The lowercase wire form ({@code active} / {@code pending} / {@code suspended}). */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Parse the {@code ?status=} query value. {@code null}/blank means "no filter" and returns {@code
   * null}; an unknown value throws (the caller maps it to a 400 naming all three).
   */
  public static OrgStatus fromWire(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "active" -> ACTIVE;
      case "pending" -> PENDING;
      case "suspended" -> SUSPENDED;
      default -> throw new IllegalArgumentException("Unknown org status: " + raw);
    };
  }
}
