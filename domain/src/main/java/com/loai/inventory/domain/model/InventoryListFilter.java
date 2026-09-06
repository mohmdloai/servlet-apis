package com.loai.inventory.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The stock overview's read predicate ({@code stories/inventory_filters.md}) — every dimension the
 * {@code GET /inventory} list accepts, in one value so the rows, their total and the stock summary
 * are computed from exactly the same WHERE.
 *
 * <ul>
 *   <li>{@code q} — free text: product name (case-insensitive, plus the Arabic-folded {@code
 *       name_search} key), SKU, or an exact barcode. Trimmed by the caller; null or blank is
 *       absent.
 *   <li>{@code stock} — the tab ({@link InventoryStockFilter}); {@code lowLte} is the LOW bound.
 *   <li>{@code categoryId} — products whose storefront listing (or whose variant's listing) is in
 *       this category. A product with no listing is in no category.
 *   <li>{@code held} — {@code reserved_qty > 0} (true) / {@code = 0} (false). Untracked rows fall
 *       out either way: there is no reserved figure to compare.
 *   <li>{@code rule} — the product has a reorder point / has none. Applies to untracked rows too;
 *       the rule lives on the product.
 *   <li>{@code changedFrom} / {@code changedTo} — half-open {@code [from, to)} on {@code
 *       inventory.updated_at}, which every restock, sale, hold and release stamps. Untracked rows
 *       fall out.
 *   <li>{@code sort} — null keeps the server's order: name ASC, except the REORDER tab's
 *       deepest-below-its-point-first.
 * </ul>
 */
public record InventoryListFilter(
    String q,
    InventoryStockFilter stock,
    Integer lowLte,
    UUID categoryId,
    Boolean held,
    ReorderRule rule,
    OffsetDateTime changedFrom,
    OffsetDateTime changedTo,
    Sort sort) {

  /** Whether the product carries a reorder point (V94). */
  public enum ReorderRule {
    SET,
    NONE
  }

  /** The explicit orders a caller may ask for; each breaks ties on (name, id). */
  public enum Sort {
    /** {@code name ASC} — the catalog order, the default. */
    NAME,
    /** {@code available ASC}, untracked last — the emptiest shelf first. */
    AVAILABLE,
    /** {@code stock_qty DESC}, untracked last — the fullest shelf first. */
    ON_HAND,
    /** {@code updated_at DESC}, untracked last — what moved most recently. */
    UPDATED
  }

  /** The pre-slice read: search + tab + LOW bound. */
  public static InventoryListFilter of(String q, InventoryStockFilter stock, Integer lowLte) {
    return new InventoryListFilter(q, stock, lowLte, null, null, null, null, null, null);
  }

  /** The unfiltered catalog. */
  public static InventoryListFilter none() {
    return of(null, null, null);
  }

  public boolean hasQuery() {
    return q != null && !q.isBlank();
  }

  /** The same filter with the LOW bound resolved (the service defaults a missing one). */
  public InventoryListFilter withLowLte(Integer bound) {
    return new InventoryListFilter(
        q, stock, bound, categoryId, held, rule, changedFrom, changedTo, sort);
  }
}
