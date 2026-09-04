package com.loai.inventory.domain.model.report;

/**
 * Sort key of {@code GET /reports/top-products?by=}. {@code PROFIT} orders {@code gross_profit DESC
 * NULLS LAST} — uncosted products rank last, never hidden (a sort must not shrink the list) — and
 * is MANAGER-plane: the handler refuses it from a caller without manager authority before the query
 * runs (stories/product_cost_and_margin.md).
 */
public enum TopProductSort {
  REVENUE,
  QUANTITY,
  PROFIT
}
