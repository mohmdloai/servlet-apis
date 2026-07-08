package com.loai.inventory.api.dto;

import com.loai.inventory.service.InvoiceAdminService.InvoiceSummary;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Lean worklist row for {@code GET /invoices} ({@code stories/invoice_reads.md}) — enough to render
 * a card without a per-row fetch: the number, the money meter ({@code grand_total} / {@code
 * paid_amount} for the partial-paid hint), the frozen customer snapshot name, and the batch-loaded
 * {@code sales_order_number}. Lines are omitted — the {@code GET /invoices/{id}} detail ({@link
 * InvoiceResponse}) carries those. Null fields are omitted from the JSON, as everywhere.
 */
public class InvoiceSummaryResponse {

  private UUID id;
  private String invoiceNumber;
  private String status;
  private BigDecimal grandTotal;
  private BigDecimal paidAmount;
  private String currency;
  private String customerName;
  private UUID salesOrderId;
  private String salesOrderNumber;
  private UUID fulfillmentId;
  private OffsetDateTime issuedAt;
  private OffsetDateTime voidedAt;
  private OffsetDateTime createdAt;

  private InvoiceSummaryResponse() {}

  public static InvoiceSummaryResponse from(InvoiceSummary summary) {
    var inv = summary.invoice();
    InvoiceSummaryResponse r = new InvoiceSummaryResponse();
    r.id = inv.getId();
    r.invoiceNumber = inv.getInvoiceNumber();
    r.status = inv.getStatus().name();
    r.grandTotal = inv.getGrandTotal();
    r.paidAmount = inv.getPaidAmount();
    r.currency = inv.getCurrency();
    r.customerName = inv.getCustomerName();
    r.salesOrderId = inv.getSalesOrderId();
    r.salesOrderNumber = summary.salesOrderNumber();
    r.fulfillmentId = inv.getFulfillmentId();
    r.issuedAt = inv.getIssuedAt();
    r.voidedAt = inv.getVoidedAt();
    r.createdAt = inv.getCreatedAt();
    return r;
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

  public BigDecimal getGrandTotal() {
    return grandTotal;
  }

  public BigDecimal getPaidAmount() {
    return paidAmount;
  }

  public String getCurrency() {
    return currency;
  }

  public String getCustomerName() {
    return customerName;
  }

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public String getSalesOrderNumber() {
    return salesOrderNumber;
  }

  public UUID getFulfillmentId() {
    return fulfillmentId;
  }

  public OffsetDateTime getIssuedAt() {
    return issuedAt;
  }

  public OffsetDateTime getVoidedAt() {
    return voidedAt;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
