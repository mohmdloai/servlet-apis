package com.loai.inventory.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Request body for {@code POST /api/orgs/{orgId}/payment-transactions}.
 *
 * <p>Jackson data carrier only (snake_case JSON ↔ camelCase Java via the global {@code
 * ObjectMapper}). Validation + provider parsing live in the service / mapper. The order is targeted
 * by either {@code sales_order_id} or {@code order_number}; both absent → ORPHAN (not an error).
 */
public class VerifyPaymentTransactionRequest {

  private String provider;
  private String providerRef;
  private BigDecimal amount;
  private String currency;
  private UUID salesOrderId;
  private String orderNumber;
  private UUID claimedByCustomerId;
  private String customerNote;
  private String verificationProof;
  private OffsetDateTime occurredAt;

  public VerifyPaymentTransactionRequest() {}

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getProviderRef() {
    return providerRef;
  }

  public void setProviderRef(String providerRef) {
    this.providerRef = providerRef;
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

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public void setSalesOrderId(UUID salesOrderId) {
    this.salesOrderId = salesOrderId;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public void setOrderNumber(String orderNumber) {
    this.orderNumber = orderNumber;
  }

  public UUID getClaimedByCustomerId() {
    return claimedByCustomerId;
  }

  public void setClaimedByCustomerId(UUID claimedByCustomerId) {
    this.claimedByCustomerId = claimedByCustomerId;
  }

  public String getCustomerNote() {
    return customerNote;
  }

  public void setCustomerNote(String customerNote) {
    this.customerNote = customerNote;
  }

  public String getVerificationProof() {
    return verificationProof;
  }

  public void setVerificationProof(String verificationProof) {
    this.verificationProof = verificationProof;
  }

  public OffsetDateTime getOccurredAt() {
    return occurredAt;
  }

  public void setOccurredAt(OffsetDateTime occurredAt) {
    this.occurredAt = occurredAt;
  }
}
