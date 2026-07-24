package com.loai.inventory.api.dto;

/**
 * Body for a shopper payment-proof claim (roadmap item 2) — {@code POST
 * /api/public/orders/{token}/payment-claim} and {@code POST
 * /api/portal/orders/{orderNumber}/payment-claim}. The shopper supplies the InstaPay {@code
 * reference}, an optional uploaded screenshot {@code proof_object_key} (minted by the sibling
 * presign), and an optional {@code note}. The order + amount are resolved server-side; the amount
 * is never taken from the body.
 */
public class PaymentClaimRequest {
  private String reference;
  private String proofObjectKey;
  private String note;

  public PaymentClaimRequest() {}

  public String getReference() {
    return reference;
  }

  public void setReference(String reference) {
    this.reference = reference;
  }

  public String getProofObjectKey() {
    return proofObjectKey;
  }

  public void setProofObjectKey(String proofObjectKey) {
    this.proofObjectKey = proofObjectKey;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }
}
