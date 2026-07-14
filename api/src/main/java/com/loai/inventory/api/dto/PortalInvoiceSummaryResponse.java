package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.SalesInvoice;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Lean customer-safe invoice row for the {@code GET /api/portal/invoices} list (slice P3, {@code
 * stories/portal_invoices.md}): the {@code id} the row links to, the number, issue date, status,
 * currency and money meter — enough to render a list row with a "Download PDF" action. Lines and
 * the customer snapshot live on the {@link PortalInvoiceResponse} detail. No internal id (customer
 * / order / fulfillment) is carried.
 */
public class PortalInvoiceSummaryResponse {
  private UUID id;
  private String invoiceNumber;
  private String status;
  private OffsetDateTime issuedAt;
  private String currency;
  private BigDecimal grandTotal;
  private BigDecimal paidAmount;
  private BigDecimal balance;

  private PortalInvoiceSummaryResponse() {}

  public static PortalInvoiceSummaryResponse from(SalesInvoice inv) {
    PortalInvoiceSummaryResponse r = new PortalInvoiceSummaryResponse();
    r.id = inv.getId();
    r.invoiceNumber = inv.getInvoiceNumber();
    r.status = inv.getStatus().name();
    r.issuedAt = inv.getIssuedAt();
    r.currency = inv.getCurrency();
    r.grandTotal = inv.getGrandTotal();
    r.paidAmount = inv.getPaidAmount();
    r.balance = nz(inv.getGrandTotal()).subtract(nz(inv.getPaidAmount()));
    return r;
  }

  private static BigDecimal nz(BigDecimal b) {
    return b == null ? BigDecimal.ZERO : b;
  }

  public UUID getId() {
    return id;
  }

  public String getInvoiceNumber() {
    return invoiceNumber;
  }

  public String getStatus() {
    return status;
  }

  public OffsetDateTime getIssuedAt() {
    return issuedAt;
  }

  public String getCurrency() {
    return currency;
  }

  public BigDecimal getGrandTotal() {
    return grandTotal;
  }

  public BigDecimal getPaidAmount() {
    return paidAmount;
  }

  public BigDecimal getBalance() {
    return balance;
  }
}
