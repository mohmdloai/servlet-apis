package com.loai.inventory.domain.model.report;

import java.math.BigDecimal;

/**
 * The window's cost of goods and gross profit in one row ({@code GET /reports/profit},
 * stories/product_cost_and_margin.md), summed over the same lines and the same expressions as the
 * per-product {@link TopProduct} cost figures — so Σ rows over an uncapped top-products read equals
 * this, by construction.
 *
 * <p>{@code quantity} is every unit sold in the window; {@code costedQuantity} the units whose line
 * carries a {@code unit_cost} snapshot. {@code costedNetSales} / {@code cost} / {@code grossProfit}
 * are over the costed lines only and {@code null} when {@code costedQuantity == 0}.
 */
public record ProfitTotals(
    long quantity,
    long costedQuantity,
    BigDecimal costedNetSales,
    BigDecimal cost,
    BigDecimal grossProfit) {}
