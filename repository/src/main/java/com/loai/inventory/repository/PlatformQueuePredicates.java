package com.loai.inventory.repository;

import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION_DELIVERY;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.REFUND;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;

import com.loai.inventory.domain.model.PlatformQueueKind;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentReconciliationStatus;
import com.loai.inventory.repository.generated.enums.PaymentStatus;
import com.loai.inventory.repository.generated.enums.RefundStatus;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

/**
 * <strong>The single definition of what is in each cross-org queue.</strong>
 *
 * <p>Two callers consume it and neither restates it:
 *
 * <ul>
 *   <li>{@link PlatformStatsRepositoryImpl#queueCounts()} — the five numbers on the operator's
 *       overview tiles.
 *   <li>{@link PlatformQueueRepositoryImpl} — the paged rows behind those tiles.
 * </ul>
 *
 * <p>The tile count and the list {@code total} <em>must</em> be the same number, and the way to
 * guarantee that is not a test — it is not writing the predicate twice. A test asserting the two
 * agree would pass right up until someone edited one of them. This is the same move {@link
 * OrgStatusConditions} makes for {@code OrgStatus}; follow that precedent when adding a sixth
 * queue.
 *
 * <p>Each predicate is the org-scoped list's predicate <em>verbatim, minus its org filter</em> —
 * nothing else about it changes. Two of the five have no org-scoped list to match ({@code
 * FAILED_EMAILS}, {@code EXPIRED_PENDING_ORDERS}); their sources are named below anyway.
 */
final class PlatformQueuePredicates {

  private PlatformQueuePredicates() {}

  /**
   * The FROM clause a queue's predicate and its org narrowing need — and nothing more. The row
   * query extends it with lookup joins (the tenant, an order number) that are strictly
   * count-preserving, so extending it can never move a {@code total} away from its tile.
   *
   * <p>{@code FAILED_EMAILS} is the only kind that joins here: {@code notification_delivery}
   * carries no {@code org_id} of its own — the org lives on {@code notification} — so that join is
   * mandatory both for the row's tenant context and for {@code ?org_id=}. It is an inner join
   * across a NOT NULL foreign key, so it changes no count.
   */
  static Table<?> from(PlatformQueueKind kind) {
    return switch (kind) {
      case FAILED_EMAILS ->
          NOTIFICATION_DELIVERY
              .join(NOTIFICATION)
              .on(NOTIFICATION.ID.eq(NOTIFICATION_DELIVERY.NOTIFICATION_ID));
      case PENDING_REFUNDS -> REFUND;
      case OPEN_DISPUTES -> PAYMENT;
      case ORPHAN_TRANSACTIONS -> PAYMENT_TRANSACTION;
      case EXPIRED_PENDING_ORDERS -> SALES_ORDER;
    };
  }

  /** The membership rule. Written once; see the class comment for why that matters. */
  static Condition where(PlatformQueueKind kind) {
    return switch (kind) {
      // No channel narrowing, per stories/platform_overview.md. Only the email leg has a failure
      // path today (NotificationService.dispatchPendingEmail); the in-app leg goes PENDING→SENT and
      // never reaches FAILED, so the two predicates coincide. If an in-app failure path is ever
      // added, this one needs `AND channel='email'` to keep matching its name.
      case FAILED_EMAILS -> NOTIFICATION_DELIVERY.STATUS.eq("FAILED");
      case PENDING_REFUNDS -> REFUND.STATUS.eq(RefundStatus.PENDING);
      case OPEN_DISPUTES -> PAYMENT.STATUS.eq(PaymentStatus.DISPUTED);
      // The org queue's has_payment=false verbatim: the 1:1 payment row is the disposition marker,
      // so a resolved or refunded orphan has already left the queue.
      case ORPHAN_TRANSACTIONS ->
          PAYMENT_TRANSACTION
              .RECONCILIATION_STATUS
              .eq(PaymentReconciliationStatus.ORPHAN)
              .and(
                  DSL.notExists(
                      DSL.selectOne()
                          .from(PAYMENT)
                          .where(PAYMENT.PAYMENT_TRANSACTION_ID.eq(PAYMENT_TRANSACTION.ID))));
      // Served by idx_so_pending_global (V29) — the sweeper's own candidate predicate.
      case EXPIRED_PENDING_ORDERS ->
          SALES_ORDER
              .STATUS
              .eq(OrderStatus.PENDING_PAYMENT)
              .and(SALES_ORDER.EXPIRES_AT.lt(DSL.currentOffsetDateTime()));
    };
  }

  /** The tenant column {@code ?org_id=} narrows on, and the row's join key to {@code org}. */
  static Field<UUID> orgId(PlatformQueueKind kind) {
    return switch (kind) {
      case FAILED_EMAILS -> NOTIFICATION.ORG_ID;
      case PENDING_REFUNDS -> REFUND.ORG_ID;
      case OPEN_DISPUTES -> PAYMENT.ORG_ID;
      case ORPHAN_TRANSACTIONS -> PAYMENT_TRANSACTION.ORG_ID;
      case EXPIRED_PENDING_ORDERS -> SALES_ORDER.ORG_ID;
    };
  }

  /**
   * The oldest-first ordering key, and the column an index for this queue must lead on after its
   * partial predicate.
   *
   * <p>Where an org-scoped twin exists, this is <strong>the same clock it orders by</strong> —
   * refunds {@code created_at}, payments {@code received_at}, transactions {@code occurred_at}. A
   * platform queue that ordered by a different clock than its org twin would list the same rows in
   * a different order, which reads as a bug every time.
   *
   * <p>{@code FAILED_EMAILS} orders on {@code created_at}, not the {@code failed_at} it also
   * reports: {@code failed_at} is nullable in the schema (only {@code markDeliveryFailed} stamps
   * it), and an ordering key that can be null is not a total order. {@code created_at} is {@code
   * NOT NULL DEFAULT NOW()} and moves with the same event.
   */
  static Field<OffsetDateTime> orderKey(PlatformQueueKind kind) {
    return switch (kind) {
      case FAILED_EMAILS -> NOTIFICATION_DELIVERY.CREATED_AT;
      case PENDING_REFUNDS -> REFUND.CREATED_AT;
      case OPEN_DISPUTES -> PAYMENT.RECEIVED_AT;
      case ORPHAN_TRANSACTIONS -> PAYMENT_TRANSACTION.OCCURRED_AT;
      case EXPIRED_PENDING_ORDERS -> SALES_ORDER.EXPIRES_AT;
    };
  }

  /** The row's own primary key — the tie-break that makes paging stable across equal timestamps. */
  static Field<UUID> id(PlatformQueueKind kind) {
    return switch (kind) {
      case FAILED_EMAILS -> NOTIFICATION_DELIVERY.ID;
      case PENDING_REFUNDS -> REFUND.ID;
      case OPEN_DISPUTES -> PAYMENT.ID;
      case ORPHAN_TRANSACTIONS -> PAYMENT_TRANSACTION.ID;
      case EXPIRED_PENDING_ORDERS -> SALES_ORDER.ID;
    };
  }
}
