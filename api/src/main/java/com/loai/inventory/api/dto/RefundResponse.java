package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Refund;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Response shape for a refund. */
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
  private OffsetDateTime executedAt;
  private OffsetDateTime cancelledAt;
  private String cancelledReason;
  private String notes;

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
    out.executedAt = r.getExecutedAt();
    out.cancelledAt = r.getCancelledAt();
    out.cancelledReason = r.getCancelledReason();
    out.notes = r.getNotes();
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
}
