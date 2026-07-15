package com.loai.inventory.service;

import com.loai.inventory.common.Pagination;
import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.ListingReview;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.model.ReviewStatus;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.ListingReviewRepository;
import com.loai.inventory.domain.repository.ListingReviewRepository.AdminReview;
import com.loai.inventory.domain.repository.ListingReviewRepository.MyReview;
import com.loai.inventory.domain.repository.ListingReviewRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Verified-purchase listing reviews across the three planes (slice R1, {@code
 * stories/storefront_reviews.md}): the portal-authenticated write gated on a DELIVERED fulfillment
 * line for the listing's product, the staff moderation worklist (PENDING → APPROVED | REJECTED),
 * and the anonymous PUBLISHED-scoped public read. One review per {@code (customer, listing)} — a
 * resubmission overwrites and returns the row to PENDING (edit = re-moderation). Every public
 * review is "verified purchase" by construction, not assertion (epic §2).
 */
public class ListingReviewService {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;
  static final int BODY_MAX_LENGTH = 2000;

  /** The cause-naming 403 for a submit without a delivered purchase (epic §2, AC1). */
  static final String NOT_ELIGIBLE_MESSAGE = "You can review items after they're delivered";

  private final DSLContext rootDsl;
  private final ListingReviewRepositoryFactory reviewRepoFactory;
  private final ProductListingRepositoryFactory listingRepoFactory;
  private final CustomerRepositoryFactory customerRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;

  public ListingReviewService(
      DSLContext rootDsl,
      ListingReviewRepositoryFactory reviewRepoFactory,
      ProductListingRepositoryFactory listingRepoFactory,
      CustomerRepositoryFactory customerRepoFactory,
      OrgRepositoryFactory orgRepoFactory) {
    this.rootDsl = rootDsl;
    this.reviewRepoFactory = reviewRepoFactory;
    this.listingRepoFactory = listingRepoFactory;
    this.customerRepoFactory = customerRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
  }

  /**
   * A submit's outcome: the stored review + whether it was freshly created (201) or an edit (200).
   */
  public record Submitted(ListingReview review, String listingSlug, boolean created) {}

