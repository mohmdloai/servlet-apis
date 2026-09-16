package com.loai.inventory.api.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** {@code POST /goods-receipts} — the delivery note as keyed. */
public class CreateGoodsReceiptRequest {
  private UUID supplierId;
  private OffsetDateTime receivedAt;
  private String supplierReference;
  private String notes;
  private List<Line> lines;

  public CreateGoodsReceiptRequest() {}

  /** {@code unit_cost} 0.00 is legal — free-of-charge goods, honestly uncosted in the ledger. */
  public static class Line {
    private UUID productId;
    private int quantity;
    private BigDecimal unitCost;

    public Line() {}

    public UUID getProductId() {
      return productId;
    }

    public void setProductId(UUID productId) {
      this.productId = productId;
    }

    public int getQuantity() {
      return quantity;
    }

    public void setQuantity(int quantity) {
      this.quantity = quantity;
    }

    public BigDecimal getUnitCost() {
      return unitCost;
    }

    public void setUnitCost(BigDecimal unitCost) {
      this.unitCost = unitCost;
    }
  }

  public UUID getSupplierId() {
    return supplierId;
  }

  public void setSupplierId(UUID supplierId) {
    this.supplierId = supplierId;
  }

  public OffsetDateTime getReceivedAt() {
    return receivedAt;
  }

  public void setReceivedAt(OffsetDateTime receivedAt) {
    this.receivedAt = receivedAt;
  }

  public String getSupplierReference() {
    return supplierReference;
  }

  public void setSupplierReference(String supplierReference) {
    this.supplierReference = supplierReference;
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }

  public List<Line> getLines() {
    return lines;
  }

  public void setLines(List<Line> lines) {
    this.lines = lines;
  }
}
