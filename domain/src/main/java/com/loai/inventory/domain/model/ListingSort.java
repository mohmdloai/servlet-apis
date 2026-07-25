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
 *   <li>{@link #FEATURED} — {@code featured_sort ASC}, the merchant's curated order (slice C3).
 *       This one is <b>internal-only</b>: it is never a wire value (no {@code ?sort=featured}) —
 *       the service selects it as the default order when {@code ?featured=true} is present and no
 *       explicit {@code ?sort=} overrides it. So a typo'd {@code ?sort=} is still a 400.
 *   <li>{@link #COLLECTION} — {@code collection_listing.sort ASC}, the merchant's curated order
 *       inside one named collection (roadmap item 8). Internal-only for the same reason as {@link
 *       #FEATURED}: never a wire value, selected as the default order when {@code
 *       ?collection={slug}} is present and no explicit {@code ?sort=} overrides it. Meaningless —
 *       and never selected — without a collection narrow, since there is no membership row to sort
 *       by.
 *   <li>{@link #BEST_SELLING} — units actually sold over a rolling 30-day window, descending
 *       ({@code stories/storefront_best_sellers.md}, roadmap item 4). Unlike {@link #FEATURED} this
 *       <b>is</b> a wire value ({@code ?sort=best_selling}). The ranking is a SQL aggregate over
 *       money-committed order lines, so it composes with paging; never-sold listings rank last
 *       (they are ordered, not hidden — narrowing to sellers is the separate {@code ?sold=true}
 *       predicate).
 * </ul>
 *
 * <p>Every order is tie-broken by a stable key ({@code slug ASC} — unique per org, never serialized
 * as an internal id) so paging is deterministic and two listings published in the same instant
 * never straddle a page boundary.
 */
public enum ListingSort {
  NEWEST,
  PRICE_ASC,
  PRICE_DESC,
  FEATURED,
  COLLECTION,
  BEST_SELLING
}
