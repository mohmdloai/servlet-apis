package com.loai.inventory.service;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.repository.CustomerWishlistRepository;
import com.loai.inventory.domain.repository.CustomerWishlistRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The customer wishlist (roadmap item 3, {@code stories/customer_wishlist.md}) — a logged-in
 * shopper's saved-for-later list, and the hook later re-marketing (back-in-stock, "still
 * interested?") will hang off.
 *
 * <p>Three decisions shape this service:
 *
 * <ul>
 *   <li><b>Slug-only wire.</b> The listing's internal id never crosses the boundary in either
 *       direction; writes resolve slug → id here, reads hand back the card DTO the storefront
 *       already serves. That keeps the portal plane's no-leak rule intact by construction.
 *   <li><b>Resolve at any status, serve only PUBLISHED.</b> The write follows the review-write
 *       precedent and accepts a listing in any status, so a heart placed moments before the
 *       merchant unpublishes still lands. The read then filters to PUBLISHED, so the row goes quiet
 *       and returns on republish — nothing is destroyed behind the customer's back.
 *   <li><b>Everything is idempotent.</b> Adding twice is one row and a 204; removing something that
 *       was never saved is a 204. The frontend replays a guest's saved slugs into the account on
 *       every login, so "already there" is the normal case, not an error to report.
 * </ul>
 *
 * <p>Identity is always the caller's {@code (orgId, customerId)} from the session principal, passed
 * in by the servlet — never a URL or body value.
 */
public class WishlistService {

  /**
   * The most listings one customer may save. The read is unpaginated by design (a wishlist is a
   * handful of items, and paging it would be ceremony), so the cap is what keeps that read bounded
   * — it is the pagination, stated as a limit.
   */
  static final int MAX_ITEMS = 200;

  static final String FULL_MESSAGE = "Your wishlist is full";

  private final DSLContext rootDsl;
  private final CustomerWishlistRepositoryFactory wishlistRepoFactory;
  private final ProductListingRepositoryFactory listingRepoFactory;
  private final StorefrontService storefrontService;

  public WishlistService(
      DSLContext rootDsl,
      CustomerWishlistRepositoryFactory wishlistRepoFactory,
      ProductListingRepositoryFactory listingRepoFactory,
      StorefrontService storefrontService) {
    this.rootDsl = rootDsl;
    this.wishlistRepoFactory = wishlistRepoFactory;
    this.listingRepoFactory = listingRepoFactory;
    this.storefrontService = storefrontService;
  }

  /**
   * Save a listing for the session customer. Unknown slug → opaque 404 (the login-merge replay
   * ignores it — a slug may have been deleted since the guest hearted it). At {@value #MAX_ITEMS}
   * saved rows the next add is a 400; an add that is already saved is not, since it grows nothing.
   */
  public void add(UUID orgId, UUID customerId, String listingSlug) {
    UUID listingId = resolveListingId(orgId, listingSlug);
    CustomerWishlistRepository wishlist = wishlistRepoFactory.create(rootDsl);
    // The cap refuses *growth*, not the call: re-hearting an already-saved listing while at the cap
    // adds no row, so failing it would break idempotence for exactly the customer who hit the
    // limit. (The count/insert pair isn't locked — the cap is a bound on the read, not an
    // invariant, so a concurrent double-add landing at 201 is harmless.)
    if (wishlist.count(orgId, customerId) >= MAX_ITEMS
        && !wishlist.findListingIds(orgId, customerId).contains(listingId)) {
      throw new ValidationException(FULL_MESSAGE);
    }
    wishlist.add(orgId, customerId, listingId);
  }

  /**
   * Remove a saved listing. Idempotent: an already-removed (or never-saved) listing is still a
   * success — only a slug that resolves to no listing at all is a 404.
   */
  public void remove(UUID orgId, UUID customerId, String listingSlug) {
    UUID listingId = resolveListingId(orgId, listingSlug);
    wishlistRepoFactory.create(rootDsl).remove(orgId, customerId, listingId);
  }

  /**
   * The customer's saved listings as storefront cards, newest-saved first, PUBLISHED-only. Bounded
   * by {@link #MAX_ITEMS}, so it is deliberately unpaginated.
   */
  public List<StorefrontService.ListingView> list(UUID orgId, UUID customerId, String locale) {
    List<UUID> ids = wishlistRepoFactory.create(rootDsl).findListingIds(orgId, customerId);
    return storefrontService.publishedViewsByIds(orgId, ids, locale);
  }

  /** Resolve the wire slug to a listing id in-org, at any status. Unknown → opaque 404. */
  private UUID resolveListingId(UUID orgId, String listingSlug) {
    if (listingSlug == null || listingSlug.isBlank()) {
      throw new ValidationException("listing_slug is required");
    }
    ProductListing listing =
        listingRepoFactory
            .create(rootDsl)
            .findBySlug(orgId, listingSlug.trim())
            .orElseThrow(() -> new NotFoundException("Listing not found: " + listingSlug));
    return listing.getId();
  }
}
