package com.loai.inventory.domain.model;

import java.math.BigDecimal;

/**
 * What one {@link OrderListFilter} adds up to, computed in the same query family as its rows
 * ({@code stories/order_filters.md}): the row count the pager needs, and the two money figures a
 * filtered ledger owes its reader — {@code outstanding} (Σ {@code grand_total − prepaid_amount}
 * over the live rows that still owe: PENDING_PAYMENT, PAID, FULFILLING, FULFILLED, CLOSED with a
 * positive balance) and {@code value} (Σ {@code grand_total} over the same live statuses; a DRAFT,
 * CANCELLED or EXPIRED order's amount is not in force, so it never counts). Both are {@code 0.00}
 * for an empty set, never null.
 */
public record OrderListStats(long total, BigDecimal outstanding, BigDecimal value) {

  public static OrderListStats empty() {
    return new OrderListStats(0, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
  }
}
