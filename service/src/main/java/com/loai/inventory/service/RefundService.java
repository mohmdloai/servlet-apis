package com.loai.inventory.service;

import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.CreditNote;
import com.loai.inventory.domain.model.CreditNoteStatus;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentAllocation;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.RefundAllocation;
import com.loai.inventory.domain.model.RefundStatus;
import com.loai.inventory.domain.repository.CreditNoteRepository;
import com.loai.inventory.domain.repository.CreditNoteRepositoryFactory;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentAllocationRepository;
import com.loai.inventory.domain.repository.PaymentAllocationRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentTransactionRepository;
import com.loai.inventory.domain.repository.PaymentTransactionRepositoryFactory;
import com.loai.inventory.domain.repository.RefundAllocationRepository;
import com.loai.inventory.domain.repository.RefundAllocationRepositoryFactory;
import com.loai.inventory.domain.repository.RefundRepository;
import com.loai.inventory.domain.repository.RefundRepositoryFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create, execute and cancel {@link Refund}s — the two-path money-out machinery. Owns its
 * transaction boundary via {@code rootDsl.transactionResult(...)}. See {@code
 * sys-analysis/outbound/refund.md}.
 *
 * <p>A refund is authorized by exactly one source. {@link #execute} converges both:
 *
 * <ul>
 *   <li><b>CreditNote-backed</b> — unwinds the credited invoice's {@link PaymentAllocation}s FIFO,
 *       writing {@link RefundAllocation} rows whose sum equals {@code refund.amount}; the source
 *       {@link Payment} moves PARTIALLY_REFUNDED / REFUNDED; the {@link CreditNote} → SETTLED once
 *       executed refunds cover its total. The invoice is never touched (gross rule).
 *   <li><b>Direct-from-Payment</b> — decrements {@code payment.unallocated_amount}; no
 *       RefundAllocation rows, no invoice involved.
 * </ul>
 *
 * Both create a DEBIT {@link PaymentTransaction} (created VERIFIED) linked via {@code
 * refund.payment_transaction_id}.
 */
public final class RefundService {

  private static final Logger log = LoggerFactory.getLogger(RefundService.class);
  private static final String CURRENCY_EGP = "EGP";

  private final DSLContext rootDsl;
  private final RefundRepositoryFactory refundRepoFactory;
  private final RefundAllocationRepositoryFactory refundAllocationRepoFactory;
  private final CreditNoteRepositoryFactory creditNoteRepoFactory;
  private final PaymentRepositoryFactory paymentRepoFactory;
  private final PaymentAllocationRepositoryFactory paymentAllocationRepoFactory;
  private final PaymentTransactionRepositoryFactory txnRepoFactory;
  private final OrgRepositoryFactory orgRepoFactory;

  public RefundService(
      DSLContext rootDsl,
      RefundRepositoryFactory refundRepoFactory,
      RefundAllocationRepositoryFactory refundAllocationRepoFactory,
      CreditNoteRepositoryFactory creditNoteRepoFactory,
      PaymentRepositoryFactory paymentRepoFactory,
      PaymentAllocationRepositoryFactory paymentAllocationRepoFactory,
      PaymentTransactionRepositoryFactory txnRepoFactory,
      OrgRepositoryFactory orgRepoFactory) {
    this.rootDsl = rootDsl;
    this.refundRepoFactory = refundRepoFactory;
    this.refundAllocationRepoFactory = refundAllocationRepoFactory;
    this.creditNoteRepoFactory = creditNoteRepoFactory;
    this.paymentRepoFactory = paymentRepoFactory;
    this.paymentAllocationRepoFactory = paymentAllocationRepoFactory;
    this.txnRepoFactory = txnRepoFactory;
    this.orgRepoFactory = orgRepoFactory;
  }

  /** Admin-supplied refund-creation command; exactly one of the two source ids must be set. */
  public record CreateCommand(
      UUID creditNoteId,
      UUID paymentId,
      BigDecimal amount,
      String currency,
      PaymentProvider method,
      String notes) {}

  /** The executed refund and its DEBIT transaction. */
  public record Executed(Refund refund, PaymentTransaction debit) {}

  // ---- create (PENDING) ----

  /**
   * Create a PENDING refund. {@code callerIsOwnerOrAdmin} gates the above-threshold escalation for
   * the direct-from-Payment path (the CreditNote path was already gated at issuance). Runs in its
   * own transaction.
   */
  public Refund create(UUID orgId, CreateCommand cmd, boolean callerIsOwnerOrAdmin) {
    validateCreate(cmd);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          BigDecimal amount = cmd.amount();
          String currency = normalizeCurrency(cmd.currency());

          UUID customerId;
          if (cmd.creditNoteId() != null) {
            CreditNoteRepository cnRepo = creditNoteRepoFactory.create(txDsl);
            RefundRepository refundRepo = refundRepoFactory.create(txDsl);
            CreditNote cn =
                cnRepo
                    .findById(orgId, cmd.creditNoteId())
                    .orElseThrow(() -> new NotFoundException("CreditNote", cmd.creditNoteId()));
            if (cn.getStatus() != CreditNoteStatus.ISSUED) {
              throw new ConflictException(
                  "credit note "
                      + cn.getCreditNoteNumber()
                      + " is "
                      + cn.getStatus()
                      + "; refunds require ISSUED");
            }
            BigDecimal alreadyRefunded = refundRepo.sumExecutedByCreditNote(orgId, cn.getId());
            BigDecimal remaining = cn.getTotal().subtract(alreadyRefunded);
            if (amount.compareTo(remaining) > 0) {
              throw new ConflictException(
                  "refund amount " + amount + " exceeds credit note remaining " + remaining);
            }
            customerId = cn.getCustomerId();
          } else {
            PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);
            Payment payment =
                paymentRepo
                    .findByIdForUpdate(orgId, cmd.paymentId())
                    .orElseThrow(() -> new NotFoundException("Payment", cmd.paymentId()));
            if (amount.compareTo(payment.getUnallocatedAmount()) > 0) {
              throw new ConflictException(
                  "refund amount "
                      + amount
                      + " exceeds payment unallocated "
                      + payment.getUnallocatedAmount());
            }
            // Above-threshold direct refunds escalate to OWNER (no CreditNote issuance to gate it).
            BigDecimal threshold = orgThreshold(txDsl, orgId);
            if (amount.compareTo(threshold) > 0 && !callerIsOwnerOrAdmin) {
              throw new AuthorizationException(
                  "refund amount "
                      + amount
                      + " exceeds approval threshold "
                      + threshold
                      + "; requires OWNER");
            }
            customerId = payment.getCustomerId();
          }

          Refund refund =
              Refund.createPending(
                  UUID.randomUUID(),
                  orgId,
                  customerId,
                  cmd.creditNoteId(),
                  cmd.paymentId(),
                  amount,
                  currency,
                  cmd.method(),
                  cmd.notes(),
                  now);
          refundRepoFactory.create(txDsl).insert(refund);
          log.info(
              "Created PENDING refund {} orgId={} amount={} method={} source={}",
              refund.getId(),
              orgId,
              amount,
              cmd.method(),
              refund.isCreditNoteBacked() ? "creditNote" : "payment");
          return refund;
        });
  }

  // ---- execute (EXECUTED) ----

  /**
   * Execute a PENDING refund: create the DEBIT transaction, move the source aggregates, link and
   * stamp EXECUTED. {@code providerRefOverride} is the real reference when known (else
   * synthesised). Runs in its own transaction.
   */
  public Executed execute(UUID orgId, UUID refundId, String providerRefOverride, UUID actorId) {
    if (actorId == null) {
      throw new ValidationException("actor identity is required");
    }
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          RefundRepository refundRepo = refundRepoFactory.create(txDsl);
          Refund refund =
              refundRepo
                  .findByIdForUpdate(orgId, refundId)
                  .orElseThrow(() -> new NotFoundException("Refund", refundId));
          if (refund.getStatus() != RefundStatus.PENDING) {
            throw new ConflictException(
                "refund " + refundId + " is " + refund.getStatus() + "; only PENDING can execute");
          }

          // 1. DEBIT transaction, created VERIFIED. (provider, provider_ref) UNIQUE is the
          //    double-execute backstop.
          String providerRef =
              (providerRefOverride == null || providerRefOverride.isBlank())
                  ? refund.getMethod().name() + "-" + refund.getId()
                  : providerRefOverride.trim();
          PaymentTransaction debit =
              PaymentTransaction.createVerifiedDebit(
                  UUID.randomUUID(),
                  orgId,
                  refund.getMethod(),
                  providerRef,
                  refund.getAmount(),
                  refund.getCurrency(),
                  actorId,
                  null,
                  now);
          PaymentTransactionRepository txnRepo = txnRepoFactory.create(txDsl);
          PaymentTransactionRepository.Recorded rec = txnRepo.insertIfAbsent(debit);
          if (!rec.inserted()) {
            throw new ConflictException(
                "refund DEBIT transaction "
                    + refund.getMethod()
                    + "/"
                    + providerRef
                    + " already recorded");
          }

          // 2. Move the source.
          if (refund.isCreditNoteBacked()) {
            executeCreditNoteBacked(txDsl, orgId, refund, now);
          } else {
            executeDirect(txDsl, orgId, refund, now);
          }

          // 3. Link + stamp EXECUTED.
          refund.execute(rec.transaction().getId(), now);
          refundRepo.updateExecution(refund);
          log.info(
              "Executed refund {} orgId={} amount={} debitTxn={}",
              refund.getId(),
              orgId,
              refund.getAmount(),
              rec.transaction().getId());
          return new Executed(refund, rec.transaction());
        });
  }

  /**
   * Create a <b>PENDING</b> direct (from-Payment) refund for {@code amount} against an
   * already-locked {@code payment}, <b>inside the caller's transaction</b> ({@code txDsl}). Used by
   * order cancellation to record the refund obligation atomically with the cancel + reservation
   * release.
   *
   * <p>This is step 1 of the two-step refund lifecycle ({@code refund.md}: PENDING → EXECUTED). No
   * money moves and no DEBIT Transaction is created here — the order's prepayment is left intact
   * ({@code payment.unallocated_amount} unchanged) and {@code sales_order.prepaid_amount} is not
   * reduced. The admin later performs the real reverse transfer and calls {@link #execute} (step
   * 2), which records the VERIFIED DEBIT and moves the caches. The same amount-based approval gate
   * as the manual path applies at creation: an above-threshold refund escalates to OWNER ({@code
   * callerIsOwnerOrAdmin}).
   */
  public Refund createDirectPendingInTx(
      DSLContext txDsl,
      UUID orgId,
      Payment payment,
      BigDecimal amount,
      PaymentProvider method,
      String notes,
      boolean callerIsOwnerOrAdmin,
      OffsetDateTime now) {
    if (payment == null) {
      throw new ValidationException("payment is required");
    }
    if (method == null) {
      throw new ValidationException("method is required");
    }
    if (amount == null || amount.signum() <= 0) {
      throw new ValidationException("amount must be > 0");
    }
    if (amount.compareTo(payment.getUnallocatedAmount()) > 0) {
      throw new ConflictException(
          "refund amount "
              + amount
              + " exceeds payment unallocated "
              + payment.getUnallocatedAmount());
    }
    // Same amount-based gate as the manual direct-refund path: large cash out needs OWNER.
    BigDecimal threshold = orgThreshold(txDsl, orgId);
    if (amount.compareTo(threshold) > 0 && !callerIsOwnerOrAdmin) {
      throw new AuthorizationException(
          "refund amount "
              + amount
              + " exceeds approval threshold "
              + threshold
              + "; requires OWNER");
    }

    Refund refund =
        Refund.createPending(
            UUID.randomUUID(),
            orgId,
            payment.getCustomerId(),
            null,
            payment.getId(),
            amount,
            payment.getCurrency(),
            method,
            notes,
            now);
    refundRepoFactory.create(txDsl).insert(refund);
    log.info(
        "Created PENDING direct refund {} orgId={} amount={} payment={} (awaiting execute)",
        refund.getId(),
        orgId,
        amount,
        payment.getId());
    return refund;
  }

  /**
   * Create <b>and execute</b> a direct cash refund for counter change, inside the caller's
   * transaction — the in-store overpaid auto-resolution ({@code payment.md} §Overpaid (in-store)
   * steps 4–5): the cashier hands the change back the moment the sale closes, so unlike every other
   * refund there is no PENDING window — the DEBIT transaction ({@code provider='cash'}) is recorded
   * and the refund lands EXECUTED atomically with the checkout.
   *
   * <p>No OWNER-threshold gate applies: {@code amount} is capped at the payment's unallocated
   * excess, which by construction is tender-just-received minus the invoice total — the org's net
   * money-in is always exactly the invoice total, so there is no discretionary payout to escalate.
   */
  public Executed createExecutedChangeInTx(
      DSLContext txDsl,
      UUID orgId,
      Payment payment,
      BigDecimal amount,
      UUID actorId,
      OffsetDateTime now) {
    if (payment == null) {
      throw new ValidationException("payment is required");
    }
    if (actorId == null) {
      throw new ValidationException("actor identity is required");
    }
    if (amount == null || amount.signum() <= 0) {
      throw new ValidationException("change amount must be > 0");
    }
    if (amount.compareTo(payment.getUnallocatedAmount()) > 0) {
      throw new ConflictException(
          "change " + amount + " exceeds payment unallocated " + payment.getUnallocatedAmount());
    }

    Refund refund =
        Refund.createPending(
            UUID.randomUUID(),
            orgId,
            payment.getCustomerId(),
            null,
            payment.getId(),
            amount,
            payment.getCurrency(),
            PaymentProvider.CASH,
            "counter change",
            now);
    refundRepoFactory.create(txDsl).insert(refund);

    // DEBIT transaction, created VERIFIED — same shape as execute(); the refund id makes the
    // synthesised ref unique.
    PaymentTransaction debit =
        PaymentTransaction.createVerifiedDebit(
            UUID.randomUUID(),
            orgId,
            PaymentProvider.CASH,
            PaymentProvider.CASH.name() + "-" + refund.getId(),
            amount,
            payment.getCurrency(),
            actorId,
            null,
            now);
    PaymentTransactionRepository.Recorded rec = txnRepoFactory.create(txDsl).insertIfAbsent(debit);
    if (!rec.inserted()) {
      throw new ConflictException(
          "change DEBIT transaction already recorded for refund " + refund.getId());
    }

    // Move the money off the payment (unallocated → 0 for an exact-change draw) and stamp EXECUTED.
    payment.recordRefund(amount, false, now);
    paymentRepoFactory.create(txDsl).updateAllocationState(payment);
    refund.execute(rec.transaction().getId(), now);
    refundRepoFactory.create(txDsl).updateExecution(refund);

    log.info(
        "Executed counter-change refund {} orgId={} amount={} payment={} debitTxn={}",
        refund.getId(),
        orgId,
        amount,
        payment.getId(),
        rec.transaction().getId());
    return new Executed(refund, rec.transaction());
  }

  private void executeCreditNoteBacked(
      DSLContext txDsl, UUID orgId, Refund refund, OffsetDateTime now) {
    CreditNoteRepository cnRepo = creditNoteRepoFactory.create(txDsl);
    RefundRepository refundRepo = refundRepoFactory.create(txDsl);
    PaymentAllocationRepository allocationRepo = paymentAllocationRepoFactory.create(txDsl);
    RefundAllocationRepository refundAllocationRepo = refundAllocationRepoFactory.create(txDsl);
    PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);

    CreditNote cn =
        cnRepo
            .findByIdForUpdate(orgId, refund.getCreditNoteId())
            .orElseThrow(() -> new NotFoundException("CreditNote", refund.getCreditNoteId()));
    if (cn.getStatus() != CreditNoteStatus.ISSUED) {
      throw new ConflictException(
          "credit note "
              + cn.getCreditNoteNumber()
              + " is "
              + cn.getStatus()
              + "; refunds require ISSUED");
    }

    // Unwind the invoice's payment allocations FIFO up to refund.amount.
    BigDecimal remaining = refund.getAmount();
    for (PaymentAllocation alloc :
        allocationRepo.findByInvoiceIdForUpdate(orgId, cn.getSalesInvoiceId())) {
      if (remaining.signum() <= 0) {
        break;
      }
      BigDecimal alreadyRefunded = refundAllocationRepo.sumByPaymentAllocation(alloc.getId());
      BigDecimal available = alloc.getAmount().subtract(alreadyRefunded);
      if (available.signum() <= 0) {
        continue;
      }
      BigDecimal unwind = available.min(remaining);

      refundAllocationRepo.insert(
          RefundAllocation.create(UUID.randomUUID(), refund.getId(), alloc.getId(), unwind, now));

      Payment payment =
          paymentRepo
              .findByIdForUpdate(orgId, alloc.getPaymentId())
              .orElseThrow(() -> new NotFoundException("Payment", alloc.getPaymentId()));
      payment.recordRefund(unwind, true, now);
      paymentRepo.updateAllocationState(payment);

      remaining = remaining.subtract(unwind);
    }
    if (remaining.signum() > 0) {
      throw new ConflictException(
          "refund amount exceeds the unwindable allocations of invoice behind credit note "
              + cn.getCreditNoteNumber()
              + " (short by "
              + remaining
              + ")");
    }

    // CreditNote → SETTLED once executed refunds (including this one) cover its total.
    BigDecimal executedTotal =
        refundRepo.sumExecutedByCreditNote(orgId, cn.getId()).add(refund.getAmount());
    if (executedTotal.compareTo(cn.getTotal()) >= 0) {
      cn.settle(now);
      cnRepo.updateStatus(cn);
    }
  }

  private void executeDirect(DSLContext txDsl, UUID orgId, Refund refund, OffsetDateTime now) {
    PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);
    Payment payment =
        paymentRepo
            .findByIdForUpdate(orgId, refund.getPaymentId())
            .orElseThrow(() -> new NotFoundException("Payment", refund.getPaymentId()));
    if (refund.getAmount().compareTo(payment.getUnallocatedAmount()) > 0) {
      throw new ConflictException(
          "refund amount "
              + refund.getAmount()
              + " exceeds payment unallocated "
              + payment.getUnallocatedAmount());
    }
    payment.recordRefund(refund.getAmount(), false, now);
    paymentRepo.updateAllocationState(payment);
  }

  // ---- cancel ----

  /** Cancel a PENDING refund. The authorizing CreditNote (if any) stays ISSUED. */
  public Refund cancel(UUID orgId, UUID refundId, String reason) {
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          RefundRepository refundRepo = refundRepoFactory.create(txDsl);
          Refund refund =
              refundRepo
                  .findByIdForUpdate(orgId, refundId)
                  .orElseThrow(() -> new NotFoundException("Refund", refundId));
          if (refund.getStatus() != RefundStatus.PENDING) {
            throw new ConflictException(
                "refund " + refundId + " is " + refund.getStatus() + "; only PENDING can cancel");
          }
          refund.cancel(reason, now);
          refundRepo.updateExecution(refund);
          log.info("Cancelled refund {} orgId={}", refundId, orgId);
          return refund;
        });
  }

  /** Read a refund for the GET endpoint. */
  public Refund get(UUID orgId, UUID id) {
    return refundRepoFactory
        .create(rootDsl)
        .findById(orgId, id)
        .orElseThrow(() -> new NotFoundException("Refund", id));
  }

  private BigDecimal orgThreshold(DSLContext txDsl, UUID orgId) {
    Org org =
        orgRepoFactory
            .create(txDsl)
            .findById(orgId)
            .orElseThrow(() -> new NotFoundException("Org", orgId));
    return org.getRefundApprovalThreshold();
  }

  /**
   * The org's refund-approval threshold, read inside the caller's transaction. Exposed so a caller
   * that creates several refunds in one operation (order cancel) can gate the <b>aggregate</b>
   * against the same bar that {@link #createDirectPendingInTx} applies per refund — closing the
   * structuring gap where many sub-threshold refunds sum to an above-threshold payout without
   * OWNER.
   */
  public BigDecimal approvalThreshold(DSLContext txDsl, UUID orgId) {
    return orgThreshold(txDsl, orgId);
  }

  private void validateCreate(CreateCommand cmd) {
    if (cmd == null) {
      throw new ValidationException("request body is required");
    }
    if ((cmd.creditNoteId() == null) == (cmd.paymentId() == null)) {
      throw new ValidationException("exactly one of credit_note_id / payment_id must be set");
    }
    if (cmd.amount() == null) {
      throw new ValidationException("amount is required");
    }
    if (cmd.amount().signum() <= 0) {
      throw new ValidationException("amount must be > 0");
    }
    if (cmd.method() == null) {
      throw new ValidationException("method is required");
    }
  }

  private static String normalizeCurrency(String currency) {
    if (currency == null || currency.isBlank()) {
      return CURRENCY_EGP;
    }
    return currency.trim().toUpperCase();
  }
}
