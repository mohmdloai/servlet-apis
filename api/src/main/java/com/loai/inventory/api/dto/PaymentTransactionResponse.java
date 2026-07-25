package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentDirection;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.PaymentVerificationStatus;
import com.loai.inventory.domain.model.SalesOrder;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response for the verify endpoint: the recorded+verified transaction, its reconciliation outcome,
 * and (on MATCHED) the created payment + the affected order. Null fields are omitted by the global
 * {@code ObjectMapper}, so {@code payment} / {@code order} appear only when present.
 */
public class PaymentTransactionResponse {

  private UUID id;
  private UUID orgId;
  private String provider;
  private String providerRef;
  private PaymentDirection direction;
  private BigDecimal amount;
  private String currency;
  private PaymentVerificationStatus verificationStatus;
  private UUID verifiedBy;
  private OffsetDateTime verifiedAt;
  private PaymentReconciliationStatus reconciliationStatus;
  private OffsetDateTime occurredAt;
  private OffsetDateTime recordedAt;
  private UUID claimedByCustomerId;
  private String proofUrl;
  private PaymentSummary payment;
  private OrderSummary order;

  private PaymentTransactionResponse() {}

  /**
   * The detail shape: {@code from} plus {@code proof_url}, a short-lived presigned GET for the
   * screenshot the shopper attached. Null (hence omitted) when they attached none. Only the detail
   * read calls this — a worklist page must not mint a read credential per row.
   */
  public static PaymentTransactionResponse withProof(
      PaymentTransaction txn, Payment payment, SalesOrder order, String proofUrl) {
    PaymentTransactionResponse r = from(txn, payment, order);
    r.proofUrl = proofUrl;
    return r;
  }

  public static PaymentTransactionResponse from(
      PaymentTransaction txn, Payment payment, SalesOrder order) {
    PaymentTransactionResponse r = new PaymentTransactionResponse();
    r.id = txn.getId();
    r.orgId = txn.getOrgId();
    r.provider = txn.getProvider().name();
    r.providerRef = txn.getProviderRef();
    r.direction = txn.getDirection();
    r.amount = txn.getAmount();
    r.currency = txn.getCurrency();
    r.verificationStatus = txn.getVerificationStatus();
    r.verifiedBy = txn.getVerifiedBy();
    r.verifiedAt = txn.getVerifiedAt();
    r.reconciliationStatus = txn.getReconciliationStatus();
    r.occurredAt = txn.getOccurredAt();
    r.recordedAt = txn.getRecordedAt();
    r.claimedByCustomerId = txn.getClaimedByCustomerId();
    r.payment = payment == null ? null : PaymentSummary.from(payment);
    r.order = order == null ? null : OrderSummary.from(order);
    return r;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public String getProvider() {
    return provider;
  }

  public String getProviderRef() {
    return providerRef;
  }

  public PaymentDirection getDirection() {
    return direction;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public PaymentVerificationStatus getVerificationStatus() {
    return verificationStatus;
  }

  public UUID getVerifiedBy() {
    return verifiedBy;
  }

  public OffsetDateTime getVerifiedAt() {
    return verifiedAt;
  }

  public PaymentReconciliationStatus getReconciliationStatus() {
    return reconciliationStatus;
  }

  public OffsetDateTime getOccurredAt() {
    return occurredAt;
  }

  public OffsetDateTime getRecordedAt() {
    return recordedAt;
  }

  public UUID getClaimedByCustomerId() {
    return claimedByCustomerId;
  }

  /** Presigned view of the shopper's uploaded proof — detail read only, omitted when absent. */
  public String getProofUrl() {
    return proofUrl;
  }

  public PaymentSummary getPayment() {
    return payment;
  }

  public OrderSummary getOrder() {
    return order;
  }

  /** The payment created on a MATCHED reconciliation. */
  public static class PaymentSummary {
    private UUID id;
    private UUID salesOrderId;
    private BigDecimal amount;
    private BigDecimal unallocatedAmount;
    private PaymentStatus status;

    private PaymentSummary() {}

    static PaymentSummary from(Payment p) {
      PaymentSummary s = new PaymentSummary();
      s.id = p.getId();
      s.salesOrderId = p.getSalesOrderId();
      s.amount = p.getAmount();
      s.unallocatedAmount = p.getUnallocatedAmount();
      s.status = p.getStatus();
      return s;
    }

    public UUID getId() {
      return id;
    }

    public UUID getSalesOrderId() {
      return salesOrderId;
    }

    public BigDecimal getAmount() {
      return amount;
    }

    public BigDecimal getUnallocatedAmount() {
      return unallocatedAmount;
    }

    public PaymentStatus getStatus() {
      return status;
    }
  }

  /** The order whose payment state the transaction touched. */
  public static class OrderSummary {
    private UUID id;
    private String orderNumber;
    private OrderStatus status;
    private BigDecimal grandTotal;
    private BigDecimal prepaidAmount;

    private OrderSummary() {}

    static OrderSummary from(SalesOrder o) {
      OrderSummary s = new OrderSummary();
      s.id = o.getId();
      s.orderNumber = o.getOrderNumber();
      s.status = o.getStatus();
      s.grandTotal = o.getGrandTotal();
      s.prepaidAmount = o.getPrepaidAmount();
      return s;
    }

    public UUID getId() {
      return id;
    }

    public String getOrderNumber() {
      return orderNumber;
    }

    public OrderStatus getStatus() {
      return status;
    }

    public BigDecimal getGrandTotal() {
      return grandTotal;
    }

    public BigDecimal getPrepaidAmount() {
      return prepaidAmount;
    }
  }
}
