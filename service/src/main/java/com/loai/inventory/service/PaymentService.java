package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentTransactionRepository;
import com.loai.inventory.domain.repository.PaymentTransactionRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconcile a VERIFIED {@link PaymentTransaction} against a sales order and, on an exact match,
 * create the {@link Payment} that flips the order to PAID.
 *
 * <p>Collaborator service — like {@code ReservationService}, it runs entirely inside the caller's
 * transaction ({@code txDsl}) and never opens its own. The caller ({@link
 * PaymentTransactionService}) owns the transaction boundary so the txn-verify and the
 * payment-creation commit (or roll back) together.
 *
 * <p>Classification (this slice — only MATCHED produces a {@link Payment}):
 *
 * <ul>
 *   <li>no/blank order ref, order not found, or order not in {@code PENDING_PAYMENT} → ORPHAN
 *   <li>{@code amount < grand_total − prepaid} → UNDERPAID
 *   <li>{@code amount > grand_total − prepaid} → OVERPAID
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

  private final PaymentRepositoryFactory paymentRepoFactory;
  private final SalesOrderRepositoryFactory salesOrderRepoFactory;
  private final PaymentTransactionRepositoryFactory txnRepoFactory;

  public PaymentService(
      PaymentRepositoryFactory paymentRepoFactory,
      SalesOrderRepositoryFactory salesOrderRepoFactory,
      PaymentTransactionRepositoryFactory txnRepoFactory) {
    this.paymentRepoFactory = paymentRepoFactory;
    this.salesOrderRepoFactory = salesOrderRepoFactory;
    this.txnRepoFactory = txnRepoFactory;
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
   * Reconcile {@code txn} against {@code ref} and, on MATCHED, create the {@link Payment} and flip
   * the order to PAID. Runs in {@code txDsl} — does not open a transaction.
   */
  public Reconciliation reconcileAndCreate(
      DSLContext txDsl, UUID orgId, PaymentTransaction txn, OrderRef ref) {

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
      // Deliberate: this is admin-entered single-call data, so a currency mismatch is treated as a
      // correctable input error (400 → fix → resubmit), not a recorded ORPHAN. The whole txn rolls
      // back, so nothing is persisted and the same provider_ref re-inserts cleanly on retry. If a
      // webhook/auto-feed ever drives this path, revisit — it would want the claim recorded
      // instead.
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
      // OVERPAID — deferred to the overpaid slice (Payment created + order → PAID with the excess
      // sitting on payment.unallocated_amount). For now record the outcome without a Payment.
      log.info(
          "Reconcile OVERPAID: order {} outstanding {} < amount {}",
          order.getOrderNumber(),
          outstanding,
          txn.getAmount());
      return new Reconciliation(PaymentReconciliationStatus.OVERPAID, null, order);
    }

    // MATCHED — exact cover. Create the payment, then flip the order to PAID in the same txn.
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Payment payment = createPrepayment(txDsl, orgId, order, txn, now);
    BigDecimal newPrepaid = order.getPrepaidAmount().add(txn.getAmount());
    order.markPaid(newPrepaid, now); // domain guard: prepaid >= grand_total, PENDING_PAYMENT → PAID
    orderRepo.updatePaymentState(order);

    log.info(
        "Reconcile MATCHED: order {} → PAID (prepaid={}), payment {} RECEIVED amount={}",
        order.getOrderNumber(),
        order.getPrepaidAmount(),
        payment.getId(),
        payment.getAmount());
    return new Reconciliation(PaymentReconciliationStatus.MATCHED, payment, order);
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
  public Payment recordInStorePayment(
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
    if (amount == null || amount.signum() <= 0) {
      throw new ValidationException("payment amount must be > 0");
    }

    // A refless tender (cash, or in-store InstaPay with no ref) gets a synthesised ref keyed on the
    // order's idempotency key — stable across a retried POST so the UNIQUE below catches the retry.
    // The order id is NOT usable here: it is a fresh random UUID on every attempt.
    String idemToken =
        (order.getIdempotencyKey() == null || order.getIdempotencyKey().isBlank())
            ? order.getId().toString()
            : order.getIdempotencyKey();
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
            null,
            null,
            now,
            now);
    txn.verify(verifiedBy, now);
    txn.applyReconciliation(PaymentReconciliationStatus.MATCHED, now);

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

    log.info(
        "Recorded in-store payment {} ({}) for order {} amount={}",
        payment.getId(),
        provider,
        order.getOrderNumber(),
        payment.getAmount());
    return payment;
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
