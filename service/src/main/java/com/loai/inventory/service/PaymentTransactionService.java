package com.loai.inventory.service;

import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Payment;
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
