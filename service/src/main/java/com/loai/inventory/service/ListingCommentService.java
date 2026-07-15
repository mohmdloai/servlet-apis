package com.loai.inventory.service;

import com.loai.inventory.common.Pagination;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.CommentStatus;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.ListingComment;
import com.loai.inventory.domain.model.ListingStatus;
import com.loai.inventory.domain.model.NotificationRecipient;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.ProductListing;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.ListingCommentRepository;
import com.loai.inventory.domain.repository.ListingCommentRepository.AdminComment;
import com.loai.inventory.domain.repository.ListingCommentRepository.MyComment;
import com.loai.inventory.domain.repository.ListingCommentRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.ProductListingRepositoryFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Listing comments across the three planes (slice R2, {@code stories/storefront_comments.md}): a
 * logged-in customer asks (no purchase required — epic §2), the merchant answers from a staff
 * worklist — which simultaneously publishes the Q&amp;A pair and raises a {@code COMMENT_REPLIED}
 * notification inside the reply txn (first reply only) — and the anonymous public read serves
 * ANSWERED pairs on PUBLISHED listings. Dismissal is silent and terminal (epic §4: nothing is
 * public until the merchant acts; answering IS the moderation act).
 */
public class ListingCommentService {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;
  static final int BODY_MAX_LENGTH = 1000;
  static final int REPLY_MAX_LENGTH = 2000;

  /** Flood control per (customer, listing): the 6th open question is a 400. */
  static final int MAX_PENDING_PER_LISTING = 5;

  private final DSLContext rootDsl;
  private final ListingCommentRepositoryFactory commentRepoFactory;
  private final ProductListingRepositoryFactory listingRepoFactory;
  private final CustomerRepositoryFactory customerRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;
  private final NotificationService notificationService;

  public ListingCommentService(
      DSLContext rootDsl,
      ListingCommentRepositoryFactory commentRepoFactory,
      ProductListingRepositoryFactory listingRepoFactory,
      CustomerRepositoryFactory customerRepoFactory,
      OrgRepositoryFactory orgRepoFactory,
      NotificationService notificationService) {
    this.rootDsl = rootDsl;
    this.commentRepoFactory = commentRepoFactory;
    this.listingRepoFactory = listingRepoFactory;
    this.customerRepoFactory = customerRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
    this.notificationService = notificationService;
  }

  /** A submit's outcome: the stored comment + its listing's public identity. */
  public record Submitted(ListingComment comment, String listingSlug, String listingTitle) {}

