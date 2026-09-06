package com.loai.inventory.domain.model;

import java.math.BigDecimal;

/**
 * The stock overview's one-line answer for a filtered set ({@code stories/inventory_filters.md}):
 * how many products matched, how many units they hold, and what those units cost. Computed from the
 * same predicate as the rows, so the line and the list can never disagree.
 *
 * @param products every matching row, untracked included (the pager's total)
 * @param unitsOnHand Σ {@code stock_qty} over the tracked rows
 * @param unitsAvailable Σ {@code stock_qty − reserved_qty} over the tracked rows
 * @param costedProducts tracked rows that carry a {@code cost_price} — the honesty qualifier for
 *     {@code costValue} when it is fewer than {@code products}
 * @param costValue Σ {@code stock_qty × cost_price} over the costed rows, scale 2; {@code 0.00}
 *     when none is costed
 */
public record InventoryListStats(
    long products,
    long unitsOnHand,
    long unitsAvailable,
    long costedProducts,
    BigDecimal costValue) {

  public static InventoryListStats empty() {
    return new InventoryListStats(0, 0, 0, 0, BigDecimal.ZERO.setScale(2));
  }
}
