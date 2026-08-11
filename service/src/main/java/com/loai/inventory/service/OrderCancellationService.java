package com.loai.inventory.service;

import com.loai.inventory.common.exception.ApprovalRequiredException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.FulfillmentStatus;
import com.loai.inventory.domain.model.NotificationRecipient;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.FulfillmentRepository;
import com.loai.inventory.domain.repository.FulfillmentRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cancel a sales order in one atomic transaction: flip it to CANCELLED, release its ACTIVE stock
 * reservations, and direct-refund every prepayment still carrying an unallocated balance. This is
 * the order-cancel primitive the outbound flow reuses across its cancel cases ({@code FLOW.md}
 * §"Online branches & edge cases"):
 *
 * <ul>
 *   <li><b>Cancel pre-PAID</b> (DRAFT / PENDING_PAYMENT, no money) — release reservations only.
 *   <li><b>Underpaid cancel</b> (PENDING_PAYMENT with a partial Payment) — release reservations and
 *       direct-refund the partial prepayment.
 *   <li><b>Cancel post-PAID, no fulfillment delivered</b> (PAID, no invoice) — release reservations
 *       and direct-refund the full prepayment. Any PENDING fulfillment is cascade-cancelled.
 *   <li><b>Partial-delivery cancel</b> (FULFILLING with every shipment either DELIVERED or FAILED —
 *       {@code salesOrder.md} "→ CANCELLED (post-PAID, partial delivery)") — cascade-cancel PENDING
 *       fulfillments, release the un-fulfilled reservations, and direct-refund the
 *       <b>unallocated</b> prepayment, which is exactly the never-invoiced remainder: delivered
 *       lines were allocated to their invoice at delivery and are untouched (the customer keeps
 *       those goods; returning them afterwards is the separate CreditNote + Refund flow).
 * </ul>
 *
 * <p>Still rejected: a SHIPPED (in-flight) fulfillment blocks the cancel (409) — the goods are
 * neither in the warehouse nor with the customer, so the shipment must first resolve to DELIVERED
 * or FAILED. FULFILLED/CLOSED orders don't cancel at all: everything was delivered, so the remedy
 * is a CreditNote per invoice ({@link SalesOrder#cancel} rejects them).
 *
 * <p>Approval gate: a cancel whose refunds sum above the org threshold needs OWNER — gated on the
 * <b>aggregate</b>, not per refund, so splitting a payout across several sub-threshold prepayments
 * cannot dodge the escalation.
 *
 * <p>Two-step refund lifecycle ({@code refund.md}: PENDING → EXECUTED). This step creates the
 * refund(s) as <b>PENDING</b> only — it records the obligation but moves no money: no DEBIT
 * Transaction, {@code payment.unallocated_amount} unchanged, {@code sales_order.prepaid_amount} not
 * reduced. The admin then performs the real reverse InstaPay transfer and executes each refund
 * separately ({@code POST /refunds/{id}/execute}), which records the VERIFIED DEBIT and reconciles
 * the caches. Refunds are direct-from-Payment (no CreditNote — no invoice was ever issued for these
 * prepayments), and the same above-threshold OWNER gate as the manual path applies at creation.
 *
 * <p>The cancel and the PENDING-refund creation commit (or roll back) together, so a cancelled
 * order never lacks its corresponding refund obligation.
 */
public final class OrderCancellationService {

  private static final Logger log = LoggerFactory.getLogger(OrderCancellationService.class);

  /** Free-text reason written to {@code inventory_reservation.released_reason} on cancel. */
  private static final String RELEASE_REASON_CANCELLED = "CANCELLED";

  /** Default refund channel — the online prepayments this cancels arrive via manual InstaPay. */
  private static final PaymentProvider DEFAULT_REFUND_METHOD = PaymentProvider.INSTAPAY_MANUAL;

  private final DSLContext rootDsl;
  private final SalesOrderRepositoryFactory salesOrderRepoFactory;
  private final PaymentRepositoryFactory paymentRepoFactory;
  private final FulfillmentRepositoryFactory fulfillmentRepoFactory;
  private final ReservationService reservationService;
  private final RefundService refundService;
  private final NotificationService notificationService;
  private final MagicLinkService magicLinkService;

  public OrderCancellationService(
      DSLContext rootDsl,
      SalesOrderRepositoryFactory salesOrderRepoFactory,
      PaymentRepositoryFactory paymentRepoFactory,
      FulfillmentRepositoryFactory fulfillmentRepoFactory,
      ReservationService reservationService,
      RefundService refundService,
      NotificationService notificationService,
      MagicLinkService magicLinkService) {
    this.rootDsl = rootDsl;
    this.salesOrderRepoFactory = salesOrderRepoFactory;
    this.paymentRepoFactory = paymentRepoFactory;
    this.fulfillmentRepoFactory = fulfillmentRepoFactory;
    this.reservationService = reservationService;
    this.refundService = refundService;
    this.notificationService = notificationService;
    this.magicLinkService = magicLinkService;
  }

  /**
   * The cancelled order plus what the cancel did: reservations released and the PENDING refund(s)
   * created (awaiting a separate execute). {@code pendingRefundTotal} is their summed amount.
   */
  public record CancelResult(
      SalesOrder order,
      int reservationsReleased,
      List<Refund> refunds,
      BigDecimal pendingRefundTotal) {}

  /**
   * Cancel {@code orderId}. {@code refundMethod} is the channel money goes back through (defaults
   * to the online InstaPay path); {@code actorId} is the cancelling user. {@code
   * callerIsOwnerOrAdmin} gates an above-threshold refund to OWNER — a cancellation that moves
   * large cash is held to the same approval bar as a manual refund. Throws {@link
   * NotFoundException} if the order is absent, {@link
   * com.loai.inventory.common.exception.InvalidOrderTransitionException} (via {@link
   * SalesOrder#cancel}) if it is already terminal or delivered, and {@link
   * com.loai.inventory.common.exception.AuthorizationException} if a refund exceeds the org
   * threshold and the caller is not OWNER (the whole cancel rolls back).
   */
  public CancelResult cancel(
      UUID orgId,
      UUID orderId,
      String reason,
      PaymentProvider refundMethod,
      UUID actorId,
      boolean callerIsOwnerOrAdmin) {
    if (orderId == null) {
      throw new ValidationException("order id is required");
    }
    if (actorId == null) {
      throw new ValidationException("actor identity is required");
    }
    PaymentProvider method = refundMethod == null ? DEFAULT_REFUND_METHOD : refundMethod;

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          SalesOrderRepository orderRepo = salesOrderRepoFactory.create(txDsl);
          PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);
          FulfillmentRepository fulfillmentRepo = fulfillmentRepoFactory.create(txDsl);

          // Cascade-cancel PENDING fulfillments BEFORE taking the order lock. Everything else in
          // the codebase locks fulfillment → order (ship, deliver, replace), so touching
          // fulfillment rows while already holding the order lock would create an AB/BA deadlock;
          // the guarded UPDATE (… WHERE status='PENDING') keeps this race-safe without a re-order.
          // Their reservations are still ACTIVE and get released by releaseForOrder below.
          for (Fulfillment f : fulfillmentRepo.findByOrderId(orgId, orderId)) {
            if (f.getStatus() == FulfillmentStatus.PENDING) {
              fulfillmentRepo.cancelIfPending(orgId, f.getId(), now);
            }
          }

          // Lock the order, then run the domain guard (rejects FULFILLED/CLOSED/already-terminal).
          SalesOrder order =
              orderRepo
                  .findByIdForUpdate(orgId, orderId)
                  .orElseThrow(() -> new NotFoundException("SalesOrder", orderId));
          order.cancel(now);

          // With the order locked, re-read the fulfillments and enforce the cancel precondition:
          // every shipment must be settled. SHIPPED means goods in flight — neither in the
          // warehouse nor with the customer — so the shipment must first deliver or fail. A
          // leftover PENDING means a fulfillment was created/shipped concurrently (its creator
          // held the order lock); rolling back un-does the cascade above, so nothing is half-done.
          for (Fulfillment f : fulfillmentRepo.findByOrderId(orgId, orderId)) {
            if (f.getStatus() == FulfillmentStatus.SHIPPED) {
              throw new ConflictException(
                  "fulfillment "
                      + f.getId()
                      + " is SHIPPED (in flight); deliver or fail it before cancelling order "
                      + order.getOrderNumber());
            }
            if (f.getStatus() == FulfillmentStatus.PENDING) {
              throw new ConflictException(
                  "fulfillment "
                      + f.getId()
                      + " changed concurrently while cancelling order "
                      + order.getOrderNumber()
                      + "; retry the cancel");
            }
          }

          // Release the order's ACTIVE reservations (shared with TTL expiry).
          ActorContext actor = ActorContext.user(actorId.toString());
          ReservationService.ReleaseResult released =
              reservationService.releaseForOrder(
                  txDsl, orderId, RELEASE_REASON_CANCELLED, actor, now);

          // Every prepayment still carrying an unallocated balance will be direct-refunded.
          List<Payment> payments = paymentRepo.findUnallocatedByOrderForUpdate(orgId, orderId);

          // Aggregate approval gate: a single cancel that pays back more than the org threshold
          // needs OWNER — even if it splits across several sub-threshold prepayments. The
          // per-refund
          // gate in createDirectPendingInTx alone is bypassable by structuring one payout into many
          // small payments, so gate the SUM here before creating any refund.
          BigDecimal totalToRefund = BigDecimal.ZERO;
          for (Payment payment : payments) {
            BigDecimal amount = payment.getUnallocatedAmount();
            if (amount.signum() > 0) {
              totalToRefund = totalToRefund.add(amount);
            }
          }
          BigDecimal threshold = refundService.approvalThreshold(txDsl, orgId);
          if (totalToRefund.compareTo(threshold) > 0 && !callerIsOwnerOrAdmin) {
            throw new ApprovalRequiredException(
                "cancel refunds totalling "
                    + totalToRefund
                    + " exceed approval threshold "
                    + threshold
                    + "; requires OWNER",
                OrgRole.OWNER.name(),
                threshold,
                totalToRefund);
          }

          // Create a PENDING direct refund per prepayment (FIFO-locked). No money moves here — the
          // admin executes each refund separately.
          List<Refund> refunds = new ArrayList<>(payments.size());
          BigDecimal pendingRefundTotal = BigDecimal.ZERO;
          for (Payment payment : payments) {
            BigDecimal amount = payment.getUnallocatedAmount();
            if (amount.signum() <= 0) {
              continue;
            }
            Refund refund =
                refundService.createDirectPendingInTx(
                    txDsl,
                    orgId,
                    payment,
                    amount,
                    method,
                    "order " + order.getOrderNumber() + " cancelled",
                    callerIsOwnerOrAdmin,
                    now);
            refunds.add(refund);
            pendingRefundTotal = pendingRefundTotal.add(amount);
          }

          // prepaid_amount is left unchanged. It is NOT recomputed here, and (despite an earlier
          // comment) it is NOT recomputed on refund execute either — executeDirect only updates the
          // Payment. The order is now terminal (CANCELLED), so the stored cache is no longer read;
          // the true figure is derivable as SUM(payments) − SUM(EXECUTED refunds) if ever needed.
          orderRepo.updateCancelledState(order);

          // ORDER_CANCELLED — raised last, once the cancel is fully assembled, so the message can
          // state the refund total and so an above-threshold cancel refused at the approval gate
          // above rolls back and stays silent (the gate throws before any refund is created).
          // Silent when the order has no customer, mirroring the other order events.
          if (order.getCustomerId() != null) {
            notifyCancelled(txDsl, orgId, order, pendingRefundTotal, now);
          }

          log.info(
              "Cancelled order {} ({}): released {} reservation(s), {} PENDING refund(s) totalling {}",
              orderId,
              order.getOrderNumber(),
              released.released(),
              refunds.size(),
              pendingRefundTotal);
          return new CancelResult(order, released.released(), refunds, pendingRefundTotal);
        });
  }

  /**
   * ORDER_CANCELLED producer ({@code stories/order_lifecycle_notifications.md}). Runs inside the
   * cancel txn, so the notification exists iff the cancel commits.
   *
   * <p>The money copy is the careful part. This service creates refunds <b>PENDING</b> — it records
   * an obligation and moves nothing ({@code refund.md}'s two-step lifecycle) — so the payload
   * carries the total only to let the template say a refund <em>is being processed</em>. A cancel
   * with no prepayment to return (the common pre-PAID case) passes a zero total, which is omitted
   * entirely rather than rendered as "a refund of 0.00": the shopper who never paid should not be
   * told about a refund at all.
   */
  private void notifyCancelled(
      DSLContext txDsl,
      UUID orgId,
      SalesOrder order,
      BigDecimal pendingRefundTotal,
      OffsetDateTime now) {
    MagicLinkService.OrderViewLink viewLink =
        magicLinkService.issueOrderViewLink(
            txDsl, orgId, order.getCustomerId(), order.getId(), now);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("order_number", order.getOrderNumber());
    if (pendingRefundTotal != null && pendingRefundTotal.signum() > 0) {
      payload.put("refund_total", pendingRefundTotal);
      payload.put("currency", order.getCurrency());
    }
    notificationService.notify(
        txDsl,
        orgId,
        NotificationRecipient.customer(order.getCustomerId()),
        NotificationType.ORDER_CANCELLED,
        payload,
        "sales_order",
        order.getId(),
        viewLink.absolute());
  }
}
