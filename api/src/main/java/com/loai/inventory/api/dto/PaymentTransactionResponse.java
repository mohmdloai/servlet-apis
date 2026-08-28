package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.Customer;
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
 *
 * <p>Since {@code stories/payment_claim_verify.md} a row that is a shopper claim also carries its
 * claim context: {@code customer} (who filed it), {@code claimed_order} (the order they said it
 * pays — distinct from {@code order}, where the money actually went), {@code customer_note} (the
 * one line that explains a name mismatch), {@code has_proof} (always on the wire; the list never
 * mints the URL), {@code not_found_reason} while NOT_FOUND, and {@code verified_by_name}.
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
  private UUID claimedSalesOrderId;
  private String customerNote;
  private boolean hasProof;
  private String notFoundReason;
  private String notFoundNote;
  private String verifiedByName;
  private String proofUrl;
  private CustomerSummary customer;
  private ClaimedOrderSummary claimedOrder;
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

  /** Set the detail-only {@code proof_url} on an already-built (context-carrying) response. */
  public static PaymentTransactionResponse attachProof(
      PaymentTransactionResponse r, String proofUrl) {
    r.proofUrl = proofUrl;
    return r;
  }

  /**
   * {@code from} plus the claim context — the shopper, the order they named, the verifier's name.
   * Each may be null (not a claim / not verified); the list passes what its batch loads found.
   */
  public static PaymentTransactionResponse withContext(
      PaymentTransaction txn,
      Payment payment,
      SalesOrder order,
      Customer customer,
      SalesOrder claimedOrder,
      String verifiedByName) {
    PaymentTransactionResponse r = from(txn, payment, order);
    r.customer = customer == null ? null : CustomerSummary.from(customer);
    r.claimedOrder = claimedOrder == null ? null : ClaimedOrderSummary.from(claimedOrder);
    r.verifiedByName = verifiedByName;
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
    r.claimedSalesOrderId = txn.getClaimedSalesOrderId();
    r.customerNote = txn.getCustomerNote();
    r.hasProof = txn.getProofObjectKey() != null && !txn.getProofObjectKey().isBlank();
    r.notFoundReason = txn.getNotFoundReason();
    r.notFoundNote = txn.getNotFoundNote();
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

  /** The order the shopper said this transfer pays (a claim only); omitted otherwise. */
  public UUID getClaimedSalesOrderId() {
    return claimedSalesOrderId;
  }

  /** The shopper's note on their claim ("sent from my brother's account"); omitted when none. */
  public String getCustomerNote() {
    return customerNote;
  }

  /** Whether a screenshot is attached — always on the wire; the URL itself is detail-only. */
  public boolean isHasProof() {
    return hasProof;
  }

  /** Why the manager could not find the transfer — present only while NOT_FOUND. */
  public String getNotFoundReason() {
    return notFoundReason;
  }

  /** The manager's note to the shopper beside the reason — present only while NOT_FOUND. */
  public String getNotFoundNote() {
    return notFoundNote;
  }

  /** Display name (or email) of the user who verified it; detail + verify responses only. */
  public String getVerifiedByName() {
    return verifiedByName;
  }

  /** Presigned view of the shopper's uploaded proof — detail read only, omitted when absent. */
  public String getProofUrl() {
    return proofUrl;
  }

  /** The shopper who filed the claim. */
  public CustomerSummary getCustomer() {
    return customer;
  }

  /** The order the shopper named, with its clock — what the queue sorts and counts down by. */
  public ClaimedOrderSummary getClaimedOrder() {
    return claimedOrder;
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

  /** Who filed the claim — name and phone, the two facts a manager matches against the bank app. */
  public static class CustomerSummary {
    private UUID id;
    private String name;
    private String phone;

    private CustomerSummary() {}

    static CustomerSummary from(Customer c) {
      CustomerSummary s = new CustomerSummary();
      s.id = c.getId();
      s.name = c.getName();
      s.phone = c.getPhoneE164() != null ? c.getPhoneE164() : c.getPhone();
      return s;
    }

    public UUID getId() {
      return id;
    }

    public String getName() {
      return name;
    }

    public String getPhone() {
      return phone;
    }
  }

  /**
   * The order the shopper named: status + money meter + {@code expires_at}, the clock the queue is
   * sorted by. Same fields as {@link OrderSummary} plus the deadline.
   */
  public static class ClaimedOrderSummary {
    private UUID id;
    private String orderNumber;
    private OrderStatus status;
    private BigDecimal grandTotal;
    private BigDecimal prepaidAmount;
    private OffsetDateTime expiresAt;

    private ClaimedOrderSummary() {}

    static ClaimedOrderSummary from(SalesOrder o) {
      ClaimedOrderSummary s = new ClaimedOrderSummary();
      s.id = o.getId();
      s.orderNumber = o.getOrderNumber();
      s.status = o.getStatus();
      s.grandTotal = o.getGrandTotal();
      s.prepaidAmount = o.getPrepaidAmount();
      s.expiresAt = o.getExpiresAt();
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

    public OffsetDateTime getExpiresAt() {
      return expiresAt;
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
