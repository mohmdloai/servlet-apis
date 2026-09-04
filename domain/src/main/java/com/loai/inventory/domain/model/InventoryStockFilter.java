package com.loai.inventory.domain.model;

/**
 * Server-side filter for the stock-overview list ({@code GET /inventory?stock=…}). A read-only
 * query value type (not persisted). Parsed from the {@code stock} query param by the api layer; an
 * unknown value is a 400 there, never reaching the repository.
 *
 * <ul>
 *   <li>{@link #OUT} — tracked and {@code available_qty == 0} (an untracked product is
 *       <i>unknown</i>, not <i>out</i>, so it is excluded).
 *   <li>{@link #LOW} — tracked and {@code available_qty <= low_lte} (the bound is client-supplied —
 *       one number for the whole shop, "your alert level").
 *   <li>{@link #REORDER} — tracked and {@code available_qty <= product.reorder_point} (the
 *       product's own rule, V94; products without one fall out). The replenishment worklist,
 *       ordered deepest-below first — the one segment that is a queue, not a catalog view.
 *   <li>{@link #TRACKED} — has an {@code inventory} row.
 *   <li>{@link #UNTRACKED} — no {@code inventory} row.
 * </ul>
 */
public enum InventoryStockFilter {
  OUT,
  LOW,
  REORDER,
  TRACKED,
  UNTRACKED
}
