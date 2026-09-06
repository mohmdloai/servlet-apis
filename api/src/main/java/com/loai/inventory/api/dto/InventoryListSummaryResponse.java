package com.loai.inventory.api.dto;

import java.math.BigDecimal;

/**
 * The shelf the whole filtered set adds up to, riding on the inventory list envelope ({@code
 * stories/inventory_filters.md}): {@code products} = every matching row (the pager's total,
 * untracked included); {@code units_on_hand} / {@code units_available} = Σ over the tracked rows;
 * {@code costed_products} = tracked rows with a cost price; {@code cost_value} = Σ {@code stock_qty
 * × cost_price} over those rows, scale 2 — present only for a caller with manager authority (the
 * {@code cost_price} rule of {@code stories/product_cost_and_margin.md}); the null-omission mapper
 * drops the key otherwise. The worklist's "4 products match · 209 on hand · EGP 6,120.00 at cost"
 * line is one read, never a second request.
 */
public record InventoryListSummaryResponse(
    long products,
    long unitsOnHand,
    long unitsAvailable,
    long costedProducts,
    BigDecimal costValue) {}
