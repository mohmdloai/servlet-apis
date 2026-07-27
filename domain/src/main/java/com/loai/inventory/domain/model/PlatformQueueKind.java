package com.loai.inventory.domain.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The five cross-org operational backlogs an operator can drill into ({@code GET
 * /api/admin/queues/{kind}}, {@code stories/platform_queues.md}). One constant per tile on the
 * overview's {@link PlatformQueueCounts}, and the enum is what binds the two: the count and the
 * list's {@code total} are the same number because both sides resolve this kind to one predicate.
 *
 * <p>Two of the five — {@link #FAILED_EMAILS} and {@link #EXPIRED_PENDING_ORDERS} — have
 * <em>no</em> org-scoped list anywhere in this product. A failed email delivery is invisible on
 * every other surface, and the org order worklist filters on {@code status}, not on {@code
 * expires_at &lt; now()}. For those two the platform queue is not a convenience view; it is the
 * only view.
 */
public enum PlatformQueueKind {
  FAILED_EMAILS("failed-emails"),
  PENDING_REFUNDS("pending-refunds"),
  OPEN_DISPUTES("open-disputes"),
  ORPHAN_TRANSACTIONS("orphan-transactions"),
  EXPIRED_PENDING_ORDERS("expired-pending-orders");

  private final String wire;

  PlatformQueueKind(String wire) {
    this.wire = wire;
  }

  /** The kebab-case path segment ({@code failed-emails}, …). */
  public String wire() {
    return wire;
  }

  /**
   * Parse the {@code {kind}} path segment. Unknown (or absent) throws — the caller maps it to a 400
   * naming all five, not a 404: {@code kind} is an enum value that happens to sit in the path, so
   * it follows the {@code ?status=} convention rather than the unknown-resource one.
   */
  public static PlatformQueueKind fromWire(String raw) {
    if (raw != null) {
      String normalized = raw.trim().toLowerCase(Locale.ROOT);
      for (PlatformQueueKind kind : values()) {
        if (kind.wire.equals(normalized)) {
          return kind;
        }
      }
    }
    throw new IllegalArgumentException("Unknown queue: " + raw);
  }

  /** {@code failed-emails, pending-refunds, …} — for the 400 that names the five. */
  public static String allWireValues() {
    return Arrays.stream(values()).map(PlatformQueueKind::wire).collect(Collectors.joining(", "));
  }
}
