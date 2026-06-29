package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Payment;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Response shape for a payment. */
public class PaymentResponse {
  private UUID id;
  private UUID salesOrderId;
  private UUID customerId;
  private UUID paymentTransactionId;
  private BigDecimal amount;
  private String currency;
  private BigDecimal unallocatedAmount;
  private BigDecimal refundedAmount;
  private String status;
  private OffsetDateTime receivedAt;
  private OffsetDateTime disputedAt;
  private String disputeReason;
  private String notes;

  private PaymentResponse() {}

  public static PaymentResponse from(Payment p) {
    PaymentResponse out = new PaymentResponse();
    out.id = p.getId();
    out.salesOrderId = p.getSalesOrderId();
    out.customerId = p.getCustomerId();
    out.paymentTransactionId = p.getPaymentTransactionId();
    out.amount = p.getAmount();
    out.currency = p.getCurrency();
    out.unallocatedAmount = p.getUnallocatedAmount();
    out.refundedAmount = p.getRefundedAmount();
    out.status = p.getStatus().name();
    out.receivedAt = p.getReceivedAt();
    out.disputedAt = p.getDisputedAt();
    out.disputeReason = p.getDisputeReason();
    out.notes = p.getNotes();
    return out;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public UUID getPaymentTransactionId() {
    return paymentTransactionId;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public BigDecimal getUnallocatedAmount() {
    return unallocatedAmount;
  }

  public BigDecimal getRefundedAmount() {
    return refundedAmount;
  }

  public String getStatus() {
    return status;
  }

  public OffsetDateTime getReceivedAt() {
    return receivedAt;
  }

  public OffsetDateTime getDisputedAt() {
    return disputedAt;
  }

  public String getDisputeReason() {
    return disputeReason;
  }

  public String getNotes() {
    return notes;
  }
}
