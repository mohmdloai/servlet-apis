package com.loai.inventory.domain.model;

import com.loai.inventory.common.exception.InvalidOrderTransitionException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * The document the org issues to a customer establishing a financial obligation — a frozen snapshot
 * of pricing, tax and customer data at issuance time, immutable post-issuance. In the online flow
 * one invoice is issued per Fulfillment that reaches DELIVERED. See {@code
 * sys-analysis/outbound/invoicing.md}.
 *
 * <p>This slice models DRAFT → ISSUED → PAID. The object is built DRAFT, {@link #issue issued}
 * (which assigns the gapless invoice number), and may be flipped to PAID by {@link
 * #recordAllocation allocations} — all inside the one transaction triggered by marking the
 * Fulfillment DELIVERED. The DRAFT state therefore only ever exists in memory; the row is persisted
 * already ISSUED.
 */
public final class SalesInvoice {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

  private final UUID id;
  private final UUID orgId;
  private final UUID customerId;
  private final UUID salesOrderId;
  private final UUID fulfillmentId;
  private final BigDecimal subtotal;
  private final BigDecimal taxTotal;
  private final BigDecimal discountTotal;
  private final BigDecimal grandTotal;
  private final String currency;
  private final String customerName;
  private final String customerEmail;
  private final String customerPhone;
  private final String customerAddress;
  private final OffsetDateTime createdAt;

  private InvoiceStatus status;
  private String invoiceNumber;
  private BigDecimal paidAmount;
  private OffsetDateTime issuedAt;
  private OffsetDateTime voidedAt;
  private String voidReason;
  private OffsetDateTime updatedAt;

  /**
   * Build a DRAFT invoice with frozen totals and a customer snapshot. {@code invoiceNumber} is not
   * assigned until {@link #issue}.
   */
  public static SalesInvoice createDraft(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID salesOrderId,
      UUID fulfillmentId,
      BigDecimal subtotal,
      BigDecimal taxTotal,
      BigDecimal discountTotal,
      String currency,
      String customerName,
      String customerEmail,
      String customerPhone,
      String customerAddress,
      OffsetDateTime now) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(orgId, "orgId required");
    Objects.requireNonNull(fulfillmentId, "fulfillmentId required");
    Objects.requireNonNull(subtotal, "subtotal required");
    Objects.requireNonNull(taxTotal, "taxTotal required");
    Objects.requireNonNull(discountTotal, "discountTotal required");
    Objects.requireNonNull(currency, "currency required");
    Objects.requireNonNull(customerName, "customerName required");
    Objects.requireNonNull(now, "now required");
    if (subtotal.signum() < 0 || taxTotal.signum() < 0 || discountTotal.signum() < 0) {
      throw new IllegalArgumentException("money fields must be >= 0");
    }
    BigDecimal sub = subtotal.setScale(MONEY_SCALE, MONEY_ROUNDING);
    BigDecimal tax = taxTotal.setScale(MONEY_SCALE, MONEY_ROUNDING);
    BigDecimal disc = discountTotal.setScale(MONEY_SCALE, MONEY_ROUNDING);
    BigDecimal grand = sub.add(tax).subtract(disc);
    if (grand.signum() <= 0) {
      throw new IllegalArgumentException("grandTotal must be > 0");
    }
    return new SalesInvoice(
        id,
        orgId,
        customerId,
        salesOrderId,
        fulfillmentId,
        sub,
        tax,
        disc,
        grand,
        currency,
        customerName,
        customerEmail,
        customerPhone,
        customerAddress,
        now,
        InvoiceStatus.DRAFT,
        null,
        BigDecimal.ZERO.setScale(MONEY_SCALE, MONEY_ROUNDING),
        null,
        null,
        null,
        now);
  }

  /** Reconstitute from a persisted row — trusts DB invariants, skips creation-time validation. */
  public static SalesInvoice rehydrate(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID salesOrderId,
      UUID fulfillmentId,
      String invoiceNumber,
      InvoiceStatus status,
      BigDecimal subtotal,
      BigDecimal taxTotal,
      BigDecimal discountTotal,
      BigDecimal grandTotal,
      String currency,
      String customerName,
      String customerEmail,
      String customerPhone,
      String customerAddress,
      BigDecimal paidAmount,
      OffsetDateTime issuedAt,
      OffsetDateTime voidedAt,
      String voidReason,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {
    SalesInvoice inv =
        new SalesInvoice(
            id,
            orgId,
            customerId,
            salesOrderId,
            fulfillmentId,
            subtotal,
            taxTotal,
            discountTotal,
            grandTotal,
            currency,
            customerName,
            customerEmail,
            customerPhone,
            customerAddress,
            createdAt,
            status,
            invoiceNumber,
            paidAmount,
            issuedAt,
            voidedAt,
            voidReason,
            updatedAt);
    return inv;
  }

  private SalesInvoice(
      UUID id,
      UUID orgId,
      UUID customerId,
      UUID salesOrderId,
      UUID fulfillmentId,
      BigDecimal subtotal,
      BigDecimal taxTotal,
      BigDecimal discountTotal,
      BigDecimal grandTotal,
      String currency,
      String customerName,
      String customerEmail,
      String customerPhone,
      String customerAddress,
      OffsetDateTime createdAt,
      InvoiceStatus status,
      String invoiceNumber,
      BigDecimal paidAmount,
      OffsetDateTime issuedAt,
      OffsetDateTime voidedAt,
      String voidReason,
      OffsetDateTime updatedAt) {
    this.id = id;
    this.orgId = orgId;
    this.customerId = customerId;
    this.salesOrderId = salesOrderId;
    this.fulfillmentId = fulfillmentId;
    this.subtotal = subtotal;
    this.taxTotal = taxTotal;
    this.discountTotal = discountTotal;
    this.grandTotal = grandTotal;
    this.currency = currency;
    this.customerName = customerName;
    this.customerEmail = customerEmail;
    this.customerPhone = customerPhone;
    this.customerAddress = customerAddress;
    this.createdAt = createdAt;
    this.status = status;
    this.invoiceNumber = invoiceNumber;
    this.paidAmount = paidAmount;
    this.issuedAt = issuedAt;
    this.voidedAt = voidedAt;
    this.voidReason = voidReason;
    this.updatedAt = updatedAt;
  }

  /**
   * Assign the gapless {@code invoiceNumber} and move DRAFT → ISSUED, freezing the document. The
   * caller supplies the number claimed from the per-org per-year counter inside the same
   * transaction.
   */
  public void issue(String invoiceNumber, OffsetDateTime now) {
    if (this.status != InvoiceStatus.DRAFT) {
      throw new InvalidOrderTransitionException(
          "cannot issue invoice " + id + " in status " + status + "; expected DRAFT");
    }
    if (invoiceNumber == null || invoiceNumber.isBlank()) {
      throw new IllegalArgumentException("invoiceNumber required");
    }
    Objects.requireNonNull(now, "now required");
    this.invoiceNumber = invoiceNumber;
    this.status = InvoiceStatus.ISSUED;
    this.issuedAt = now;
    this.updatedAt = now;
  }

  /**
   * Apply {@code amount} of a payment allocation to this invoice. Increments the cached {@code
   * paidAmount} and flips ISSUED → PAID once it reaches {@code grandTotal}. Only legal on an ISSUED
   * (or already PAID, idempotent overpay guard) invoice.
   */
  public void recordAllocation(BigDecimal amount, OffsetDateTime now) {
    if (this.status != InvoiceStatus.ISSUED && this.status != InvoiceStatus.PAID) {
      throw new InvalidOrderTransitionException(
          "cannot allocate to invoice " + id + " in status " + status + "; must be ISSUED");
    }
    Objects.requireNonNull(amount, "amount required");
    Objects.requireNonNull(now, "now required");
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("allocation amount must be > 0");
    }
    this.paidAmount = this.paidAmount.add(amount).setScale(MONEY_SCALE, MONEY_ROUNDING);
    if (this.paidAmount.compareTo(this.grandTotal) >= 0) {
      this.status = InvoiceStatus.PAID;
    }
    this.updatedAt = now;
  }

  /**
   * Cancel an ISSUED invoice: move ISSUED → VOID, recording {@code reason} and {@code voidedAt}.
   * Legal only on an unpaid ISSUED invoice — a PAID (or already VOID/DRAFT) invoice cannot be
   * voided, and the {@code paidAmount == 0} check is a defensive mirror of the service's "no
   * allocations" rule (a non-zero cached paid amount means allocations exist). Named {@code
   * voidInvoice} because {@code void} is a reserved word.
   */
  public void voidInvoice(String reason, OffsetDateTime now) {
    if (this.status != InvoiceStatus.ISSUED) {
      throw new InvalidOrderTransitionException(
          "cannot void invoice " + id + " in status " + status + "; must be ISSUED");
    }
    if (this.paidAmount.signum() != 0) {
      throw new InvalidOrderTransitionException(
          "cannot void invoice " + id + "; it has allocations (paidAmount=" + paidAmount + ")");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("void reason required");
    }
    Objects.requireNonNull(now, "now required");
    this.status = InvoiceStatus.VOID;
    this.voidedAt = now;
    this.voidReason = reason;
    this.updatedAt = now;
  }

  public boolean isPaid() {
    return status == InvoiceStatus.PAID;
  }

  public boolean isVoid() {
    return status == InvoiceStatus.VOID;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public UUID getFulfillmentId() {
    return fulfillmentId;
  }

  public String getInvoiceNumber() {
    return invoiceNumber;
  }

  public InvoiceStatus getStatus() {
    return status;
  }

  public BigDecimal getSubtotal() {
    return subtotal;
  }

  public BigDecimal getTaxTotal() {
    return taxTotal;
  }

  public BigDecimal getDiscountTotal() {
    return discountTotal;
  }

  public BigDecimal getGrandTotal() {
    return grandTotal;
  }

  public String getCurrency() {
    return currency;
  }

  public String getCustomerName() {
    return customerName;
  }

  public String getCustomerEmail() {
    return customerEmail;
  }

  public String getCustomerPhone() {
    return customerPhone;
  }

  public String getCustomerAddress() {
    return customerAddress;
  }

  public BigDecimal getPaidAmount() {
    return paidAmount;
  }

  public OffsetDateTime getIssuedAt() {
    return issuedAt;
  }

  public OffsetDateTime getVoidedAt() {
    return voidedAt;
  }

  public String getVoidReason() {
    return voidReason;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  @Override
  public String toString() {
    return "SalesInvoice{id="
        + id
        + ", invoiceNumber='"
        + invoiceNumber
        + "', status="
        + status
        + ", grandTotal="
        + grandTotal
        + ", paidAmount="
        + paidAmount
        + "}";
  }
}
