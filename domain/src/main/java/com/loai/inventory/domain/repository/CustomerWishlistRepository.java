package com.loai.inventory.domain.repository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for the customer wishlist (roadmap item 3, {@code stories/customer_wishlist.md}).
 *
 * <p>The whole table is a set of {@code (customer, listing)} pairs, so every method here is
 * naturally idempotent: adding twice is one row, removing something that was never there is a
 * no-op. That is deliberate — the frontend replays a guest's saved slugs into the account on every
 * login, and a merge that had to be exactly-once would need coordination the client cannot offer.
 *
 * <p>Rows are stored against <em>any</em> listing status; only the read resolves them through the
 * PUBLISHED catalog. A heart placed moments before the merchant unpublishes is kept, silently stops
 * serving, and comes back on republish.
 */
public interface CustomerWishlistRepository {

  /**
   * The customer's saved listing ids, newest-saved first (the owner index's order). Ids only — the
   * caller resolves them through the storefront read path, which is what enforces PUBLISHED-only.
   */
  List<UUID> findListingIds(UUID orgId, UUID customerId);

  /** How many listings this customer has saved — the cap guard's input. */
  int count(UUID orgId, UUID customerId);

  /**
   * Save a listing, {@code ON CONFLICT DO NOTHING} on the {@code (customer, listing)} unique pair.
   *
   * @return true when a row was actually inserted, false when it was already saved.
   */
  boolean add(UUID orgId, UUID customerId, UUID productListingId);

  /**
   * Remove one saved listing. Returns the number of rows deleted (0 when it wasn't saved) — the
   * caller answers 204 either way; a wishlist removal has no failure worth reporting.
   */
  int remove(UUID orgId, UUID customerId, UUID productListingId);
}
