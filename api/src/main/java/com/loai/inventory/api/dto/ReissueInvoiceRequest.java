package com.loai.inventory.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /api/orgs/{orgId}/invoices/{id}/reissue}: a free-text {@code reason} for the
 * void and the corrected lines for the replacement invoice.
 */
public class ReissueInvoiceRequest {
  private String reason;
  private List<Line> lines;

  public ReissueInvoiceRequest() {}

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public List<Line> getLines() {
    return lines;
  }

  public void setLines(List<Line> lines) {
    this.lines = lines;
  }

  /** One corrected invoice line. */
  public static class Line {
    private UUID productId;
    private String description;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal taxRate;

    public Line() {}

    public UUID getProductId() {
      return productId;
    }

    public void setProductId(UUID productId) {
      this.productId = productId;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public Integer getQuantity() {
      return quantity;
    }

    public void setQuantity(Integer quantity) {
      this.quantity = quantity;
    }

    public BigDecimal getUnitPrice() {
      return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
      this.unitPrice = unitPrice;
    }

    public BigDecimal getTaxRate() {
      return taxRate;
    }

    public void setTaxRate(BigDecimal taxRate) {
      this.taxRate = taxRate;
    }
  }
}
