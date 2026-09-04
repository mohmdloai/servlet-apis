package com.loai.inventory.domain.model.report;

import java.math.BigDecimal;

/**
 * Point-in-time inventory valuation ({@code GET /reports/inventory-valuation}) over tracked
 * products (those with an {@code inventory} row) in one org.
 *
 * <p><b>{@code retailValue} is retail-basis on purpose</b> — {@code Σ stock_qty × base_price}
 * (selling price) over every tracked product, named {@code retail_value} so the basis is
 * unmistakable. Since V92 ({@code stories/product_cost_and_margin.md}) the cost basis sits beside
 * it: {@code costValue} = {@code Σ stock_qty × cost_price} over the <b>costed</b> tracked products
 * only, {@code null} when none is costed (never {@code 0} — that would read "the shelf is worth
 * nothing"); {@code uncostedProducts} / {@code uncostedUnits} say how much of the shelf the cost
 * figure does not cover, and are always present. A client shows {@code retail − cost} as potential
 * profit only when {@code uncostedUnits == 0}, because across a partly costed shelf the two values
 * are over different sets. See {@code stories/reporting_reads.md} §G5.
 */
public record InventoryValuation(
    long trackedProducts,
    long totalUnits,
    BigDecimal retailValue,
    long outOfStock,
    BigDecimal costValue,
    long uncostedProducts,
    long uncostedUnits) {

  /** The pre-V92 shape: no tracked product is costed. */
  public InventoryValuation(
      long trackedProducts, long totalUnits, BigDecimal retailValue, long outOfStock) {
    this(trackedProducts, totalUnits, retailValue, outOfStock, null, trackedProducts, totalUnits);
  }
}
