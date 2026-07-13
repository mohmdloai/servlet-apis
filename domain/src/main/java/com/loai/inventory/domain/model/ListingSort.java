package com.loai.inventory.domain.model;

/**
 * Sort order for the public storefront listings read ({@code GET /api/public/{orgSlug}/listings},
 * {@code stories/storefront_search_and_filters.md}, B3). A read-only query value type (not
 * persisted). Parsed from the {@code sort} query param by the service layer; an unknown value is a
 * 400 there — never a silent default — so it never reaches the repository.
 *
 * <ul>
 *   <li>{@link #NEWEST} — {@code published_at DESC} (the epic's recency field); the default.
 *   <li>{@link #PRICE_ASC} / {@link #PRICE_DESC} — {@code sales_price} ascending / descending.
 * </ul>
 *
 * <p>Every order is tie-broken by a stable key ({@code slug ASC} — unique per org, never serialized
 * as an internal id) so paging is deterministic and two listings published in the same instant
 * never straddle a page boundary.
 */
public enum ListingSort {
  NEWEST,
  PRICE_ASC,
  PRICE_DESC
}
