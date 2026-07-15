package com.loai.inventory.domain.model;

/**
 * Lifecycle of a {@link ListingComment} (slice R2, {@code stories/storefront_comments.md}).
 * Answering IS the moderation act (epic §4): {@code PENDING → ANSWERED} publishes the Q&amp;A pair;
 * {@code PENDING → DISMISSED} hides it silently and is terminal. Nothing unanswered ever serves
 * publicly.
 */
public enum CommentStatus {
  PENDING,
  ANSWERED,
  DISMISSED
}
