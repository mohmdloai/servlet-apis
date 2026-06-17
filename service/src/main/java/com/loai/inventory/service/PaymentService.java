package com.loai.inventory.service;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
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

  private final PaymentRepositoryFactory paymentRepoFactory;
  private final SalesOrderRepositoryFactory salesOrderRepoFactory;

  public PaymentService(
      PaymentRepositoryFactory paymentRepoFactory,
      SalesOrderRepositoryFactory salesOrderRepoFactory) {
    this.paymentRepoFactory = paymentRepoFactory;
    this.salesOrderRepoFactory = salesOrderRepoFactory;
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
      log.info(
          "Reconcile UNDERPAID: order {} outstanding {} > amount {}",
          order.getOrderNumber(),
          outstanding,
          txn.getAmount());
      return new Reconciliation(PaymentReconciliationStatus.UNDERPAID, null, order);
    }
    if (cmp > 0) {
      log.info(
          "Reconcile OVERPAID: order {} outstanding {} < amount {}",
          order.getOrderNumber(),
          outstanding,
          txn.getAmount());
      return new Reconciliation(PaymentReconciliationStatus.OVERPAID, null, order);
    }

    // MATCHED — exact cover. Create the payment, then flip the order to PAID in the same txn.
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
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
    PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);
    paymentRepo.insert(payment);

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
