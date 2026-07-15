package com.loai.inventory.domain.repository;

import com.loai.inventory.domain.model.CommentStatus;
import com.loai.inventory.domain.model.ListingComment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for listing comments (slice R2, {@code stories/storefront_comments.md}). Portal
 * reads/writes are {@code (orgId, customerId)}-scoped; staff reads are org-scoped; the public read
 * is listing-scoped and ANSWERED-only. State transitions (reply/dismiss guards, the notification)
 * are the service's job — this layer persists.
 */
public interface ListingCommentRepository {

  /** A customer's own comment joined with its listing's public identity (My questions). */
  record MyComment(ListingComment comment, String listingSlug, String listingTitle) {}

  /** An admin worklist row: the comment + the customer context staff already see (CRM). */
  record AdminComment(
      ListingComment comment, String customerName, String customerEmail, String listingTitle) {}

  /** The customer's open questions on one listing — the flood-control cap's input. */
  long countPending(UUID orgId, UUID customerId, UUID listingId);

  ListingComment insert(ListingComment comment);

  /** The customer's own comments, newest first, each with its listing slug + title. */
  List<MyComment> findMine(UUID orgId, UUID customerId);

  /** Delete the customer's own comment; returns rows deleted (0 → the caller's opaque 404). */
  int deleteOwn(UUID orgId, UUID customerId, UUID commentId);

  Optional<ListingComment> findById(UUID orgId, UUID commentId);

  /**
   * Persist a reply (first answer or an edit): {@code reply_body}, {@code replied_by}, {@code
   * replied_at}, status ANSWERED. Keyed by primary key (the service already loaded + guarded).
   */
  ListingComment updateReply(ListingComment comment);

  /** Set the status alone (the dismiss transition); 0 rows → the caller's 404. */
  int updateStatus(UUID orgId, UUID commentId, CommentStatus status);

  /**
   * The staff worklist page: filtered = queue {@code created_at ASC}, unfiltered = ledger {@code
   * created_at DESC} — the queue-vs-ledger convention.
   */
  List<AdminComment> findAdminPage(UUID orgId, CommentStatus status, int offset, int limit);

  long countAdmin(UUID orgId, CommentStatus status);

  /** The public page of one listing's ANSWERED pairs, newest first. */
  List<ListingComment> findAnsweredPage(UUID orgId, UUID listingId, int offset, int limit);

  long countAnswered(UUID orgId, UUID listingId);
}
