package com.loai.inventory.domain.model;

import java.util.Locale;

/**
 * The five things {@code GET /api/admin/search?q=} can find (slice 3 of the platform-console epic,
 * {@code stories/platform_search.md}). One constant per group in the response, and the declaration
 * order below <em>is</em> the order groups appear on the wire.
 *
 * <p><strong>What is deliberately not here, and why:</strong>
 *
 * <ul>
 *   <li><strong>Customer names.</strong> {@code customer} is matched on {@code email} only. The
 *       fuzzy-name probe measured <strong>1706 ms</strong> at two characters over {@code perfdb}'s
 *       200,000 customers — pg_trgm extracts no trigram below three characters, so the planner
 *       chooses the GIN index and then rechecks the whole table through it. See {@code
 *       PlatformSearchType}'s callers and the handler Javadoc for the full four reasons.
 *   <li><strong>{@code sales_invoice} / {@code credit_note} numbers.</strong> Each costs another
 *       cross-org index, and both are two clicks from the order that owns them.
 *   <li><strong>Products and listings.</strong> Merchant catalog, not operator triage.
 * </ul>
 *
 * <p>Two of the five have no owning tenant — an {@link #ORG} <em>is</em> the tenant, and an {@link
 * #APP_USER} is a platform-plane identity that may hold roles in several. Everything else carries
 * its {@link PlatformSearchOrg}; see {@link PlatformSearchResult#org()}.
 */
public enum PlatformSearchType {
  /** Exact on {@code slug}, fuzzy on {@code name} — 200 rows, so the scan is honest and cheap. */
  ORG("org"),
  /** Exact on {@code app_user.email}, which is the one globally-unique email in the schema. */
  APP_USER("app_user"),
  /**
   * Exact on {@code customer.email}. Unique <em>per org</em>, so this legitimately returns many.
   */
  CUSTOMER("customer"),
  /** Exact on {@code order_number}. Unique <em>per org</em> — the whole point of the slice. */
  SALES_ORDER("sales_order"),
  /** Exact on {@code provider_ref}, the one identifier here that is globally unique. */
  PAYMENT_TRANSACTION("payment_transaction");

  private final String wire;

  PlatformSearchType(String wire) {
    this.wire = wire;
  }

  /** The snake_case value carried in a group's {@code type} field. */
  public String wire() {
    return wire;
  }

  /**
   * Whether results of this type carry an owning tenant. False for {@link #ORG}/{@link #APP_USER}.
   */
  public boolean hasOwningOrg() {
    return this != ORG && this != APP_USER;
  }

  /** Defensive parse, used only by tests and tooling; the wire is write-only for the API. */
  public static PlatformSearchType fromWire(String raw) {
    if (raw != null) {
      String normalized = raw.trim().toLowerCase(Locale.ROOT);
      for (PlatformSearchType type : values()) {
        if (type.wire.equals(normalized)) {
          return type;
        }
      }
    }
    throw new IllegalArgumentException("Unknown search type: " + raw);
  }
}
