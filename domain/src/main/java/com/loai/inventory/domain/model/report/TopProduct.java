package com.loai.inventory.domain.model.report;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One ranked row of the top-products report ({@code GET /reports/top-products}) — a product's total
 * quantity sold and revenue over the window, from {@code sales_order_line} joined to
 * money-committed orders. The live {@code name}/{@code sku} are safe to carry because product
 * deletion is FK-blocked while order lines reference it. See {@code stories/reporting_reads.md}
 * §G3.
 *
 * <p>The cost figures (stories/product_cost_and_margin.md) are sums over the <b>costed</b> lines
 * only — those with a {@code unit_cost} snapshot: {@code costedQuantity} always; {@code
 * costedNetSales} (ex-tax line subtotal minus the order discount prorated by subtotal share),
 * {@code cost} ({@code Σ quantity × unit_cost}) and {@code grossProfit} (the difference) are {@code
 * null} when {@code costedQuantity == 0}, so a product nobody costed never reads "made nothing".
 * {@code revenue} stays the tax-inclusive {@code Σ line_total} it always was. The client's margin
 * is {@code grossProfit / costedNetSales} — numerator and denominator over the same lines.
 */
public record TopProduct(
    UUID productId,
    String name,
    String sku,
    long quantity,
    BigDecimal revenue,
    long costedQuantity,
    BigDecimal costedNetSales,
    BigDecimal cost,
    BigDecimal grossProfit) {

  /** The pre-V92 shape — an uncosted row: nothing sold under a cost snapshot. */
  public TopProduct(UUID productId, String name, String sku, long quantity, BigDecimal revenue) {
    this(productId, name, sku, quantity, revenue, 0L, null, null, null);
  }
}
