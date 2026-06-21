package com.loai.inventory.domain.model;

/**
 * Lifecycle of a {@link SalesInvoice}: DRAFT → ISSUED → PAID, with a VOID branch. See {@code
 * sys-analysis/outbound/invoicing.md}.
 */
public enum InvoiceStatus {
  DRAFT,
  ISSUED,
  PAID,
  VOID
}
