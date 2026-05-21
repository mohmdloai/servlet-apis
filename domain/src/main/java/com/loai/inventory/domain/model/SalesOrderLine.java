package com.loai.inventory.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public final class SalesOrderLine {

  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

  private final UUID id;
  private final UUID salesOrderId;
  private final UUID productId;
  private final String description;
  private final int quantity;
  private final BigDecimal unitPrice;
  private final BigDecimal taxRate;
  private final BigDecimal lineSubtotal;
  private final BigDecimal lineTax;
  private final BigDecimal lineTotal;

  /** Build a fresh line from product snapshot + requested quantity. Totals are computed here. */
  public static SalesOrderLine create(
      UUID id,
      UUID salesOrderId,
      UUID productId,
      String description,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal taxRate) {
    Objects.requireNonNull(id, "id required");
    Objects.requireNonNull(salesOrderId, "salesOrderId required");
    Objects.requireNonNull(productId, "productId required");
    Objects.requireNonNull(description, "description required");
    Objects.requireNonNull(unitPrice, "unitPrice required");
    Objects.requireNonNull(taxRate, "taxRate required");
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be > 0");
    }
    if (unitPrice.signum() < 0) {
      throw new IllegalArgumentException("unitPrice must be >= 0");
    }
    if (taxRate.signum() < 0) {
      throw new IllegalArgumentException("taxRate must be >= 0");
    }
    BigDecimal subtotal =
        unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(MONEY_SCALE, MONEY_ROUNDING);
    BigDecimal tax = subtotal.multiply(taxRate).setScale(MONEY_SCALE, MONEY_ROUNDING);
    BigDecimal total = subtotal.add(tax);
    return new SalesOrderLine(
        id,
        salesOrderId,
        productId,
        description,
        quantity,
        unitPrice.setScale(MONEY_SCALE, MONEY_ROUNDING),
        taxRate,
        subtotal,
        tax,
        total);
  }

  /** Reconstruct from persisted row — trusts DB invariants, skips recomputation. */
  public static SalesOrderLine rehydrate(
      UUID id,
      UUID salesOrderId,
      UUID productId,
      String description,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal taxRate,
      BigDecimal lineSubtotal,
      BigDecimal lineTax,
      BigDecimal lineTotal) {
    return new SalesOrderLine(
        id,
        salesOrderId,
        productId,
        description,
        quantity,
        unitPrice,
        taxRate,
        lineSubtotal,
        lineTax,
        lineTotal);
  }

  private SalesOrderLine(
      UUID id,
      UUID salesOrderId,
      UUID productId,
      String description,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal taxRate,
      BigDecimal lineSubtotal,
      BigDecimal lineTax,
      BigDecimal lineTotal) {
    this.id = id;
    this.salesOrderId = salesOrderId;
    this.productId = productId;
    this.description = description;
    this.quantity = quantity;
    this.unitPrice = unitPrice;
    this.taxRate = taxRate;
    this.lineSubtotal = lineSubtotal;
    this.lineTax = lineTax;
    this.lineTotal = lineTotal;
  }

  public UUID getId() {
    return id;
  }

  public UUID getSalesOrderId() {
    return salesOrderId;
  }

  public UUID getProductId() {
    return productId;
  }

  public String getDescription() {
    return description;
  }

  public int getQuantity() {
    return quantity;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public BigDecimal getTaxRate() {
    return taxRate;
  }

  public BigDecimal getLineSubtotal() {
    return lineSubtotal;
  }

  public BigDecimal getLineTax() {
    return lineTax;
  }

  public BigDecimal getLineTotal() {
    return lineTotal;
  }
}
