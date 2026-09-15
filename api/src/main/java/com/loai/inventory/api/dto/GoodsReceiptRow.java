package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.GoodsReceipt;
import com.loai.inventory.domain.model.GoodsReceiptStatus;
import com.loai.inventory.domain.model.Supplier;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One lean row of a receipt list. {@code supplier} rides the org-wide list and is absent on the
 * {@code /suppliers/{id}/receipts} subresource, where the supplier is the path.
 */
public class GoodsReceiptRow {
  private UUID id;
  private String receiptNumber;
  private SupplierResponse.Ref supplier;
  private OffsetDateTime receivedAt;
  private String supplierReference;
  private int lineCount;
  private BigDecimal totalCost;
  private GoodsReceiptStatus status;

  private GoodsReceiptRow() {}

  public static GoodsReceiptRow from(GoodsReceipt g, Supplier supplier, int lineCount) {
    GoodsReceiptRow r = new GoodsReceiptRow();
    r.id = g.getId();
    r.receiptNumber = g.getReceiptNumber();
    r.supplier = SupplierResponse.ref(supplier);
    r.receivedAt = g.getReceivedAt();
    r.supplierReference = g.getSupplierReference();
    r.lineCount = lineCount;
    r.totalCost = g.getTotalCost();
    r.status = g.getStatus();
    return r;
  }

  public UUID getId() {
    return id;
  }

  public String getReceiptNumber() {
    return receiptNumber;
  }

  public SupplierResponse.Ref getSupplier() {
    return supplier;
  }

  public OffsetDateTime getReceivedAt() {
    return receivedAt;
  }

  public String getSupplierReference() {
    return supplierReference;
  }

  public int getLineCount() {
    return lineCount;
  }

  public BigDecimal getTotalCost() {
    return totalCost;
  }

  public GoodsReceiptStatus getStatus() {
    return status;
  }
}
