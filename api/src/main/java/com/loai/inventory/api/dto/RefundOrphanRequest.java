package com.loai.inventory.api.dto;

/**
 * Body of {@code POST /api/orgs/{orgId}/payment-transactions/{id}/refund}. Both fields are
 * optional: {@code method} (refund rail — enum name or DB literal) defaults to the transaction's
 * own provider, {@code notes} to a standard orphan-refund note. An empty body is valid.
 */
public class RefundOrphanRequest {
  private String method;
  private String notes;

  public RefundOrphanRequest() {}

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
