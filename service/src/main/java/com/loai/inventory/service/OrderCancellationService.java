package com.loai.inventory.service;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
 *       and direct-refund the full prepayment.
 * </ul>
 *
 * <p>Out of scope here (needs the CreditNote path): cancelling after at least one DELIVERED
 * fulfillment — {@link SalesOrder#cancel} already rejects FULFILLED/CLOSED, and a partially-
 * delivered order's invoiced lines must be credited rather than refunded from Payment.
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
  private final ReservationService reservationService;
  private final RefundService refundService;

  public OrderCancellationService(
      DSLContext rootDsl,
      SalesOrderRepositoryFactory salesOrderRepoFactory,
      PaymentRepositoryFactory paymentRepoFactory,
      ReservationService reservationService,
      RefundService refundService) {
    this.rootDsl = rootDsl;
    this.salesOrderRepoFactory = salesOrderRepoFactory;
    this.paymentRepoFactory = paymentRepoFactory;
    this.reservationService = reservationService;
    this.refundService = refundService;
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

          // Lock the order, then run the domain guard (rejects FULFILLED/CLOSED/already-terminal).
          SalesOrder order =
              orderRepo
                  .findByIdForUpdate(orgId, orderId)
                  .orElseThrow(() -> new NotFoundException("SalesOrder", orderId));
          order.cancel(now);

          // Release the order's ACTIVE reservations (shared with TTL expiry).
          ActorContext actor = ActorContext.user(actorId.toString());
          ReservationService.ReleaseResult released =
              reservationService.releaseForOrder(
                  txDsl, orderId, RELEASE_REASON_CANCELLED, actor, now);

          // Create a PENDING direct refund for every prepayment still carrying an unallocated
          // balance (FIFO-locked). No money moves here — the admin executes each refund separately.
          List<Payment> payments = paymentRepo.findUnallocatedByOrderForUpdate(orgId, orderId);
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

          // prepaid_amount is intentionally NOT reduced here — that happens when the refund
          // EXECUTES
          // (prepaid = SUM(payments) − SUM(EXECUTED refunds)). Persist only the cancel state.
          orderRepo.updateCancelledState(order);

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
}
