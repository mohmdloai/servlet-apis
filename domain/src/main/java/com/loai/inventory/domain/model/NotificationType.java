package com.loai.inventory.domain.model;

/**
 * The kind of domain event a notification represents. Stored as its {@code name()} in the open
 * {@code notification.type} TEXT column, so adding a type needs no migration. Templates key on this
 * enum (see the service layer). More types are added as their producer hooks are wired (see the
 * routing table in {@code docs/notifications-plan.md} §8).
 */
public enum NotificationType {
  ORDER_PLACED,

  /**
   * The order flipped PENDING_PAYMENT → PAID by remote money (MATCHED / OVERPAID reconcile) — "your
   * payment was received, your order is confirmed". Customer-only, email. Deliberately not fired by
   * the in-store sale (the receipt is the notification) nor on UNDERPAID (a partial ack is a
   * separate future event). See {@code stories/notify_order_paid.md}.
   */
  ORDER_PAID,

  /**
   * The merchant answered the customer's listing question (slice R2, {@code
   * stories/storefront_comments.md}) — the answer published the Q&amp;A pair and this closes the
   * loop back to the asker. Customer recipient (feed + email); raised inside the reply txn, first
   * reply only (editing an answer never re-notifies). Payload carries {@code listing_slug} so the
   * portal feed can deep-link to the listing's Q&amp;A anchor.
   */
  COMMENT_REPLIED
}