  /**
   * Submit a question/comment as the session customer — login is the only gate (pre-purchase
   * questions are the point, epic §2). Listing resolved by slug in-org (opaque 404); body
   * 1..{@value #BODY_MAX_LENGTH} (400, stored verbatim); more than {@value
   * #MAX_PENDING_PER_LISTING} open questions on one listing → 400. {@code display_name} frozen from
   * the customer row; lands PENDING — visible only to its author until the merchant answers.
   */
  public Submitted submit(UUID orgId, UUID customerId, String listingSlug, String body) {
    if (listingSlug == null || listingSlug.isBlank()) {
      throw new ValidationException("listing_slug is required");
    }
    if (body == null || body.isEmpty()) {
      throw new ValidationException("body is required");
    }
    if (body.length() > BODY_MAX_LENGTH) {
      throw new ValidationException("body must be at most " + BODY_MAX_LENGTH + " characters");
    }

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ProductListing listing =
              listingRepoFactory
                  .create(txDsl)
                  .findBySlug(orgId, listingSlug.trim())
                  .orElseThrow(() -> new NotFoundException("Listing not found: " + listingSlug));
          ListingCommentRepository comments = commentRepoFactory.create(txDsl);
          if (comments.countPending(orgId, customerId, listing.getId())
              >= MAX_PENDING_PER_LISTING) {
            throw new ValidationException(
                "You already have "
                    + MAX_PENDING_PER_LISTING
                    + " unanswered questions on this item — please wait for the store to reply");
          }
          ListingComment fresh = new ListingComment();
          fresh.setOrgId(orgId);
          fresh.setProductListingId(listing.getId());
          fresh.setCustomerId(customerId);
          fresh.setBody(body);
          fresh.setDisplayName(freezeDisplayName(txDsl, orgId, customerId));
          return new Submitted(comments.insert(fresh), listing.getSlug(), listing.getTitle());
        });
  }

  /** The customer's own comments, newest first, with listing slug + title (My questions). */
  public List<MyComment> myComments(UUID orgId, UUID customerId) {
    return commentRepoFactory.create(rootDsl).findMine(orgId, customerId);
  }

  /**
   * Delete the customer's own comment — deleting an ANSWERED one removes the public pair too (the
   * customer owns their words). Foreign or unknown id → the same opaque 404 (P2 pattern).
   */
  public void deleteOwn(UUID orgId, UUID customerId, UUID commentId) {
    int deleted = commentRepoFactory.create(rootDsl).deleteOwn(orgId, customerId, commentId);
    if (deleted == 0) {
      throw new NotFoundException("Comment not found: " + commentId);
    }
  }

  // ───────── staff worklist ─────────

  /** One page of the staff worklist. */
  public record AdminPage(List<AdminComment> items, long total) {}

  /**
   * The staff read: {@code status} filtered = queue oldest-first, unfiltered = ledger newest-first
   * (the worklist convention). An unknown status string → 400, never a silent default.
   */
  public AdminPage adminList(UUID orgId, String statusRaw, int page, int size) {
    CommentStatus status = parseStatus(statusRaw);
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    ListingCommentRepository comments = commentRepoFactory.create(rootDsl);
    return new AdminPage(
        comments.findAdminPage(orgId, status, Pagination.offset(p, s), s),
        comments.countAdmin(orgId, status));
  }

  /**
   * Reply to a comment (STAFF): a PENDING comment flips ANSWERED — which publishes the pair — and
   * raises exactly one {@code COMMENT_REPLIED} notification to the asker <b>inside the reply
   * txn</b> (the durable-row-is-the-guarantee rule); re-replying to an ANSWERED comment <em>edits
   * the answer</em> (allowed, stays ANSWERED, never re-notifies). A DISMISSED comment is terminal →
   * 409. Reply body 1..{@value #REPLY_MAX_LENGTH} (400). Unknown id in-org → 404.
   */
  public ListingComment reply(UUID orgId, UUID actorUserId, UUID commentId, String replyBody) {
    if (replyBody == null || replyBody.isEmpty()) {
      throw new ValidationException("body is required");
    }
    if (replyBody.length() > REPLY_MAX_LENGTH) {
      throw new ValidationException("body must be at most " + REPLY_MAX_LENGTH + " characters");
    }
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ListingCommentRepository comments = commentRepoFactory.create(txDsl);
          ListingComment comment =
              comments
                  .findById(orgId, commentId)
                  .orElseThrow(() -> new NotFoundException("Comment not found: " + commentId));
          if (comment.getStatus() == CommentStatus.DISMISSED) {
            throw new ConflictException("Comment was dismissed — dismissal is terminal");
          }
          boolean firstReply = comment.getStatus() == CommentStatus.PENDING;
          comment.setReplyBody(replyBody);
          comment.setRepliedBy(actorUserId);
          comment.setRepliedAt(OffsetDateTime.now(ZoneOffset.UTC));
          ListingComment saved = comments.updateReply(comment);
          if (firstReply) {
            ProductListing listing =
                listingRepoFactory
                    .create(txDsl)
                    .findById(orgId, saved.getProductListingId())
                    .orElseThrow(
                        () -> new NotFoundException("Listing", saved.getProductListingId()));
            notificationService.notify(
                txDsl,
                orgId,
                NotificationRecipient.customer(saved.getCustomerId()),
                NotificationType.COMMENT_REPLIED,
                Map.of("listing_slug", listing.getSlug(), "listing_title", listing.getTitle()),
                "listing_comment",
                saved.getId(),
                /* linkTarget= */ null);
          }
          return saved;
        });
  }

  /**
   * Dismiss a PENDING comment (STAFF): never public, no notification — silence, not
   * rejection-nagging. Terminal: only PENDING dismisses (an ANSWERED pair is already public, a
   * DISMISSED one already closed) → otherwise 409. Unknown id in-org → 404.
   */
  public ListingComment dismiss(UUID orgId, UUID commentId) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          ListingCommentRepository comments = commentRepoFactory.create(txDsl);
          ListingComment comment =
              comments
                  .findById(orgId, commentId)
                  .orElseThrow(() -> new NotFoundException("Comment not found: " + commentId));
          if (comment.getStatus() != CommentStatus.PENDING) {
            throw new ConflictException("Only a pending comment can be dismissed");
          }
          comments.updateStatus(orgId, commentId, CommentStatus.DISMISSED);
          comment.setStatus(CommentStatus.DISMISSED);
          return comment;
        });
  }

  // ───────── public read ─────────

  /** One public page of a listing's ANSWERED pairs + the total (drives the pager). */
  public record PublicPage(List<ListingComment> items, long total, int page, int size) {}

  /**
   * The anonymous public read: {@code listingSlug} resolves through the <b>same PUBLISHED-only
   * resolution as the listing read</b> (the R1 rule), so Q&amp;A on a DRAFT/ARCHIVED listing is
   * unreachable by construction. ANSWERED only, newest first, paged.
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
    ListingCommentRepository comments = commentRepoFactory.create(rootDsl);
    return new PublicPage(
        comments.findAnsweredPage(orgId, listing.getId(), Pagination.offset(p, s), s),
        comments.countAnswered(orgId, listing.getId()),
        p,
        s);
  }

  // ───────── helpers ─────────

  /** The frozen public author name (the R1/epic §6 rule) — never a join back to the customer. */
  private String freezeDisplayName(DSLContext txDsl, UUID orgId, UUID customerId) {
    Customer customer =
        customerRepoFactory
            .create(txDsl)
            .findById(orgId, customerId)
            .orElseThrow(() -> new NotFoundException("Customer not found: " + customerId));
    String name = customer.getName();
    return name == null || name.isBlank() ? "Customer" : name.trim();
  }

  private static CommentStatus parseStatus(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return CommentStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
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
