package com.loai.inventory.domain.model.report;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One ranked row of the top-products report ({@code GET /reports/top-products}) — a product's total
 * quantity sold and revenue over the window, from {@code sales_order_line} joined to
 * money-committed orders. The live {@code name}/{@code sku} are safe to carry because product
 * deletion is FK-blocked while order lines reference it. See {@code stories/reporting_reads.md}
 * §G3.
 */
public record TopProduct(
    UUID productId, String name, String sku, long quantity, BigDecimal revenue) {}
