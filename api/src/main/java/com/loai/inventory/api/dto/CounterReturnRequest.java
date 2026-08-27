package com.loai.inventory.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Body of {@code POST /api/orgs/{orgId}/sales-orders/{id}/return} ({@code
 * stories/counter_return.md}): which products, how many, whether they go back on the shelf, and an
 * optional reason. No prices — the receipt is the authority for those.
 */
public class CounterReturnRequest {
  private List<Line> lines;
  private Boolean restock;
  private String reasonNote;

  public CounterReturnRequest() {}

  public List<Line> getLines() {
    return lines;
  }

  public void setLines(List<Line> lines) {
    this.lines = lines;
  }

  public Boolean getRestock() {
    return restock;
  }

  public void setRestock(Boolean restock) {
    this.restock = restock;
  }

  public String getReasonNote() {
    return reasonNote;
  }

  public void setReasonNote(String reasonNote) {
    this.reasonNote = reasonNote;
  }

  public static class Line {
    private UUID productId;
    private Integer quantity;

    public Line() {}

    public UUID getProductId() {
      return productId;
    }

    public void setProductId(UUID productId) {
      this.productId = productId;
    }

    public Integer getQuantity() {
      return quantity;
    }

    public void setQuantity(Integer quantity) {
      this.quantity = quantity;
    }
  }
}
