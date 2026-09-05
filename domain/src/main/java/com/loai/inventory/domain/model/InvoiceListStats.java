package com.loai.inventory.domain.model;

import java.math.BigDecimal;

/**
 * What one {@link InvoiceListFilter} adds up to, computed in the same query family as its rows
 * ({@code stories/invoice_filters.md}): the row count the pager needs, and the two money figures a
 * filtered ledger owes its reader — {@code outstanding} (Σ {@code grand_total − paid_amount} over
 * the ISSUED rows) and {@code issued} (Σ {@code grand_total} over ISSUED + PAID; a VOID document's
 * amount is no longer in force, so it never counts). Both are {@code 0.00} for an empty set, never
 * null.
 */
public record InvoiceListStats(long total, BigDecimal outstanding, BigDecimal issued) {

  public static InvoiceListStats empty() {
    return new InvoiceListStats(0, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
  }
}
