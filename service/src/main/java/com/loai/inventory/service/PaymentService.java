package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.NotificationRecipient;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.PlatformFunnelStage;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.InventoryReservationRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentTransactionRepository;
import com.loai.inventory.domain.repository.PaymentTransactionRepositoryFactory;
import com.loai.inventory.domain.repository.RefundRepository;
import com.loai.inventory.domain.repository.RefundRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import com.loai.inventory.service.platform.OrgMilestoneService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconcile a VERIFIED {@link PaymentTransaction} against a sales order and, on an exact match,
 * create the {@link Payment} that flips the order to PAID.
 *
 * <p>Collaborator service — like {@code ReservationService}, its write paths run entirely inside
 * the caller's transaction ({@code txDsl}) and never open their own. The caller ({@link
 * PaymentTransactionService}) owns the transaction boundary so the txn-verify and the
 * payment-creation commit (or roll back) together. The read-only {@link #listForOrder} runs on
 * {@code rootDsl} with no explicit transaction, same as every other read.
 *
 * <p>Classification:
 *
 * <ul>
 *   <li>no/blank order ref, order not found, or order not in {@code PENDING_PAYMENT} → ORPHAN (no
 *       Payment; admin resolves from the orphan queue)
 *   <li>{@code amount < grand_total − prepaid} → UNDERPAID (create Payment, accumulate {@code
 *       prepaid_amount}, order stays PENDING_PAYMENT)
 *   <li>{@code amount > grand_total − prepaid} → OVERPAID (create Payment, mark order PAID; excess
 *       stays on {@code payment.unallocated_amount} for a later direct refund)
 *   <li>{@code amount == grand_total − prepaid} → MATCHED (create Payment, mark order PAID)
 * </ul>
 */
public final class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  /**
   * Canonical rejection for an in-store tender that names the online-only provider. Shared with
   * {@link SalesOrderService#validateInStoreInputs} so the wording can't drift between the two
   * layers that both guard it.
   */
  public static final String IN_STORE_PROVIDER_REJECT_MSG =
      "INSTAPAY_MANUAL is the online path; in-store accepts CASH or INSTAPAY_IN_STORE";

  /**
   * The same rejection for the online card rail: a {@code paymob_card} transaction is written by
   * the Paymob webhook and nothing else. Card at the counter is the epic's slice 4 (a terminal, its
   * own provider value), not this constant with a cashier behind it.
   */
  public static final String ONLINE_CARD_PROVIDER_REJECT_MSG =
      "PAYMOB_CARD is the online card path (recorded by the gateway webhook); in-store accepts"
          + " CASH or INSTAPAY_IN_STORE";

  /**
   * stories/cash_shift.md: which drawer-day a counter tender belongs to; NONE outside production.
   */
  private final CashShiftStamper cashShifts;

  private final DSLContext rootDsl;
  private final PaymentRepositoryFactory paymentRepoFactory;
  private final SalesOrderRepositoryFactory salesOrderRepoFactory;
  private final PaymentTransactionRepositoryFactory txnRepoFactory;
  private final RefundRepositoryFactory refundRepoFactory;
  private final InventoryReservationRepositoryFactory reservationRepoFactory;
  private final NotificationService notificationService;
  private final MagicLinkService magicLinkService;
  private final OrgMilestoneService milestoneService;

  public PaymentService(
      DSLContext rootDsl,
      PaymentRepositoryFactory paymentRepoFactory,
      SalesOrderRepositoryFactory salesOrderRepoFactory,
      PaymentTransactionRepositoryFactory txnRepoFactory,
      RefundRepositoryFactory refundRepoFactory,
      InventoryReservationRepositoryFactory reservationRepoFactory,
      NotificationService notificationService,
      MagicLinkService magicLinkService,
      OrgMilestoneService milestoneService) {
    this(
        rootDsl,
        paymentRepoFactory,
        salesOrderRepoFactory,
        txnRepoFactory,
        refundRepoFactory,
        reservationRepoFactory,
        notificationService,
        magicLinkService,
        milestoneService,
        CashShiftStamper.NONE);
  }

  /** The production wiring: counter tenders are stamped with the org's cash shift (V95). */
  public PaymentService(
      DSLContext rootDsl,
      PaymentRepositoryFactory paymentRepoFactory,
      SalesOrderRepositoryFactory salesOrderRepoFactory,
      PaymentTransactionRepositoryFactory txnRepoFactory,
      RefundRepositoryFactory refundRepoFactory,
      InventoryReservationRepositoryFactory reservationRepoFactory,
      NotificationService notificationService,
      MagicLinkService magicLinkService,
      OrgMilestoneService milestoneService,
      CashShiftStamper cashShifts) {
    this.cashShifts = cashShifts == null ? CashShiftStamper.NONE : cashShifts;
    this.rootDsl = rootDsl;
    this.paymentRepoFactory = paymentRepoFactory;
    this.salesOrderRepoFactory = salesOrderRepoFactory;
    this.txnRepoFactory = txnRepoFactory;
    this.refundRepoFactory = refundRepoFactory;
    this.reservationRepoFactory = reservationRepoFactory;
    this.notificationService = notificationService;
    this.magicLinkService = magicLinkService;
    this.milestoneService = milestoneService;
  }

  /**
   * The PENDING_PAYMENT → PAID flip's twin write ({@code stories/clear_expiry_on_paid.md}): a paid
   * order has no payment-hold window, so its ACTIVE reservations' {@code expires_at} display mirror
   * (V19) is nulled in the same transaction — otherwise the holds keep asserting a deadline that
   * ceased to exist and the UI renders "Expired" over stock firmly held for a paid order. {@code
   * markPaid} nulls the order's own column; this clears the mirror. Deliberately absent from the
   * UNDERPAID branch — a partially-paid order is still expirable and its countdown is true. Moves
   * no stock.
   */
  private void clearHoldDeadlines(DSLContext txDsl, SalesOrder order) {
    int cleared = reservationRepoFactory.create(txDsl).clearExpiryForOrder(order.getId());
    if (cleared > 0) {
      log.debug(
          "Cleared expires_at on {} ACTIVE reservation(s) of paid order {}",
          cleared,
          order.getOrderNumber());
    }
  }

  /**
   * A shopper's payment claim keeps the order open ({@code stories/payment_claim_verify.md}): the
   * hold moves to {@code max(expires_at, until)}, the ACTIVE reservations' V19 display mirror moves
   * with it, and the sweeper — which knows nothing about claims — simply finds a later deadline.
   * Locks the order ({@code FOR UPDATE}) so a concurrent expiry sweep serialises against it: the
   * loser observes the committed state. A no-op (empty) when the order is not PENDING_PAYMENT — a
   * paid, cancelled or already-expired order has no hold to extend, and the claim is still recorded
   * against it (a verify later reconciles it as ORPHAN with the order's status shown). Runs in
   * {@code txDsl}.
   *
   * @return the order with its (possibly unchanged) deadline, or empty when it has no hold
   */
  public Optional<SalesOrder> extendHoldForClaim(
      DSLContext txDsl, UUID orgId, UUID orderId, OffsetDateTime until, OffsetDateTime now) {
    SalesOrderRepository orderRepo = salesOrderRepoFactory.create(txDsl);
    Optional<SalesOrder> found = orderRepo.findByIdForUpdate(orgId, orderId);
    if (found.isEmpty() || found.get().getStatus() != OrderStatus.PENDING_PAYMENT) {
      return Optional.empty();
    }
    SalesOrder order = found.get();
    if (order.extendHold(until, now)) {
      orderRepo.updatePaymentState(order);
      int moved = reservationRepoFactory.create(txDsl).extendExpiryForOrder(order.getId(), until);
      log.info(
          "Extended hold on order {} to {} for a payment claim ({} reservation mirror(s) moved)",
          order.getOrderNumber(),
          until,
          moved);
    }
    return Optional.of(order);
  }

  /** Target order for a transaction; at most one of the two fields is populated. */
  public record OrderRef(UUID salesOrderId, String orderNumber) {
    public boolean isEmpty() {
      return salesOrderId == null && (orderNumber == null || orderNumber.isBlank());
    }
  }

  /** Outcome of reconciliation. {@code payment} and {@code order} may be null (e.g. ORPHAN). */
  public record Reconciliation(
      PaymentReconciliationStatus status, Payment payment, SalesOrder order) {}

  /**
   * What a currency mismatch between the transaction and its order means to the caller. The two
   * callers want opposite things for the same reason: an admin can fix a typo, a webhook cannot be
   * asked to try again ({@code accept_online_payment.md} §"Known follow-ups", {@code
   * stories/paymob_card_checkout.md}).
   */
  public enum CurrencyMismatch {
    /** Admin-entered data: 400, the whole call rolls back, nothing persists. The original. */
    THROW,
    /** A machine feed: record the event as ORPHAN so it is never lost. */
    ORPHAN
  }

  /**
   * Reconcile {@code txn} against {@code ref} and, on MATCHED, create the {@link Payment} and flip
   * the order to PAID. Runs in {@code txDsl} — does not open a transaction. The admin path: a
   * currency mismatch {@link CurrencyMismatch#THROW throws}.
   */
  public Reconciliation reconcileAndCreate(
      DSLContext txDsl, UUID orgId, PaymentTransaction txn, OrderRef ref) {
    return reconcileAndCreate(txDsl, orgId, txn, ref, CurrencyMismatch.THROW);
  }

  /**
   * {@link #reconcileAndCreate(DSLContext, UUID, PaymentTransaction, OrderRef)} with the
   * currency-mismatch outcome chosen by the caller.
   */
  public Reconciliation reconcileAndCreate(
      DSLContext txDsl,
      UUID orgId,
      PaymentTransaction txn,
      OrderRef ref,
      CurrencyMismatch onCurrencyMismatch) {

    SalesOrderRepository orderRepo = salesOrderRepoFactory.create(txDsl);

    Optional<SalesOrder> found = resolveOrder(orderRepo, orgId, ref);
    if (found.isEmpty()) {
      log.info(
          "Reconcile ORPHAN: transaction {} has no matching order (ref={})",
          txn.getProviderRef(),
          ref);
      return new Reconciliation(PaymentReconciliationStatus.ORPHAN, null, null);
    }

    SalesOrder order = found.get();

    // Money arrived, but there is no open (PENDING_PAYMENT) order to apply it to — treat as orphan.
    if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
      log.info(
          "Reconcile ORPHAN: order {} is {} (not PENDING_PAYMENT) for transaction {}",
          order.getOrderNumber(),
          order.getStatus(),
          txn.getProviderRef());
      return new Reconciliation(PaymentReconciliationStatus.ORPHAN, null, order);
    }

    if (!order.getCurrency().equalsIgnoreCase(txn.getCurrency())) {
      if (onCurrencyMismatch == CurrencyMismatch.ORPHAN) {
        // A webhook drove this: the event must never be dropped, and nobody can retype it.
        log.warn(
            "Reconcile ORPHAN: transaction {} is {} but order {} is {} — currency mismatch on a"
                + " machine feed",
            txn.getProviderRef(),
            txn.getCurrency(),
            order.getOrderNumber(),
            order.getCurrency());
        return new Reconciliation(PaymentReconciliationStatus.ORPHAN, null, order);
      }
      // Deliberate: admin-entered single-call data, so a currency mismatch is a correctable input
      // error (400 → fix → resubmit), not a recorded ORPHAN. The whole txn rolls back, so nothing
      // is persisted and the same provider_ref re-inserts cleanly on retry. Bit-identical to the
      // behaviour before the webhook mode above existed.
      throw new ValidationException(
          "currency mismatch: transaction "
              + txn.getCurrency()
              + " vs order "
              + order.getCurrency());
    }

    BigDecimal outstanding = order.getGrandTotal().subtract(order.getPrepaidAmount());
    int cmp = txn.getAmount().compareTo(outstanding);
    if (cmp < 0) {
      // UNDERPAID — a partial prepayment. Per the documented model (state-machines.md E /
      // payment.md): still create the Payment and accumulate prepaid_amount; the order stays
      // PENDING_PAYMENT because prepaid < grand_total. The partial is a refundable Payment the
      // customer can top up (chase) or have refunded on cancel.
      OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
      Payment payment = createPrepayment(txDsl, orgId, order, txn, now);
      order.addPrepayment(order.getPrepaidAmount().add(txn.getAmount()), now);
      orderRepo.updatePaymentState(order);
      notifyPaymentNeedsAttention(txDsl, orgId, order, txn, now);
      log.info(
          "Reconcile UNDERPAID: order {} prepaid {} < grandTotal {} (payment {} RECEIVED amount={})",
          order.getOrderNumber(),
          order.getPrepaidAmount(),
          order.getGrandTotal(),
          payment.getId(),
          payment.getAmount());
      return new Reconciliation(PaymentReconciliationStatus.UNDERPAID, payment, order);
    }
    if (cmp > 0) {
      // OVERPAID — per the documented model (state-machines.md E / payment.md §Overpaid online):
      // the order is fully covered, so still create the Payment for the full amount and flip the
      // order to PAID. The excess (amount − outstanding) sits on payment.unallocated_amount —
      // invoice issuance at delivery allocates only up to the invoice total (InvoiceService FIFO),
      // and the admin refunds the leftover directly from the Payment (no CreditNote).
      OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
      Payment payment = createPrepayment(txDsl, orgId, order, txn, now);
      BigDecimal newPrepaid = order.getPrepaidAmount().add(txn.getAmount());
      order.markPaid(newPrepaid, now); // domain guard allows prepaid > grand_total
      orderRepo.updatePaymentState(order);
      clearHoldDeadlines(txDsl, order);
      // Same template as MATCHED — the excess-refund conversation is the admin's, not an email's.
      notifyOrderPaid(txDsl, orgId, order, txn, now);
      log.info(
          "Reconcile OVERPAID: order {} → PAID (prepaid={} > grandTotal={}), payment {} RECEIVED"
              + " amount={} excess={}",
          order.getOrderNumber(),
          order.getPrepaidAmount(),
          order.getGrandTotal(),
          payment.getId(),
          payment.getAmount(),
          txn.getAmount().subtract(outstanding));
      return new Reconciliation(PaymentReconciliationStatus.OVERPAID, payment, order);
    }

    // MATCHED — exact cover. Create the payment, then flip the order to PAID in the same txn.
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Payment payment = createPrepayment(txDsl, orgId, order, txn, now);
    BigDecimal newPrepaid = order.getPrepaidAmount().add(txn.getAmount());
    order.markPaid(newPrepaid, now); // domain guard: prepaid >= grand_total, PENDING_PAYMENT → PAID
    orderRepo.updatePaymentState(order);
    clearHoldDeadlines(txDsl, order);
    notifyOrderPaid(txDsl, orgId, order, txn, now);

    log.info(
        "Reconcile MATCHED: order {} → PAID (prepaid={}), payment {} RECEIVED amount={}",
        order.getOrderNumber(),
        order.getPrepaidAmount(),
        payment.getId(),
        payment.getAmount());
    return new Reconciliation(PaymentReconciliationStatus.MATCHED, payment, order);
  }

  /**
   * ORDER_PAID producer ({@code stories/notify_order_paid.md}): the customer's "your payment was
   * received, your order is confirmed" email, fired on the two {@code markPaid} branches (MATCHED
   * and OVERPAID) — which covers every route to PAID-by-money, verify and orphan-resolve alike.
   * Runs inside {@code txDsl}, after the order state is persisted: the notification exists iff the
   * PAID flip commits; delivery is post-commit via the sweeper, at-least-once. A fresh order-view
   * magic link is minted per email, same as placement. Deliberately silent when the order has no
   * customer ({@code customer_id} NULL — possible on PHONE orders): there is nobody to mail.
   */
  private void notifyOrderPaid(
      DSLContext txDsl, UUID orgId, SalesOrder order, PaymentTransaction txn, OffsetDateTime now) {
    if (order.getCustomerId() == null) {
      return;
    }
    MagicLinkService.OrderViewLink viewLink =
        magicLinkService.issueOrderViewLink(
            txDsl, orgId, order.getCustomerId(), order.getId(), now);
    notificationService.notify(
        txDsl,
        orgId,
        NotificationRecipient.customer(order.getCustomerId()),
        NotificationType.ORDER_PAID,
        Map.of(
            "order_number", order.getOrderNumber(),
            "amount", txn.getAmount(),
            "currency", txn.getCurrency()),
        "sales_order",
        order.getId(),
        viewLink.absolute());
  }

  /**
   * PAYMENT_NOT_FOUND producer ({@code stories/payment_claim_not_found.md}): "we looked for your
   * transfer and could not find it — check the reference and send it again; your order is held
   * until …". Runs inside the not-found txn, after the claim's NOT_FOUND flip and the hold re-arm
   * are persisted, so the message exists iff the decision commits. Addressed to the customer who
   * filed the claim (not the order's customer — on a claim they are the same person, and the claim
   * is the row that knows). Silent when the claim names nobody. A fresh order-view magic link per
   * message, as every customer email here.
   */
  public void notifyClaimNotFound(
      DSLContext txDsl,
      UUID orgId,
      SalesOrder order,
      PaymentTransaction claim,
      String reason,
      String note,
      OffsetDateTime heldUntil,
      OffsetDateTime now) {
    if (claim.getClaimedByCustomerId() == null) {
      return;
    }
    MagicLinkService.OrderViewLink viewLink =
        magicLinkService.issueOrderViewLink(
            txDsl, orgId, claim.getClaimedByCustomerId(), order.getId(), now);
    Map<String, Object> payload = new java.util.HashMap<>();
    payload.put("order_number", order.getOrderNumber());
    payload.put("reference", claim.getProviderRef());
    payload.put("reason", reason);
    if (note != null) {
      payload.put("note", note);
    }
    if (heldUntil != null) {
      payload.put("held_until", heldUntil.toString());
    }
    notificationService.notify(
        txDsl,
        orgId,
        NotificationRecipient.customer(claim.getClaimedByCustomerId()),
        NotificationType.PAYMENT_NOT_FOUND,
        payload,
        "sales_order",
        order.getId(),
        viewLink.absolute());
  }

  /**
   * PAYMENT_NEEDS_ATTENTION producer ({@code stories/order_lifecycle_notifications.md}): "we got
   * your transfer, it's short by X, your items are still held". Raised on the <b>UNDERPAID</b>
   * branch only, inside {@code txDsl} and after the partial {@link Payment} and the order's new
   * {@code prepaid_amount} are persisted — so the acknowledgement and the money record commit
   * together, and {@code order.getPrepaidAmount()} already includes this transfer when the
   * remainder is computed.
   *
   * <p>This is the sharpest silence in the flow: the shopper moved real money, the order stays
   * {@code PENDING_PAYMENT}, and from their side that is indistinguishable from a failed transfer.
   * Firing <b>per recorded partial</b> is deliberate — a second top-up that still falls short
   * raises it again carrying the new, smaller remainder, which is the only number they need.
   *
   * <p>Silent when the order has no customer ({@code customer_id} NULL on some PHONE orders),
   * mirroring {@link #notifyOrderPaid}: there is nobody to mail.
   */
  private void notifyPaymentNeedsAttention(
      DSLContext txDsl, UUID orgId, SalesOrder order, PaymentTransaction txn, OffsetDateTime now) {
    if (order.getCustomerId() == null) {
      return;
    }
    BigDecimal outstanding = order.getGrandTotal().subtract(order.getPrepaidAmount());
    MagicLinkService.OrderViewLink viewLink =
        magicLinkService.issueOrderViewLink(
            txDsl, orgId, order.getCustomerId(), order.getId(), now);
    notificationService.notify(
        txDsl,
        orgId,
        NotificationRecipient.customer(order.getCustomerId()),
        NotificationType.PAYMENT_NEEDS_ATTENTION,
        Map.of(
            "order_number", order.getOrderNumber(),
            "amount", txn.getAmount(),
            "outstanding", outstanding,
            "currency", txn.getCurrency()),
        "sales_order",
        order.getId(),
        viewLink.absolute());
  }

  /**
   * Create and persist a RECEIVED, fully-unallocated {@link Payment} for {@code txn} against {@code
   * order}, inside {@code txDsl}. Shared by the MATCHED and UNDERPAID reconciliation branches; the
   * caller then updates {@code prepaid_amount} (and, for MATCHED, flips the order to PAID).
   */
  private Payment createPrepayment(
      DSLContext txDsl, UUID orgId, SalesOrder order, PaymentTransaction txn, OffsetDateTime now) {
    Payment payment =
        Payment.createReceived(
            UUID.randomUUID(),
            orgId,
            order.getCustomerId(),
            order.getId(),
            txn.getId(),
            txn.getAmount(),
            txn.getCurrency(),
            now);
    paymentRepoFactory.create(txDsl).insert(payment);
    // FIRST_PAYMENT — any reconciliation outcome (MATCHED, OVERPAID, UNDERPAID) is money that
    // arrived; the backfill's MIN(payment.received_at) makes no distinction either.
    milestoneService.reach(txDsl, orgId, PlatformFunnelStage.FIRST_PAYMENT, now);
    return payment;
  }

  /**
   * In-store collaborator: create a point-of-sale {@link PaymentTransaction} (cash or in-store
   * InstaPay) already VERIFIED + MATCHED, then the {@link Payment} (RECEIVED, fully unallocated)
   * for it. Runs in {@code txDsl} — does not open a transaction. The caller flips the order to PAID
   * and issues the invoice that auto-allocates this payment, all in the same checkout txn.
   *
   * <p>Staff is matching the payment to the order at the counter in real time, so unlike the online
   * manual-InstaPay path there is no separate reconciliation step (see {@code state-machines.md}
   * E). {@code providerRef} is the real reference when known (in-store InstaPay) or synthesised
   * from the order's idempotency key for cash. Because that key is stable across a retried POST
   * (the order id is freshly random each attempt and must not be used here), the global {@code
   * (provider, provider_ref)} UNIQUE genuinely makes a retried checkout fail rather than
   * double-charge — this is the second of the two double-submit barriers (the first is the order's
   * {@code (org_id, idempotency_key)} UNIQUE).
   */
  public InStorePayment recordInStorePayment(
      DSLContext txDsl,
      UUID orgId,
      SalesOrder order,
      PaymentProvider provider,
      String providerRef,
      BigDecimal amount,
      UUID verifiedBy,
      OffsetDateTime now) {

    if (provider == null) {
      throw new ValidationException("payment provider is required");
    }
    if (provider == PaymentProvider.INSTAPAY_MANUAL) {
      throw new ValidationException(IN_STORE_PROVIDER_REJECT_MSG);
    }
    if (provider == PaymentProvider.PAYMOB_CARD) {
      throw new ValidationException(ONLINE_CARD_PROVIDER_REJECT_MSG);
    }
    if (amount == null || amount.signum() <= 0) {
      throw new ValidationException("payment amount must be > 0");
    }

    // A refless tender (cash, or in-store InstaPay with no ref) gets a synthesised ref keyed on the
    // order's idempotency key — stable across a retried POST so the UNIQUE below catches the retry.
    // The order id is NOT usable here: it is a fresh random UUID on every attempt. (A split sale's
    // second refless InstaPay tender arrives with its ordinal-suffixed ref already resolved —
    // SalesOrderService.normaliseTenders.)
    String idemToken = idempotencyTokenOf(order);
    String ref =
        (providerRef == null || providerRef.isBlank())
            ? provider.name() + "-" + idemToken
            : providerRef.trim();

    // Created UNVERIFIED then promoted to VERIFIED + MATCHED on the spot — the in-store shape.
    PaymentTransaction txn =
        PaymentTransaction.createClaimed(
            UUID.randomUUID(),
            orgId,
            provider,
            ref,
            amount,
            order.getCurrency(),
            order.getCustomerId(),
            null, // not a shopper claim — staff match the tender at the counter
            null,
            null, // no shopper-uploaded proof key on the in-store path
            null,
            now,
            now);
    txn.verify(verifiedBy, now);
    txn.applyReconciliation(PaymentReconciliationStatus.MATCHED, now);
    // The counter's drawer-day (stories/cash_shift.md): both providers are stamped so the shift
    // slip totals the counter's receipts; the gate (409 SHIFT_REQUIRED) fires here, before any
    // row of the sale is written.
    UUID shiftId = cashShifts.shiftForCounterInTx(txDsl, orgId, verifiedBy, now);
    if (shiftId != null) {
      txn.stampCashShift(shiftId);
    }

    // insertIfAbsent persists the full VERIFIED + MATCHED row (ON CONFLICT DO NOTHING). A
    // not-inserted result means this exact (provider, provider_ref) was already recorded.
    PaymentTransactionRepository txnRepo = txnRepoFactory.create(txDsl);
    PaymentTransactionRepository.Recorded rec = txnRepo.insertIfAbsent(txn);
    if (!rec.inserted()) {
      throw new ConflictException(
          "payment transaction " + provider + "/" + ref + " already recorded");
    }

    Payment payment =
        Payment.createReceived(
            UUID.randomUUID(),
            orgId,
            order.getCustomerId(),
            order.getId(),
            rec.transaction().getId(),
            amount,
            order.getCurrency(),
            now);
    paymentRepoFactory.create(txDsl).insert(payment);
    // FIRST_PAYMENT — same rule as the online path (createPrepayment): the Payment row existing is
    // the signal, not the order's resulting status.
    milestoneService.reach(txDsl, orgId, PlatformFunnelStage.FIRST_PAYMENT, now);

    log.info(
        "Recorded in-store payment {} ({}) for order {} amount={}",
        payment.getId(),
        provider,
        order.getOrderNumber(),
        payment.getAmount());
    return new InStorePayment(payment, rec.transaction());
  }

  /** One recorded in-store tender: the RECEIVED payment and its VERIFIED + MATCHED transaction. */
  public record InStorePayment(Payment payment, PaymentTransaction transaction) {}

  /**
   * The token a sale's synthesised provider refs are keyed on: the order's idempotency key when it
   * has one (stable across a retried POST), else its id.
   */
  public static String idempotencyTokenOf(SalesOrder order) {
    return (order.getIdempotencyKey() == null || order.getIdempotencyKey().isBlank())
        ? order.getId().toString()
        : order.getIdempotencyKey();
  }

  private Optional<SalesOrder> resolveOrder(
      SalesOrderRepository orderRepo, UUID orgId, OrderRef ref) {
    if (ref == null || ref.isEmpty()) {
      return Optional.empty();
    }
    if (ref.salesOrderId() != null) {
      return orderRepo.findByIdForUpdate(orgId, ref.salesOrderId());
    }
    return orderRepo.findByOrderNumberForUpdate(orgId, ref.orderNumber());
  }

  /**
   * One payment applied to an order, the transaction it was recognised from (its provider and
   * reference — which tender it was) and its refunds, oldest first.
   */
  public record PaymentWithRefunds(
      Payment payment, PaymentTransaction transaction, List<Refund> refunds) {}

  /** An order's full money story: the order header + every payment FIFO, each with refunds. */
  public record OrderPayments(SalesOrder order, List<PaymentWithRefunds> payments) {}

  /**
   * The money story of an order ({@code stories/list_order_payments.md}): every payment ever
   * applied to it regardless of status — RECEIVED, ALLOCATED, DISPUTED, REFUNDED — ordered {@code
   * received_at ASC, id ASC} (the FIFO order invoice allocation consumes them in), each carrying
   * its refunds oldest first. Standalone orphan-refund payments ({@code sales_order_id} NULL) can
   * never appear here by construction. Read-only on {@code rootDsl}, no explicit transaction.
   *
   * @throws NotFoundException if the order is not in {@code orgId}
   */
  public OrderPayments listForOrder(UUID orgId, UUID salesOrderId) {
    SalesOrder order =
        salesOrderRepoFactory
            .create(rootDsl)
            .findById(orgId, salesOrderId)
            .orElseThrow(() -> new NotFoundException("SalesOrder", salesOrderId));
    PaymentRepository paymentRepo = paymentRepoFactory.create(rootDsl);
    RefundRepository refundRepo = refundRepoFactory.create(rootDsl);
    PaymentTransactionRepository txnRepo = txnRepoFactory.create(rootDsl);
    List<PaymentWithRefunds> payments =
        paymentRepo.findByOrderId(orgId, salesOrderId).stream()
            .map(
                p ->
                    new PaymentWithRefunds(
                        p,
                        txnRepo.findById(orgId, p.getPaymentTransactionId()).orElse(null),
                        refundRepo.findByPaymentId(orgId, p.getId())))
            .toList();
    return new OrderPayments(order, payments);
  }

  /**
   * Take the order's row lock ({@code FOR UPDATE}) ahead of a claim-row lock — the lock order the
   * sweeper and the cancel path already use (order, then its open claims). Empty when the order is
   * not in {@code orgId}. Runs in {@code txDsl}.
   */
  public Optional<SalesOrder> lockOrder(DSLContext txDsl, UUID orgId, UUID orderId) {
    return salesOrderRepoFactory.create(txDsl).findByIdForUpdate(orgId, orderId);
  }

  /** Batch, non-locking read of whole orders by id (one query) for list decoration. */
  public Map<UUID, SalesOrder> findOrders(DSLContext dsl, UUID orgId, Collection<UUID> orderIds) {
    if (orderIds.isEmpty()) {
      return Map.of();
    }
    return salesOrderRepoFactory.create(dsl).findByIds(orgId, orderIds);
  }

  /**
   * Non-locking resolution of {@code ref} to its order — for read-only callers (e.g. assembling an
   * idempotent-replay response) that must not take a write lock or mutate anything.
   */
  public Optional<SalesOrder> findOrder(DSLContext txDsl, UUID orgId, OrderRef ref) {
    if (ref == null || ref.isEmpty()) {
      return Optional.empty();
    }
    SalesOrderRepository orderRepo = salesOrderRepoFactory.create(txDsl);
    if (ref.salesOrderId() != null) {
      return orderRepo.findById(orgId, ref.salesOrderId());
    }
    return orderRepo.findByOrderNumber(orgId, ref.orderNumber());
  }
}
