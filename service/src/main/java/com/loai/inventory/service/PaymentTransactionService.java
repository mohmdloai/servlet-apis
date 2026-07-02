package com.loai.inventory.service;

import com.loai.inventory.common.exception.AppException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentDirection;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.PaymentVerificationStatus;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentTransactionRepository;
import com.loai.inventory.domain.repository.PaymentTransactionRepositoryFactory;
import com.loai.inventory.service.PaymentService.OrderRef;
import com.loai.inventory.service.PaymentService.Reconciliation;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Online-payment TX: an admin records a customer-claimed manual InstaPay transfer and verifies it
 * in one action, then reconciles it against the order.
 *
 * <p>Per the slice spec {@code stories/accept_online_payment.md}: the whole flow runs in one DB
 * transaction so the transaction-verify and the (MATCHED) payment-creation + order flip commit
 * atomically. Recording is idempotent on {@code (provider, provider_ref)} — replaying the same
 * real-world money event returns the prior outcome without creating a second payment.
 */
public final class PaymentTransactionService {

  private static final Logger log = LoggerFactory.getLogger(PaymentTransactionService.class);
  private static final String CURRENCY_EGP = "EGP";

  private final DSLContext rootDsl;
  private final PaymentTransactionRepositoryFactory txnRepoFactory;
  private final PaymentRepositoryFactory paymentRepoFactory;
  private final PaymentService paymentService;

  public PaymentTransactionService(
      DSLContext rootDsl,
      PaymentTransactionRepositoryFactory txnRepoFactory,
      PaymentRepositoryFactory paymentRepoFactory,
      PaymentService paymentService) {
    this.rootDsl = rootDsl;
    this.txnRepoFactory = txnRepoFactory;
    this.paymentRepoFactory = paymentRepoFactory;
    this.paymentService = paymentService;
  }

  /**
   * Admin-supplied verification command. {@code currency} defaults to EGP; {@code salesOrderId} /
   * {@code orderNumber} are both optional (absent → ORPHAN, not an error); the rest is the claim.
   */
  public record VerifyCommand(
      PaymentProvider provider,
      String providerRef,
      BigDecimal amount,
      String currency,
      UUID salesOrderId,
      String orderNumber,
      UUID claimedByCustomerId,
      String customerNote,
      String verificationProof,
      OffsetDateTime occurredAt) {}

  /** Result of a verify call: the transaction, its reconciliation, optional payment + order. */
  public record VerifyResult(
      PaymentTransaction transaction,
      PaymentReconciliationStatus reconciliationStatus,
      Payment payment,
      SalesOrder order,
      boolean replay) {}

  /**
   * Record-and-verify a claimed transfer. {@code verifiedBy} is the admin's {@code app_user.id}.
   * Returns the verified transaction and reconciliation outcome.
   */
  public VerifyResult verify(UUID orgId, VerifyCommand cmd, UUID verifiedBy) {
    validate(cmd, verifiedBy);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          PaymentTransactionRepository txnRepo = txnRepoFactory.create(txDsl);
          PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);

          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          String currency = normalizeCurrency(cmd.currency());

          // 1. Idempotently record the claimed transaction (UNVERIFIED).
          PaymentTransaction claim =
              PaymentTransaction.createClaimed(
                  UUID.randomUUID(),
                  orgId,
                  cmd.provider(),
                  cmd.providerRef().trim(),
                  cmd.amount(),
                  currency,
                  cmd.claimedByCustomerId(),
                  trimOrNull(cmd.customerNote()),
                  trimOrNull(cmd.verificationProof()),
                  cmd.occurredAt(),
                  now);
          PaymentTransactionRepository.Recorded rec = txnRepo.insertIfAbsent(claim);
          PaymentTransaction txn = rec.transaction();

