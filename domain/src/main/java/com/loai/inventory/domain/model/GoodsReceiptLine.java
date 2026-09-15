package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One product on a delivery note: {@code line_total = quantity × unit_cost}.
 *
 * <p>{@code unit_cost = 0.00} is legal and means free-of-charge goods — the stock moves, the ledger
 * posts nothing ({@code journal_line.amount > 0}) and {@code ledger_skip} records UNCOSTED, which
 * is the honest reading of a delivery that cost nothing.
 */
public class GoodsReceiptLine {
  private Long id;
  private UUID orgId;
  private UUID goodsReceiptId;
  private UUID productId;
  private int quantity;
  private BigDecimal unitCost;
  private BigDecimal lineTotal;

  public GoodsReceiptLine() {}

  public GoodsReceiptLine(UUID productId, int quantity, BigDecimal unitCost) {
    this.productId = productId;
    this.quantity = quantity;
    this.unitCost = unitCost;
    this.lineTotal = unitCost == null ? null : unitCost.multiply(BigDecimal.valueOf(quantity));
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public void setOrgId(UUID orgId) {
    this.orgId = orgId;
  }

  public UUID getGoodsReceiptId() {
    return goodsReceiptId;
  }

  public void setGoodsReceiptId(UUID goodsReceiptId) {
    this.goodsReceiptId = goodsReceiptId;
  }

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

  public BigDecimal getLineTotal() {
    return lineTotal;
  }

  public void setLineTotal(BigDecimal lineTotal) {
    this.lineTotal = lineTotal;
  }
}
