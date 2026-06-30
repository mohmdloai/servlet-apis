package com.loai.inventory.domain.model;

/**
 * Lifecycle of a {@link ProductListing}. Constant names must match the Postgres {@code
 * listing_status} enum so the jOOQ round-trip ({@code valueOf(name())}) holds. Only {@link
 * #PUBLISHED} is storefront-visible.
 */
public enum ListingStatus {
  DRAFT,
  PUBLISHED,
  ARCHIVED
}
