package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A delivery, keyed off the supplier's note (V102, {@code stories/supplier_goods_receipt.md}).
 *
 * <p><b>A document over movements, not a second source of truth.</b> Each line writes one {@code
 * +stock inventory_log} row and V101's {@code STOCK/MOVED} template already posts those (DR 1200 /
 * CR 2000), so the receipt posts nothing of its own — the whole ledger change is that {@code
 * unit_cost} is now a number somebody paid. {@code totalCost = Σ line.lineTotal} by construction,
 * frozen on the header for slice 2's bill to match against.
 */
public class GoodsReceipt {
  private UUID id;
  private UUID orgId;
  private UUID supplierId;
  private String receiptNumber;
  private GoodsReceiptStatus status = GoodsReceiptStatus.POSTED;
  private OffsetDateTime receivedAt;
  private String supplierReference;
  private String notes;
  private BigDecimal totalCost;
  private String idempotencyKey;
  private OffsetDateTime voidedAt;
  private String voidReason;
  private UUID voidedBy;
  private UUID createdBy;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private List<GoodsReceiptLine> lines = new ArrayList<>();

  public GoodsReceipt() {}

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(UUID orgId) {
    this.orgId = orgId;
  }

  public UUID getSupplierId() {
    return supplierId;
  }

  public void setSupplierId(UUID supplierId) {
    this.supplierId = supplierId;
  }

  public String getReceiptNumber() {
    return receiptNumber;
  }

  public void setReceiptNumber(String receiptNumber) {
    this.receiptNumber = receiptNumber;
  }

  public GoodsReceiptStatus getStatus() {
    return status;
  }

  public void setStatus(GoodsReceiptStatus status) {
    this.status = status;
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

  public BigDecimal getTotalCost() {
    return totalCost;
  }

  public void setTotalCost(BigDecimal totalCost) {
    this.totalCost = totalCost;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }

  public OffsetDateTime getVoidedAt() {
    return voidedAt;
  }

  public void setVoidedAt(OffsetDateTime voidedAt) {
    this.voidedAt = voidedAt;
  }

  public String getVoidReason() {
    return voidReason;
  }

  public void setVoidReason(String voidReason) {
    this.voidReason = voidReason;
  }

  public UUID getVoidedBy() {
    return voidedBy;
  }

  public void setVoidedBy(UUID voidedBy) {
    this.voidedBy = voidedBy;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(UUID createdBy) {
    this.createdBy = createdBy;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(OffsetDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(OffsetDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public List<GoodsReceiptLine> getLines() {
    return lines;
  }

  public void setLines(List<GoodsReceiptLine> lines) {
    this.lines = lines == null ? new ArrayList<>() : lines;
  }
}
