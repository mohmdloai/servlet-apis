package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentAllocation;
import com.loai.inventory.domain.model.PaymentStatus;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.repository.PaymentAllocationRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.RefundAllocationRepositoryFactory;
import com.loai.inventory.domain.repository.SalesInvoiceRepository;
import com.loai.inventory.domain.repository.SalesInvoiceRepositoryFactory;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The post-allocation dispute lifecycle on {@link Payment} (sys-analysis/outbound/payment.md
 * §Disputed; state-machines.md F). A customer may later claim a recognized payment didn't happen,
 * wasn't authorized, or was wrong:
 *
 * <ul>
 *   <li>{@link #dispute} — ALLOCATED → DISPUTED. Freezes the money: while DISPUTED the payment
 *       cannot be allocated to new invoices nor refunded directly (domain guards on {@link
 *       Payment#allocate} / {@link Payment#recordRefund}).
 *   <li>{@link #uphold} — DISPUTED → ALLOCATED. The investigation found the payment real and
 *       correct; nothing moves.
 * </ul>
 *
 * <p>The other resolution — <b>refund</b> — is not a method here: it reuses the existing CreditNote
 * + Refund machinery unchanged. The admin issues a {@code DISPUTE_RESOLUTION} CreditNote against
 * the disputed payment's invoice and refunds it ({@code POST /credit-notes} → {@code POST /refunds}
 * → {@code POST /refunds/{id}/execute}); executing that CreditNote-backed refund drives the payment
 * to REFUNDED ({@link Payment#recordRefund} permits the allocation path on a DISPUTED payment). No
 * new orchestration — the CreditNote is the recorded refund decision. A payment allocated across
 * several invoices is refunded by one such CreditNote+Refund per invoice; the payment stays frozen
 * as DISPUTED until the cumulative refunds cover the whole amount, then → REFUNDED. Once any such
 * refund has executed, {@link #uphold} is refused — the dispute can only resolve forward to
 * REFUNDED, never back to ALLOCATED.
 *
 * <p>Disputing/un-disputing is MANAGER+ (payment.md §Authorization) and moves no money, so there is
 * no above-threshold OWNER gate.
 */
public final class PaymentDisputeService {

  private static final Logger log = LoggerFactory.getLogger(PaymentDisputeService.class);

  private final DSLContext rootDsl;
  private final PaymentRepositoryFactory paymentRepoFactory;
  private final RefundAllocationRepositoryFactory refundAllocationRepoFactory;
  private final PaymentAllocationRepositoryFactory paymentAllocationRepoFactory;
  private final SalesInvoiceRepositoryFactory invoiceRepoFactory;

  public PaymentDisputeService(
      DSLContext rootDsl,
      PaymentRepositoryFactory paymentRepoFactory,
      RefundAllocationRepositoryFactory refundAllocationRepoFactory,
      PaymentAllocationRepositoryFactory paymentAllocationRepoFactory,
      SalesInvoiceRepositoryFactory invoiceRepoFactory) {
    this.rootDsl = rootDsl;
    this.paymentRepoFactory = paymentRepoFactory;
    this.refundAllocationRepoFactory = refundAllocationRepoFactory;
    this.paymentAllocationRepoFactory = paymentAllocationRepoFactory;
    this.invoiceRepoFactory = invoiceRepoFactory;
  }

  /**
   * Flag {@code paymentId} as DISPUTED with an optional free-text {@code reason}. Rejects a payment
   * that is not ALLOCATED ({@link ConflictException}). Runs in its own transaction.
   */
  public Payment dispute(UUID orgId, UUID paymentId, String reason, UUID actorId) {
    requireIds(paymentId, actorId);
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);
          Payment payment =
              paymentRepo
                  .findByIdForUpdate(orgId, paymentId)
                  .orElseThrow(() -> new NotFoundException("Payment", paymentId));
          try {
            payment.dispute(reason, now);
          } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
          }
          paymentRepo.updateDisputeState(payment);
          log.info("Disputed payment {} orgId={} by={}", paymentId, orgId, actorId);
          return payment;
        });
  }

  /**
   * Resolve a dispute in the org's favour: DISPUTED → ALLOCATED. Rejects a payment that is not
   * DISPUTED ({@link ConflictException}). Runs in its own transaction.
   */
  public Payment uphold(UUID orgId, UUID paymentId, UUID actorId) {
    requireIds(paymentId, actorId);
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);
          Payment payment =
              paymentRepo
                  .findByIdForUpdate(orgId, paymentId)
                  .orElseThrow(() -> new NotFoundException("Payment", paymentId));
          // A dispute-resolution refund that has already executed (real money returned) cannot be
          // taken back by upholding. Its RefundAllocation can only exist post-dispute — an
          // allocation-backed refund moves a payment off ALLOCATED, the only state from which a
          // dispute can open — so its presence unambiguously means the refund resolution is
          // underway.
          if (refundAllocationRepoFactory.create(txDsl).existsForPayment(orgId, paymentId)) {
            throw new ConflictException(
                "payment "
                    + paymentId
                    + " has a dispute-resolution refund in progress; it resolves to REFUNDED, not"
                    + " ALLOCATED");
          }
          try {
            payment.uphold(now);
          } catch (IllegalStateException e) {
            throw new ConflictException(e.getMessage());
          }
          paymentRepo.updateDisputeState(payment);
          log.info("Upheld payment {} orgId={} by={}", paymentId, orgId, actorId);
          return payment;
        });
  }

  /** One allocation of the payment, joined to the invoice it funded. */
  public record AllocationView(PaymentAllocation allocation, SalesInvoice invoice) {}

  /**
   * The payment plus every invoice it was allocated to (FIFO, the order they were applied in) — the
   * dispute-resolution entry point: the refund path issues a {@code DISPUTE_RESOLUTION} CreditNote
   * <em>against one of these invoices</em>, so the read must surface them. Empty for a RECEIVED /
   * fully-unallocated payment.
   */
  public record PaymentView(Payment payment, List<AllocationView> allocations) {}

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  /** One page of the org's payments plus the total under the same filter. */
  public record PaymentPage(List<Payment> items, long total) {}

  /**
   * The org's payments worklist / ledger, optionally narrowed by {@code status} (null = any) and to
   * those still carrying an unallocated balance ({@code unallocatedOnly}). Backs {@code GET
   * /api/orgs/{orgId}/payments} — the disputes preview ({@code status=DISPUTED}) and the
   * unallocated preview ({@code unallocatedOnly}) whose totals equal the health rollup's {@code
   * open_disputes} / {@code unallocated_payments}. {@code page} floors at 0, {@code size} clamps to
   * {@code [1, MAX_PAGE_SIZE]}. Read-only. See {@code stories/org_health_rollup.md}.
   */
  public PaymentPage list(
      UUID orgId, PaymentStatus status, boolean unallocatedOnly, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    PaymentRepository repo = paymentRepoFactory.create(rootDsl);
    List<Payment> items = repo.list(orgId, status, unallocatedOnly, p * s, s);
    long total = repo.count(orgId, status, unallocatedOnly);
    return new PaymentPage(items, total);
  }

  /** Read a payment + its allocations for the GET endpoint. */
  public PaymentView get(UUID orgId, UUID id) {
    Payment payment =
        paymentRepoFactory
            .create(rootDsl)
            .findById(orgId, id)
            .orElseThrow(() -> new NotFoundException("Payment", id));
    SalesInvoiceRepository invoiceRepo = invoiceRepoFactory.create(rootDsl);
    List<AllocationView> allocations =
        paymentAllocationRepoFactory.create(rootDsl).findByPaymentId(orgId, id).stream()
            .map(
                a ->
                    new AllocationView(
                        a,
                        invoiceRepo
                            .findById(orgId, a.getSalesInvoiceId())
                            .orElseThrow(
                                () ->
                                    new IllegalStateException(
                                        "allocation "
                                            + a.getId()
                                            + " references missing invoice "
                                            + a.getSalesInvoiceId()))))
            .toList();
    return new PaymentView(payment, allocations);
  }

  private static void requireIds(UUID paymentId, UUID actorId) {
    if (paymentId == null) {
      throw new ValidationException("payment id is required");
    }
    if (actorId == null) {
      throw new ValidationException("actor identity is required");
    }
  }
}
