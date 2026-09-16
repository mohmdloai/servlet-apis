package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.GoodsReceipt;
import com.loai.inventory.domain.model.GoodsReceiptLine;
import com.loai.inventory.domain.model.GoodsReceiptStatus;
import com.loai.inventory.domain.model.Product;
import com.loai.inventory.service.GoodsReceiptService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The delivery note as a document: header, supplier, lines with {@code quantity × unit_cost =
 * line_total} and each line's {@code stock_after}. A VOIDED receipt keeps every figure and wears
 * {@code voided_at} / {@code void_reason} / {@code voided_by_name} — it is history, not a deletion.
 */
public class GoodsReceiptResponse {
  private UUID id;
  private String receiptNumber;
  private SupplierResponse.Ref supplier;
  private OffsetDateTime receivedAt;
  private String supplierReference;
  private String notes;
  private GoodsReceiptStatus status;
  private BigDecimal totalCost;
  private List<Line> lines;
  private OffsetDateTime voidedAt;
  private String voidReason;
  private String voidedByName;
  private OffsetDateTime createdAt;

  private GoodsReceiptResponse() {}

  public static GoodsReceiptResponse from(GoodsReceiptService.ReceiptView v) {
    GoodsReceipt g = v.receipt();
    GoodsReceiptResponse r = new GoodsReceiptResponse();
    r.id = g.getId();
    r.receiptNumber = g.getReceiptNumber();
    r.supplier = SupplierResponse.ref(v.supplier());
    r.receivedAt = g.getReceivedAt();
    r.supplierReference = g.getSupplierReference();
    r.notes = g.getNotes();
    r.status = g.getStatus();
    r.totalCost = g.getTotalCost();
    r.lines = g.getLines().stream().map(l -> Line.of(l, v.products(), v.stockAfter())).toList();
    r.voidedAt = g.getVoidedAt();
    r.voidReason = g.getVoidReason();
    r.voidedByName = v.voidedByName();
    r.createdAt = g.getCreatedAt();
    return r;
  }

  /** {@code stock_after} is read back from the movements the line wrote, never stored twice. */
  public record Line(
      UUID productId,
      String productName,
      String sku,
      int quantity,
      BigDecimal unitCost,
      BigDecimal lineTotal,
      Integer stockAfter) {

    static Line of(GoodsReceiptLine l, Map<UUID, Product> products, Map<UUID, Integer> stockAfter) {
      Product p = products.get(l.getProductId());
      return new Line(
          l.getProductId(),
          p == null ? null : p.getName(),
          p == null ? null : p.getSku(),
          l.getQuantity(),
          l.getUnitCost(),
          l.getLineTotal(),
          stockAfter.get(l.getProductId()));
    }
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

  public String getNotes() {
    return notes;
  }

  public GoodsReceiptStatus getStatus() {
    return status;
  }

  public BigDecimal getTotalCost() {
    return totalCost;
  }

  public List<Line> getLines() {
    return lines;
  }

  public OffsetDateTime getVoidedAt() {
    return voidedAt;
  }

  public String getVoidReason() {
    return voidReason;
  }

  public String getVoidedByName() {
    return voidedByName;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
