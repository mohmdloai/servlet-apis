package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.service.RefundService.RefundView;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response shape for a refund. Worklist rows ({@code GET /refunds}) additionally carry the source
 * context — {@code sales_order_id}/{@code sales_order_number} for a payment-backed refund (absent
 * for an orphan payment), {@code sales_invoice_id}/{@code credit_note_number} for a
 * CreditNote-backed one — so a queue card can say what the money is for without extra requests.
 * Mutation responses omit those fields (null → omitted, as everywhere).
 */
public class RefundResponse {
  private UUID id;
  private UUID creditNoteId;
  private UUID paymentId;
  private UUID customerId;
  private BigDecimal amount;
  private String currency;
  private String status;
  private String method;
  private UUID paymentTransactionId;
  private OffsetDateTime createdAt;
  private OffsetDateTime executedAt;
  private OffsetDateTime cancelledAt;
  private String cancelledReason;
  private String notes;
  private UUID salesOrderId;
  private String salesOrderNumber;
  private UUID salesInvoiceId;
  private String creditNoteNumber;

  private RefundResponse() {}

  public static RefundResponse from(Refund r) {
    RefundResponse out = new RefundResponse();
    out.id = r.getId();
    out.creditNoteId = r.getCreditNoteId();
    out.paymentId = r.getPaymentId();
    out.customerId = r.getCustomerId();
    out.amount = r.getAmount();
    out.currency = r.getCurrency();
    out.status = r.getStatus().name();
    out.method = r.getMethod().name();
    out.paymentTransactionId = r.getPaymentTransactionId();
    out.createdAt = r.getCreatedAt();
    out.executedAt = r.getExecutedAt();
    out.cancelledAt = r.getCancelledAt();
    out.cancelledReason = r.getCancelledReason();
    out.notes = r.getNotes();
    return out;
  }

  /** A worklist row: the refund decorated with its batch-loaded source context. */
  public static RefundResponse from(RefundView view) {
    RefundResponse out = from(view.refund());
    out.salesOrderId = view.salesOrderId();
    out.salesOrderNumber = view.salesOrderNumber();
    out.salesInvoiceId = view.salesInvoiceId();
    out.creditNoteNumber = view.creditNoteNumber();
    return out;
  }

  public UUID getId() {
    return id;
  }

  public UUID getCreditNoteId() {
    return creditNoteId;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public String getStatus() {
    return status;
  }

  public String getMethod() {
    return method;
  }

  public UUID getPaymentTransactionId() {
    return paymentTransactionId;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getExecutedAt() {
    return executedAt;
  }

  public OffsetDateTime getCancelledAt() {
    return cancelledAt;
  }

  public String getCancelledReason() {
    return cancelledReason;
  }

  public String getNotes() {
    return notes;
  }

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public String getSalesOrderNumber() {
    return salesOrderNumber;
  }

  public UUID getSalesInvoiceId() {
    return salesInvoiceId;
  }

  public String getCreditNoteNumber() {
    return creditNoteNumber;
  }
}
