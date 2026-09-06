package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The order worklist's read predicate ({@code stories/order_filters.md}) — every dimension {@code
 * GET /sales-orders} accepts, in one value so the rows, their total and the money summary are
 * computed from exactly the same WHERE.
 *
 * <ul>
 *   <li>{@code status} — the tab. Non-null makes the read a queue (oldest first); null the ledger
 *       (newest first) — unless an explicit {@code sort} says otherwise.
 *   <li>{@code channel} — ONLINE / IN_STORE / PHONE ({@code stories/counter_return.md}).
 *   <li>{@code q} — free text: the order number, or the customer's name / phone, CRM row or walk-in
 *       contact ({@code stories/order_search.md}). Trimmed by the caller; null or blank is absent.
 *   <li>{@code createdFrom} / {@code createdTo} — half-open {@code [from, to)} on {@code
 *       created_at}; either side may be open. {@code created_at} is what the ledger sorts and the
 *       row shows; {@code placed_at} is stamped in the same transaction, so the two never disagree
 *       for a placed order, and only {@code created_at} is NOT NULL and inside the worklist index.
 *   <li>{@code balance} — the row's money meter as a filter: {@code grand_total − prepaid_amount}
 *       positive (owing), zero (settled) or negative (overpaid). Pure arithmetic, like the invoice
 *       {@code paid} filter — it composes with {@code status}, it never guesses at it.
 *   <li>{@code minTotal} / {@code maxTotal} — inclusive bounds on {@code grand_total}.
 *   <li>{@code sort} — an explicit order; null is the queue-vs-ledger default.
 * </ul>
 */
public record OrderListFilter(
    OrderStatus status,
    OrderChannel channel,
    String q,
    OffsetDateTime createdFrom,
    OffsetDateTime createdTo,
    Balance balance,
    BigDecimal minTotal,
    BigDecimal maxTotal,
    Sort sort) {

  /** The row's balance as a filter value, read off {@code grand_total − prepaid_amount}. */
  public enum Balance {
    /** Still owed: {@code prepaid_amount < grand_total}. */
    OWING,
    /** Paid to the piastre: {@code prepaid_amount = grand_total}. */
    SETTLED,
    /** Paid past the total: {@code prepaid_amount > grand_total}. */
    OVERPAID
  }

  /** The explicit orders a caller may ask for; each breaks ties on {@code (created_at, id)}. */
  public enum Sort {
    /** {@code created_at DESC} — the ledger order, whatever the tab. */
    NEWEST,
    /** {@code created_at ASC} — the queue order, whatever the tab. */
    OLDEST,
    /** {@code grand_total DESC} — the biggest orders first. */
    TOTAL,
    /** {@code grand_total − prepaid_amount DESC} — the most owed first. */
    BALANCE,
    /** {@code expires_at ASC NULLS LAST} — the payment holds about to lapse first. */
    EXPIRING
  }

  /** The pre-slice read: tab, channel and search alone. */
  public static OrderListFilter of(OrderStatus status, OrderChannel channel, String q) {
    return new OrderListFilter(status, channel, q, null, null, null, null, null, null);
  }

  /** The unfiltered ledger. */
  public static OrderListFilter none() {
    return of(null, null, null);
  }

  /** A status filter makes the read a worklist (oldest first); none makes it the ledger. */
  public boolean isQueue() {
    return status != null;
  }

  public boolean hasQuery() {
    return q != null && !q.isBlank();
  }
}
