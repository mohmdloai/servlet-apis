package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.service.PaymentDisputeService.AllocationView;
import com.loai.inventory.service.PaymentDisputeService.PaymentView;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response shape for a payment. The detail read ({@code GET /payments/{id}}) additionally carries
 * {@code allocations} — every invoice this payment funded, FIFO — the entry point for the
 * dispute-resolution refund (the {@code DISPUTE_RESOLUTION} CreditNote is issued against one of
 * those invoices). Embedded payment shapes (the order money story) omit the field.
 */
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
  private List<Allocation> allocations;

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

  /** The detail read: the payment decorated with the invoices its money was allocated to. */
  public static PaymentResponse from(PaymentView view) {
    PaymentResponse out = from(view.payment());
    out.allocations = view.allocations().stream().map(Allocation::from).toList();
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

  public List<Allocation> getAllocations() {
    return allocations;
  }

  /** One slice of the payment applied to an invoice, joined to that invoice's identity. */
  public static class Allocation {
    private UUID salesInvoiceId;
    private String invoiceNumber;
    private String invoiceStatus;
    private BigDecimal amount;
    private OffsetDateTime receivedAt;

    private Allocation() {}

    static Allocation from(AllocationView view) {
      Allocation a = new Allocation();
      a.salesInvoiceId = view.allocation().getSalesInvoiceId();
      a.invoiceNumber = view.invoice().getInvoiceNumber();
      a.invoiceStatus = view.invoice().getStatus().name();
      a.amount = view.allocation().getAmount();
      a.receivedAt = view.allocation().getReceivedAt();
      return a;
    }

    public UUID getSalesInvoiceId() {
      return salesInvoiceId;
    }

    public String getInvoiceNumber() {
      return invoiceNumber;
    }

    public String getInvoiceStatus() {
      return invoiceStatus;
    }

    public BigDecimal getAmount() {
      return amount;
    }

    public OffsetDateTime getReceivedAt() {
      return receivedAt;
    }
  }
}
