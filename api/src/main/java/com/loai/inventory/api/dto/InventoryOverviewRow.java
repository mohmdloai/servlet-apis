package com.loai.inventory.api.dto;

import com.loai.inventory.domain.repository.InventoryRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of the stock-overview list ({@code GET /inventory}). A product LEFT JOINed to its
 * inventory row: an <b>untracked</b> product (no inventory row) has {@code tracked=false} and null
 * {@code stock_qty} / {@code reserved_qty} / {@code available_qty} / {@code version} / {@code
 * updated_at}. The global ObjectMapper omits null fields, so an untracked row carries {@code
 * tracked:false} and simply omits the stock keys — the frontend keys off the explicit {@code
 * tracked} flag, never a zero or a present/absent key. {@code base_price} may be null if the
 * product allows it.
 */
public class InventoryOverviewRow {

  private UUID productId;
  private String name;
  private String sku;
  private BigDecimal basePrice;
  private boolean tracked;
  private Integer stockQty;
  private Integer reservedQty;
  private Integer availableQty;
  private Long version;
  private OffsetDateTime updatedAt;

  private InventoryOverviewRow() {}

  public static InventoryOverviewRow from(InventoryRepository.OverviewRow row) {
    InventoryOverviewRow r = new InventoryOverviewRow();
    r.productId = row.productId();
    r.name = row.name();
    r.sku = row.sku();
    r.basePrice = row.basePrice();
    r.tracked = row.tracked();
    r.stockQty = row.stockQty();
    r.reservedQty = row.reservedQty();
    r.availableQty = row.availableQty();
    r.version = row.version();
    r.updatedAt = row.updatedAt();
    return r;
  }

  public UUID getProductId() {
    return productId;
  }

  public String getName() {
    return name;
  }

  public String getSku() {
    return sku;
  }

  public BigDecimal getBasePrice() {
    return basePrice;
  }

  public boolean isTracked() {
    return tracked;
  }

  public Integer getStockQty() {
    return stockQty;
  }

  public Integer getReservedQty() {
    return reservedQty;
  }

  public Integer getAvailableQty() {
    return availableQty;
  }

  public Long getVersion() {
    return version;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
