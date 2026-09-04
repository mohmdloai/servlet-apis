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

  /** MANAGER-plane (V92): written only with manager authority and a costed product, else absent. */
  private BigDecimal costPrice;

  /** The product's reorder point (V94); absent when it has none. */
  private Integer reorderPoint;

  private boolean tracked;
  private Integer stockQty;
  private Integer reservedQty;
  private Integer availableQty;
  private Long version;
  private OffsetDateTime updatedAt;
  private String imageUrl;

  private InventoryOverviewRow() {}

  public static InventoryOverviewRow from(InventoryRepository.OverviewRow row) {
    return from(row, null);
  }

  /**
   * As {@link #from(InventoryRepository.OverviewRow)} plus a short-lived presigned thumbnail URL
   * for the product's storefront listing image (the stock-overview / out-of-stock thumbnail).
   * {@code null} when the product has no listing image — the global ObjectMapper omits the key and
   * the frontend renders a placeholder.
   */
  public static InventoryOverviewRow from(InventoryRepository.OverviewRow row, String imageUrl) {
    return from(row, imageUrl, false);
  }

  /**
   * @param costVisible the caller's manager-authority decision
   *     (stories/product_cost_and_margin.md); without it the cost key never crosses, whatever the
   *     row holds.
   */
  public static InventoryOverviewRow from(
      InventoryRepository.OverviewRow row, String imageUrl, boolean costVisible) {
    InventoryOverviewRow r = new InventoryOverviewRow();
    r.productId = row.productId();
    r.name = row.name();
    r.sku = row.sku();
    r.basePrice = row.basePrice();
    r.costPrice = costVisible ? row.costPrice() : null;
    r.reorderPoint = row.reorderPoint();
    r.tracked = row.tracked();
    r.stockQty = row.stockQty();
    r.reservedQty = row.reservedQty();
    r.availableQty = row.availableQty();
    r.version = row.version();
    r.updatedAt = row.updatedAt();
    r.imageUrl = imageUrl;
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

  public Integer getReorderPoint() {
    return reorderPoint;
  }

  public BigDecimal getCostPrice() {
    return costPrice;
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

  public String getImageUrl() {
    return imageUrl;
  }
}
