package com.loai.inventory.domain.model;

/**
 * Moderation lifecycle of a {@link ListingReview} (slice R1, {@code
 * stories/storefront_reviews.md}). Every review lands {@code PENDING}; only the merchant's explicit
 * act publishes it (epic decision §4 — nothing is public until the merchant acts). An
 * edit-resubmission resets an {@code APPROVED} review to {@code PENDING} (edited content is
 * re-moderated).
 */
public enum ReviewStatus {
  PENDING,
  APPROVED,
  REJECTED
}