          // 2. Idempotent replay: the event was recorded AND already verified on a prior call.
          //    Re-resolve the order (non-locking) from the same request ref so the replay response
          //    body matches the first call's shape — a retry must not silently drop the order.
          if (!rec.inserted()
              && txn.getVerificationStatus() == PaymentVerificationStatus.VERIFIED) {
            Payment prior = paymentRepo.findByTransactionId(orgId, txn.getId()).orElse(null);
            OrderRef ref = new OrderRef(cmd.salesOrderId(), cmd.orderNumber());
            SalesOrder priorOrder = paymentService.findOrder(txDsl, orgId, ref).orElse(null);
            log.info(
                "Idempotent verify replay for provider_ref={} (reconciliation={})",
                txn.getProviderRef(),
                txn.getReconciliationStatus());
            return new VerifyResult(txn, txn.getReconciliationStatus(), prior, priorOrder, true);
          }

          // 3. Verify (UNVERIFIED → VERIFIED).
          txn.verify(verifiedBy, now);

          // 4. Reconcile against the order in the same transaction.
          OrderRef ref = new OrderRef(cmd.salesOrderId(), cmd.orderNumber());
          Reconciliation reconciliation = paymentService.reconcileAndCreate(txDsl, orgId, txn, ref);

          // 5. Stamp the reconciliation outcome and persist the transaction's new state.
          txn.applyReconciliation(reconciliation.status(), now);
          txnRepo.update(txn);

