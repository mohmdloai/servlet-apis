package com.loai.inventory.api.dto;

/** {@code POST /goods-receipts/{id}/void} — a void has an author and a reason, both required. */
public class VoidGoodsReceiptRequest {
  private String reason;

  public VoidGoodsReceiptRequest() {}

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }
}
