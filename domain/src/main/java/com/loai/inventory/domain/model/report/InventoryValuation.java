package com.loai.inventory.domain.model.report;

import java.math.BigDecimal;

/**
 * Point-in-time inventory valuation ({@code GET /reports/inventory-valuation}) over tracked
 * products (those with an {@code inventory} row) in one org.
 *
 * <p><b>{@code retailValue} is retail-basis on purpose.</b> {@code product} has no cost column, so
 * the only honest valuation is {@code Σ stock_qty × base_price} (selling price). The field is named
 * {@code retail_value} — never {@code valuation} or {@code cogs} — so the basis is unmistakable. A
 * cost-basis figure is a schema follow-up ({@code product.cost_price}). See {@code
 * stories/reporting_reads.md} §G5.
 */
public record InventoryValuation(
    long trackedProducts, long totalUnits, BigDecimal retailValue, long outOfStock) {}
