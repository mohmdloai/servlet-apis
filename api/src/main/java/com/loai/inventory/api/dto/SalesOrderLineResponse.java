package com.loai.inventory.api.dto;

import com.loai.inventory.domain.model.SalesOrderLine;
import java.math.BigDecimal;
import java.util.UUID;

public class SalesOrderLineResponse {
  private UUID id;
  private UUID productId;
  private String description;
  private int quantity;
  private BigDecimal unitPrice;
  private BigDecimal taxRate;
  private BigDecimal lineSubtotal;
  private BigDecimal lineTax;
  private BigDecimal lineTotal;

  private SalesOrderLineResponse() {}

  public static SalesOrderLineResponse from(SalesOrderLine line) {
    SalesOrderLineResponse r = new SalesOrderLineResponse();
    r.id = line.getId();
    r.productId = line.getProductId();
    r.description = line.getDescription();
    r.quantity = line.getQuantity();
    r.unitPrice = line.getUnitPrice();
    r.taxRate = line.getTaxRate();
    r.lineSubtotal = line.getLineSubtotal();
    r.lineTax = line.getLineTax();
    r.lineTotal = line.getLineTotal();
    return r;
  }

  public UUID getId() {
    return id;
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
