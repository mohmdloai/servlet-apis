package com.loai.inventory.domain.model;

/**
 * Server-side filter for the stock-overview list ({@code GET /inventory?stock=…}). A read-only
 * query value type (not persisted). Parsed from the {@code stock} query param by the api layer; an
 * unknown value is a 400 there, never reaching the repository.
 *
 * <ul>
 *   <li>{@link #OUT} — tracked and {@code available_qty == 0} (an untracked product is
 *       <i>unknown</i>, not <i>out</i>, so it is excluded).
 *   <li>{@link #LOW} — tracked and {@code available_qty <= low_lte} (the bound is client-supplied;
 *       the backend has no low-stock threshold column).
 *   <li>{@link #TRACKED} — has an {@code inventory} row.
 *   <li>{@link #UNTRACKED} — no {@code inventory} row.
 * </ul>
 */
public enum InventoryStockFilter {
  OUT,
  LOW,
  TRACKED,
  UNTRACKED
}
