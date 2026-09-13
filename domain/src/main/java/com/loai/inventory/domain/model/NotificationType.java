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
  COMMENT_REPLIED,

  /**
   * An online order finished delivering — "how was your order? review your items" (roadmap item 1,
   * {@code stories/review_request_on_delivery.md}). Customer recipient (feed + email); raised
   * inside the deliver txn on the {@code FULFILLING → FULFILLED} roll-up edge, exactly once per
   * order (never on a partial delivery or a re-deliver, never for the in-store sale). Payload
   * carries {@code order_number}; the email deep-links to the branded order-view page so the
   * shopper can rate each delivered line.
   */
  REVIEW_REQUESTED,

  /**
   * A shipment left the warehouse — the first of the three types that close the PAID→FULFILLED
   * silence ({@code stories/order_lifecycle_notifications.md}). Customer recipient (feed + email);
   * raised inside the {@code FulfillmentService.ship} txn on the {@code PENDING → SHIPPED} edge, so
   * a rolled-back shipment sends nothing. Fires <b>once per fulfillment, not once per order</b>: a
   * split order really does put two boxes on the road and the shopper is owed both. Payload carries
   * {@code order_number} plus {@code carrier}/{@code tracking_number} when the merchant recorded
   * them — the template names them only when present, never "carrier: null".
   *
   * <p>Deliberately <b>not</b> {@code OUT_FOR_DELIVERY}: there is exactly one transition here, and
   * {@code stories/rider_self_delivery.md} reserves that name for the rider-pickup edge (it also
   * declined a distinct status, "reuse SHIPPED"). Two names for one edge would be two words for one
   * fact.
   */
  ORDER_SHIPPED,

  /**
   * The order was cancelled by the merchant ({@code OrderCancellationService}). Customer recipient
   * (feed + email); raised inside the cancel txn <em>after</em> the refund obligations are created,
   * so the notification exists iff the whole cancel commits — an above-threshold cancel denied at
   * the approval gate rolls back and stays silent. Payload carries {@code order_number} and, when
   * the cancel created PENDING refunds, {@code refund_total}/{@code currency}.
   *
   * <p>The money copy is deliberately careful: a cancel records a refund <b>obligation</b>, it does
   * not move money (two-step lifecycle, {@code refund.md}), so the template says a refund is on its
   * way and never that it has been sent. An order that had no prepayment gets the plain cancel
   * notice with no money sentence at all.
   *
   * <p>Order <b>expiry</b> is a different event and is not this type — the TTL sweeper stays silent
   * for now (owner decision, {@code stories/order_lifecycle_notifications.md} §Out).
   */
  ORDER_CANCELLED,

  /**
   * A payment arrived but did not cover the order — the UNDERPAID branch of {@code
   * PaymentService.reconcileAndCreate}. Customer recipient (feed + email); raised inside the
   * reconcile txn beside the partial {@code Payment}, so the acknowledgement and the money record
   * commit together. Payload carries {@code order_number}, the {@code amount} just received, the
   * {@code outstanding} remainder and {@code currency}.
   *
   * <p>This is the one case where the shopper has genuinely paid real money and, until now, heard
   * nothing at all — the order sits {@code PENDING_PAYMENT} and looks to them exactly like a failed
   * transfer. Fires <b>per recorded partial</b>: a second top-up that still falls short raises it
   * again with the new, smaller remainder, which is the useful thing to say.
   *
   * <p>Deliberately silent on OVERPAID (that order is PAID — {@link #ORDER_PAID} covers it, and the
   * excess-refund conversation is the admin's) and on a DISPUTED payment (a merchant-initiated
   * flag, not news the shopper can act on).
   */
  PAYMENT_NEEDS_ATTENTION,

  /**
   * The store looked for the shopper's claimed transfer and could not find it ({@code
   * stories/payment_claim_not_found.md}) — "check the reference in your InstaPay app and send it
   * again; your order is held until …". Customer recipient (feed + email); raised inside the
   * not-found txn beside the claim's NOT_FOUND flip and the 6 h hold re-arm, so the message exists
   * iff the decision commits. Payload carries {@code order_number}, {@code reference}, {@code
   * reason} (NO_TRANSFER / DIFFERENT_ACCOUNT / OTHER), an optional {@code note} from the manager
   * and {@code held_until} when the order still has a hold. Silent when the claim names no customer
   * (nobody to tell).
   */
  PAYMENT_NOT_FOUND,

  /**
   * A sale took a product's <em>available</em> stock from above its reorder point to at or below it
   * ({@code stories/reorder_point.md}, V94). Org staff recipients (in-app, the {@code ORDER_PLACED}
   * fan-out); raised by {@code LowStockNotifier} inside the sale's own transaction — the
   * reservation at online/phone placement and the in-store sale's decrement — so a rolled-back sale
   * tells nobody. Fires on the <b>crossing</b>, never on the state: a product already below its
   * point sells on in silence until a restock (or a release) re-arms it. Payload carries {@code
   * product_id}, {@code name}, {@code sku}, {@code available}, {@code reorder_point}; source is the
   * product, and the link is its stock page.
   */
  LOW_STOCK,

  /**
   * A merchant opened a support ticket ({@code stories/support_tickets.md}). Recipients: every
   * active platform ADMIN / SUPPORT user, each as a {@code USER} with {@code org_id} = the ticket's
   * org (the tenant this concerns — the {@code platform_audit.org_id} semantics). Raised inside the
   * create txn, so a ticket that exists has told the desk. Payload: {@code ticket_number}, {@code
   * subject}, {@code org_name}, {@code blocking}; source is the ticket.
   */
  SUPPORT_TICKET_OPENED,

  /**
   * The merchant wrote on a ticket the desk had answered (a reply, a reopen) or closed it — the
   * desk's "your move again". Same recipients and payload as {@link #SUPPORT_TICKET_OPENED}, plus
   * {@code event} ({@code replied} / {@code reopened} / {@code closed}).
   */
  SUPPORT_TICKET_UPDATED,

  /**
   * The desk replied on the merchant's ticket. Recipients: the merchant participants (every active
   * member who wrote on the merchant side; the OWNERs when none remain). In-app + push today — a
   * USER recipient has no email leg (epic decision 8). Payload: {@code ticket_number}, {@code
   * subject}; the link is the thread.
   */
  SUPPORT_TICKET_REPLIED,

  /** The desk marked the merchant's ticket resolved — confirm, or reply to reopen. */
  SUPPORT_TICKET_RESOLVED
}