  /**
   * Submit (or edit — the upsert on the {@code (customer, listing)} unique key) a review as the
   * session customer. Listing resolved by slug in-org, any status (a delivered item stays
   * reviewable after unpublishing); unknown slug → opaque 404. No delivered fulfillment line for
   * the listing's product → 403 with the cause-naming message. Rating outside 1–5 or body over
   * {@value #BODY_MAX_LENGTH} chars → 400. {@code display_name} is frozen from the customer row at
   * every write; an edit resets the status to PENDING (re-moderation) and never grows the row
   * count.
   */
  public Submitted submit(
      UUID orgId, UUID customerId, String listingSlug, Integer rating, String body) {
    if (listingSlug == null || listingSlug.isBlank()) {
      throw new ValidationException("listing_slug is required");
    }
    if (rating == null || rating < 1 || rating > 5) {
      throw new ValidationException("rating must be between 1 and 5");
    }
    if (body != null && body.length() > BODY_MAX_LENGTH) {
      throw new ValidationException("body must be at most " + BODY_MAX_LENGTH + " characters");
    }
    String storedBody = (body == null || body.isEmpty()) ? null : body;

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ProductListing listing =
              listingRepoFactory
                  .create(txDsl)
                  .findBySlug(orgId, listingSlug.trim())
                  .orElseThrow(() -> new NotFoundException("Listing not found: " + listingSlug));
          ListingReviewRepository reviews = reviewRepoFactory.create(txDsl);
          if (!reviews.hasDeliveredProduct(orgId, customerId, listing.getProductId())) {
            throw new AuthorizationException(NOT_ELIGIBLE_MESSAGE);
          }
          String displayName = freezeDisplayName(txDsl, orgId, customerId);

          ListingReview existing =
              reviews.findByCustomerAndListing(orgId, customerId, listing.getId()).orElse(null);
          if (existing == null) {
            ListingReview fresh = new ListingReview();
            fresh.setOrgId(orgId);
            fresh.setProductListingId(listing.getId());
            fresh.setCustomerId(customerId);
            fresh.setRating(rating);
            fresh.setBody(storedBody);
            fresh.setDisplayName(displayName);
            return new Submitted(reviews.insert(fresh), listing.getSlug(), true);
          }
          existing.setRating(rating);
          existing.setBody(storedBody);
          existing.setDisplayName(displayName);
          return new Submitted(reviews.updateContent(existing), listing.getSlug(), false);
        });
  }

  /** The customer's own reviews, newest first, with listing slug + title (My reviews). */
  public List<MyReview> myReviews(UUID orgId, UUID customerId) {
    return reviewRepoFactory.create(rootDsl).findMine(orgId, customerId);
  }

  /** Delete the customer's own review. Foreign or unknown id → the same opaque 404 (P2 pattern). */
  public void deleteOwn(UUID orgId, UUID customerId, UUID reviewId) {
    int deleted = reviewRepoFactory.create(rootDsl).deleteOwn(orgId, customerId, reviewId);
    if (deleted == 0) {
      throw new NotFoundException("Review not found: " + reviewId);
    }
  }

  // ───────── staff moderation ─────────

  /** One page of the staff worklist. */
  public record AdminPage(List<AdminReview> items, long total) {}

  /**
   * The staff moderation read: {@code status} filtered = queue oldest-first, unfiltered = ledger
   * newest-first (the worklist convention). An unknown status string → 400, never a silent default.
   */
  public AdminPage adminList(UUID orgId, String statusRaw, int page, int size) {
    ReviewStatus status = parseStatus(statusRaw);
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    ListingReviewRepository reviews = reviewRepoFactory.create(rootDsl);
    return new AdminPage(
        reviews.findAdminPage(orgId, status, Pagination.offset(p, s), s),
        reviews.countAdmin(orgId, status));
  }

  /** Approve or reject one review (STAFF). Unknown id in-org → 404. */
  public ListingReview moderate(UUID orgId, UUID reviewId, ReviewStatus decision) {
    if (decision != ReviewStatus.APPROVED && decision != ReviewStatus.REJECTED) {
      throw new ValidationException("decision must be APPROVED or REJECTED");
    }
    ListingReviewRepository reviews = reviewRepoFactory.create(rootDsl);
    if (reviews.updateStatus(orgId, reviewId, decision) == 0) {
      throw new NotFoundException("Review not found: " + reviewId);
    }
    return reviews
        .findById(orgId, reviewId)
        .orElseThrow(() -> new NotFoundException("Review not found: " + reviewId));
  }

  // ───────── public read ─────────

  /** One public page of a listing's APPROVED reviews + the total (drives the pager). */
  public record PublicPage(List<ListingReview> items, long total, int page, int size) {}

  /**
   * The anonymous public read: {@code listingSlug} resolves through the <b>same PUBLISHED-only
   * resolution as the listing read</b> (never a bare slug lookup), so reviews on a DRAFT/ARCHIVED
   * listing are unreachable by construction. APPROVED only, newest first, paged.
   */
  public PublicPage publicPage(String orgSlug, String listingSlug, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    UUID orgId = resolveOrg(orgSlug).getId();
    ProductListing listing =
        listingRepoFactory
            .create(rootDsl)
            .findBySlugAndStatus(orgId, listingSlug, ListingStatus.PUBLISHED)
            .orElseThrow(() -> new NotFoundException("Listing not found: " + listingSlug));
    ListingReviewRepository reviews = reviewRepoFactory.create(rootDsl);
    return new PublicPage(
        reviews.findApprovedPage(orgId, listing.getId(), Pagination.offset(p, s), s),
        reviews.countApproved(orgId, listing.getId()),
        p,
        s);
  }

  // ───────── helpers ─────────

  /**
   * The frozen public author name (epic §6 — the invoice {@code customer_name} snapshot precedent):
   * the customer row's name at write time, falling back to a neutral label when the CRM record has
   * none. The public row never joins back to the customer.
   */
  private String freezeDisplayName(DSLContext txDsl, UUID orgId, UUID customerId) {
    Customer customer =
        customerRepoFactory
            .create(txDsl)
            .findById(orgId, customerId)
            .orElseThrow(() -> new NotFoundException("Customer not found: " + customerId));
    String name = customer.getName();
    return name == null || name.isBlank() ? "Customer" : name.trim();
  }

  private static ReviewStatus parseStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return ReviewStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Unknown status: " + raw);
    }
  }

  private Org resolveOrg(String orgSlug) {
    return orgRepoFactory
        .create(rootDsl)
        .findBySlug(orgSlug)
        .filter(Org::isActive)
        .orElseThrow(() -> new NotFoundException("Storefront not found: " + orgSlug));
  }
}
