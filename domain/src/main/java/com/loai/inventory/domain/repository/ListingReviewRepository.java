package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.ListingReview;
import com.loai.inventory.domain.model.ReviewStatus;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for verified-purchase listing reviews (slice R1, {@code
 * stories/storefront_reviews.md}). Portal reads/writes are scoped to {@code (orgId, customerId)}
 * (both from the session token — cross-customer access is unrepresentable); staff reads are
 * org-scoped; the public read is listing-scoped and APPROVED-only. The verified-purchase gate
 * ({@link #hasDeliveredProduct}) lives here because it is a pure SQL EXISTS over the fulfillment
 * spine.
 */
public interface ListingReviewRepository {

  /** A customer's own review joined with its listing's public identity (My reviews). */
  record MyReview(ListingReview review, String listingSlug, String listingTitle) {}

  /** An admin worklist row: the review + the customer context staff already see (CRM). */
  record AdminReview(
      ListingReview review, String customerName, String customerEmail, String listingTitle) {}

  /** The APPROVED aggregate of one listing: average rating + count (absent when count is 0). */
  record Aggregate(BigDecimal average, long count) {}

  /**
   * The verified-purchase eligibility predicate (epic §2): does {@code customerId} have a DELIVERED
   * fulfillment line for {@code productId}? EXISTS over {@code fulfillment_line JOIN
   * sales_order_line (product_id) JOIN fulfillment (status = DELIVERED) JOIN sales_order (org_id,
   * customer_id)} — goods in hand, not merely placed or paid.
   */
  boolean hasDeliveredProduct(UUID orgId, UUID customerId, UUID productId);

  /** The customer's existing review of one listing, if any — the upsert pre-read. */
  Optional<ListingReview> findByCustomerAndListing(UUID orgId, UUID customerId, UUID listingId);

  ListingReview insert(ListingReview review);

  /**
   * Overwrite an existing review's content (rating, body, display_name), reset {@code status} to
   * PENDING (edit = re-moderation) and bump {@code updated_at}. Keyed by primary key.
   */
  ListingReview updateContent(ListingReview review);

  /** The customer's own reviews, newest first, each with its listing slug + title. */
  List<MyReview> findMine(UUID orgId, UUID customerId);

  /** Delete the customer's own review; returns rows deleted (0 → the caller's opaque 404). */
  int deleteOwn(UUID orgId, UUID customerId, UUID reviewId);

  /** One review in the org (staff moderation read). */
  Optional<ListingReview> findById(UUID orgId, UUID reviewId);

  /** Set a review's moderation status + bump {@code updated_at}; 0 rows → the caller's 404. */
  int updateStatus(UUID orgId, UUID reviewId, ReviewStatus status);

  /**
   * The staff worklist page: filtered = queue {@code created_at ASC} (oldest first), unfiltered =
   * ledger {@code created_at DESC} — the queue-vs-ledger convention.
   */
  List<AdminReview> findAdminPage(UUID orgId, ReviewStatus status, int offset, int limit);

  long countAdmin(UUID orgId, ReviewStatus status);

  /** The public page of one listing's APPROVED reviews, newest first. */
  List<ListingReview> findApprovedPage(UUID orgId, UUID listingId, int offset, int limit);

  long countApproved(UUID orgId, UUID listingId);

  /**
   * APPROVED rating aggregates for a set of listings in one grouped query, keyed by listing id.
   * Listings with no APPROVED review are simply absent from the map (absent, never zero-fabricated
   * — epic §8/§10).
   */
  Map<UUID, Aggregate> findAggregates(UUID orgId, Collection<UUID> listingIds);
}
