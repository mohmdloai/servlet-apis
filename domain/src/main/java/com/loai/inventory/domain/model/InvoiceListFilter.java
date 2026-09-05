package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The invoice worklist's read predicate ({@code stories/invoice_filters.md}) — every dimension the
 * {@code GET /invoices} list accepts, in one value so the rows, their total and the money summary
 * are computed from exactly the same WHERE.
 *
 * <ul>
 *   <li>{@code status} — the tab. Non-null makes the read a queue (oldest first); null the ledger.
 *   <li>{@code q} — free text: invoice number, order number, the frozen customer name (folded) or
 *       the digits of a phone. Trimmed by the caller; null or blank is absent.
 *   <li>{@code issuedFrom} / {@code issuedTo} — half-open {@code [from, to)} on {@code issued_at};
 *       either side may be open.
 *   <li>{@code paid} — the ISSUED money meter as a filter: nothing paid yet, or partly paid.
 *   <li>{@code minTotal} / {@code maxTotal} — inclusive bounds on {@code grand_total}.
 * </ul>
 */
public record InvoiceListFilter(
    InvoiceStatus status,
    String q,
    OffsetDateTime issuedFrom,
    OffsetDateTime issuedTo,
    PaidState paid,
    BigDecimal minTotal,
    BigDecimal maxTotal) {

  /** The ISSUED meter as a filter value: {@code paid_amount = 0} / {@code 0 < paid < total}. */
  public enum PaidState {
    NONE,
    PARTIAL
  }

  /** The pre-slice read: the tab alone. */
  public static InvoiceListFilter ofStatus(InvoiceStatus status) {
    return new InvoiceListFilter(status, null, null, null, null, null, null);
  }

  /** The unfiltered ledger. */
  public static InvoiceListFilter none() {
    return ofStatus(null);
  }

  /** A status filter makes the read a worklist (oldest first); none makes it the ledger. */
  public boolean isQueue() {
    return status != null;
  }

  public boolean hasQuery() {
    return q != null && !q.isBlank();
  }
}
