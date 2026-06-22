package com.loai.inventory.api.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of {@code POST /api/orgs/{orgId}/refunds}. Exactly one of the two source ids must be set.
 */
public class CreateRefundRequest {
  private UUID creditNoteId;
  private UUID paymentId;
  private BigDecimal amount;
  private String currency;
  private String method;
  private String notes;

  public CreateRefundRequest() {}

  public UUID getCreditNoteId() {
    return creditNoteId;
  }

  public void setCreditNoteId(UUID creditNoteId) {
    this.creditNoteId = creditNoteId;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public void setPaymentId(UUID paymentId) {
    this.paymentId = paymentId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getMethod() {
    return method;
  }

  public void setMethod(String method) {
    this.method = method;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