          return new VerifyResult(
              txn,
              reconciliation.status(),
              reconciliation.payment(),
              reconciliation.order(),
              false);
        });
  }

  /**
   * Resolve an ORPHAN transaction by matching it to an admin-chosen order, reusing the same
   * reconcile / prepaid / SO→PAID path as the automated online flow. This is the manual override
   * the docs require before launch ({@code state-machines.md}: "manual InstaPay will produce
   * orphans on day 1"); authorization (MANAGER+) is enforced at the servlet.
   *
   * <p>The transaction must be a VERIFIED CREDIT currently in ORPHAN. The chosen {@code ref} is fed
   * to {@link PaymentService#reconcileAndCreate}: on an exact cover ({@code amount == grand_total −
   * prepaid}) this creates the {@link Payment} and flips the order to PAID, and the transaction
   * moves ORPHAN → MATCHED. Any non-MATCHED outcome (order not found, order not PENDING_PAYMENT,
   * under/overpaid) persists nothing and is reported as a 4xx so the transaction stays in the
   * orphan queue — partial / overpaid manual matches are intentionally not supported in this slice.
   *
   * <p>Idempotent: re-resolving a transaction that already has a Payment returns that payment
   * (replay) instead of erroring, mirroring {@link #verify}.
   */
  public VerifyResult resolveOrphan(UUID orgId, UUID transactionId, OrderRef ref, UUID resolvedBy) {
    if (transactionId == null) {
      throw new ValidationException("transaction id is required");
    }
    if (resolvedBy == null) {
      throw new ValidationException("resolver identity is required");
    }
    if (ref == null || ref.isEmpty()) {
      throw new ValidationException("sales_order_id or order_number is required");
    }

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          PaymentTransactionRepository txnRepo = txnRepoFactory.create(txDsl);
          PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);

          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          PaymentTransaction txn =
              txnRepo
                  .findByIdForUpdate(orgId, transactionId)
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "payment transaction " + transactionId + " not found"));

          // Idempotent replay: a Payment already exists for this txn → it was resolved before.
          // Return it with the order it is actually attached to (not the request ref).
          Optional<Payment> prior = paymentRepo.findByTransactionId(orgId, transactionId);
          if (prior.isPresent()) {
            Payment payment = prior.get();
            SalesOrder priorOrder =
                payment.getSalesOrderId() == null
                    ? null
                    : paymentService
                        .findOrder(txDsl, orgId, new OrderRef(payment.getSalesOrderId(), null))
                        .orElse(null);
            log.info(
                "Idempotent orphan-resolve replay for transaction {} (reconciliation={})",
                transactionId,
                txn.getReconciliationStatus());
            return new VerifyResult(txn, txn.getReconciliationStatus(), payment, priorOrder, true);
          }

          // Only a VERIFIED, CREDIT, still-ORPHAN transaction can be matched to an order.
          if (txn.getDirection() != PaymentDirection.CREDIT) {
            throw new ValidationException("only CREDIT transactions can be matched to an order");
          }
          if (txn.getVerificationStatus() != PaymentVerificationStatus.VERIFIED) {
            throw new ConflictException(
                "transaction is "
                    + txn.getVerificationStatus()
                    + "; only a VERIFIED transaction can be resolved");
          }
          if (txn.getReconciliationStatus() != PaymentReconciliationStatus.ORPHAN) {
            throw new ConflictException(
                "transaction reconciliation is "
                    + txn.getReconciliationStatus()
                    + "; only an ORPHAN transaction can be resolved");
          }

          Reconciliation rec = paymentService.reconcileAndCreate(txDsl, orgId, txn, ref);

          if (rec.status() != PaymentReconciliationStatus.MATCHED) {
            // Throw so the whole txn rolls back (an OVERPAID reconcile writes a Payment and flips
            // the order — the rollback discards that) and the transaction stays ORPHAN in the
            // queue. Manual matches accept only an exact cover in this slice.
            throw orphanRejection(rec, txn);
          }

          txn.applyReconciliation(rec.status(), now);
          txnRepo.update(txn);

          log.info(
              "Resolved ORPHAN transaction {} → MATCHED on order {} (payment {}) by {}",
              transactionId,
              rec.order().getOrderNumber(),
              rec.payment().getId(),
              resolvedBy);
          return new VerifyResult(txn, rec.status(), rec.payment(), rec.order(), false);
        });
  }

  /** Precise 4xx for a manual match that {@code reconcileAndCreate} did not turn into a MATCHED. */
  private static AppException orphanRejection(Reconciliation rec, PaymentTransaction txn) {
    SalesOrder order = rec.order();
    return switch (rec.status()) {
      case ORPHAN ->
          order == null
              ? new NotFoundException("no order found for the given reference in this org")
              : new ConflictException(
                  "order "
                      + order.getOrderNumber()
                      + " is "
                      + order.getStatus()
                      + "; only a PENDING_PAYMENT order can accept a matched payment");
      case UNDERPAID ->
          new ConflictException(
              "amount "
                  + txn.getAmount()
                  + " is less than the order's outstanding "
                  + outstanding(order)
                  + "; partial orphan matches are not supported");
      case OVERPAID ->
          new ConflictException(
              "amount "
                  + txn.getAmount()
                  + " exceeds the order's outstanding "
                  + outstanding(order)
                  + "; overpaid orphan matches are not supported");
      default -> new ConflictException("cannot resolve orphan (outcome " + rec.status() + ")");
    };
  }

  private static BigDecimal outstanding(SalesOrder order) {
    return order.getGrandTotal().subtract(order.getPrepaidAmount());
  }

  private void validate(VerifyCommand cmd, UUID verifiedBy) {
    if (cmd == null) {
      throw new ValidationException("request body is required");
    }
    if (verifiedBy == null) {
      throw new ValidationException("verifier identity is required");
    }
    if (cmd.provider() == null) {
      throw new ValidationException("provider is required");
    }
    if (cmd.providerRef() == null || cmd.providerRef().isBlank()) {
      throw new ValidationException("provider_ref is required");
    }
    if (cmd.amount() == null) {
      throw new ValidationException("amount is required");
    }
    if (cmd.amount().signum() <= 0) {
      throw new ValidationException("amount must be > 0");
    }
  }

  private static String normalizeCurrency(String currency) {
    if (currency == null || currency.isBlank()) {
      return CURRENCY_EGP;
    }
    return currency.trim().toUpperCase();
  }

  private static String trimOrNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
