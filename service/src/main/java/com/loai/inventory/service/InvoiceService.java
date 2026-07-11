package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentAllocation;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.PaymentAllocationRepository;
import com.loai.inventory.domain.repository.PaymentAllocationRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.SalesInvoiceRepository;
import com.loai.inventory.domain.repository.SalesInvoiceRepositoryFactory;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Issue a {@link SalesInvoice} for a fulfillment and auto-allocate the order's prepayment FIFO —
 * the "magic moment" shared by both sales channels.
 *
 * <p>Collaborator service — like {@code ReservationService} and {@code
 * PaymentService.reconcileAndCreate}, it runs entirely inside the caller's transaction ({@code
 * txDsl}) and never opens its own. The caller owns the transaction boundary:
 *
 * <ul>
 *   <li>online: {@link FulfillmentService#markDelivered} calls this on the SHIPPED → DELIVERED
 *       transition, so issuance + allocation commit with the delivery.
 *   <li>in-store: {@link SalesOrderService#placeInStoreSale} calls this in the single checkout txn,
 *       right after the same-txn Payment is created — auto-allocation then finds and consumes it.
 * </ul>
 *
 * <p>Allocation reads the order's unallocated Payments FIFO ({@code received_at ASC, id ASC},
 * {@code FOR UPDATE}) and consumes them up to the invoice's {@code grand_total}: a {@link
 * PaymentAllocation} per consumed payment, {@code payment.unallocated_amount}/status advanced, and
 * {@code sales_invoice.paid_amount}/status accrued (→ PAID at {@code grand_total}). v1 has no
 * per-fulfillment discount proration ({@code discount_total} is 0).
 */
public final class InvoiceService {

  private static final Logger log = LoggerFactory.getLogger(InvoiceService.class);

  /**
   * Postgres-named unique index behind {@code UNIQUE (org_id, invoice_number)} on sales_invoice.
   */
  private static final String INVOICE_NUMBER_CONSTRAINT = "sales_invoice_org_id_invoice_number_key";

  private final SalesInvoiceRepositoryFactory invoiceRepoFactory;
  private final PaymentRepositoryFactory paymentRepoFactory;
  private final PaymentAllocationRepositoryFactory allocationRepoFactory;

  public InvoiceService(
      SalesInvoiceRepositoryFactory invoiceRepoFactory,
      PaymentRepositoryFactory paymentRepoFactory,
      PaymentAllocationRepositoryFactory allocationRepoFactory) {
    this.invoiceRepoFactory = invoiceRepoFactory;
    this.paymentRepoFactory = paymentRepoFactory;
    this.allocationRepoFactory = allocationRepoFactory;
  }

  /** One invoice line to bill: a snapshot of the order line at the delivered/sold quantity. */
  public record LineSpec(
      UUID productId, String description, int quantity, BigDecimal unitPrice, BigDecimal taxRate) {}

  /** Placeholder id for throwaway lines built only to value {@code specs} (never persisted). */
  private static final UUID VALUATION_PLACEHOLDER_ID = new UUID(0L, 0L);

  /**
   * The grand total an invoice would carry for {@code specs} — the sum of each line's (subtotal +
   * tax) at the canonical money scale. Sizes a failed-fulfillment refund to the exact value of the
   * invoice that fulfillment would have produced at delivery, so the customer is refunded for the
   * goods they didn't receive and no more. Reuses {@link SalesInvoiceLine#create} so the arithmetic
   * is identical to real issuance (same scale, same rounding).
   */
  public static BigDecimal grandTotalOf(List<LineSpec> specs) {
    BigDecimal total = BigDecimal.ZERO;
    for (LineSpec spec : specs) {
      SalesInvoiceLine line =
          SalesInvoiceLine.create(
              VALUATION_PLACEHOLDER_ID,
              VALUATION_PLACEHOLDER_ID,
              spec.productId(),
              spec.description(),
              spec.quantity(),
              spec.unitPrice(),
              spec.taxRate());
      total = total.add(line.getLineTotal());
    }
    return total;
  }

  /**
   * The issued invoice, its lines, the allocations auto-created from prepayment, and the Payment
   * objects those allocations mutated (carrying their post-allocation status / unallocated balance
   * — the FIFO loader works on its own copies, so callers needing the fresh state read these).
   */
  public record Issued(
      SalesInvoice invoice,
      List<SalesInvoiceLine> lines,
      List<PaymentAllocation> allocations,
      List<Payment> consumedPayments) {}

  /**
   * Build a DRAFT invoice from {@code lineSpecs}, issue it with a gapless per-org per-year number,
   * and auto-allocate the order's prepayment FIFO up to its grand total. Runs in {@code txDsl} —
   * does not open a transaction. {@code customer} (nullable) supplies the frozen invoice snapshot.
   */
  public Issued issueForFulfillment(
      DSLContext txDsl,
      UUID orgId,
      SalesOrder order,
      UUID fulfillmentId,
      Customer customer,
      List<LineSpec> lineSpecs,
      OffsetDateTime now) {

    SalesInvoiceRepository invoiceRepo = invoiceRepoFactory.create(txDsl);
    PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);
    PaymentAllocationRepository allocationRepo = allocationRepoFactory.create(txDsl);

    // Build the invoice lines + frozen totals.
    UUID invoiceId = UUID.randomUUID();
    List<SalesInvoiceLine> invoiceLines = new ArrayList<>(lineSpecs.size());
    BigDecimal subtotal = BigDecimal.ZERO;
    BigDecimal taxTotal = BigDecimal.ZERO;
    for (LineSpec spec : lineSpecs) {
      SalesInvoiceLine il =
          SalesInvoiceLine.create(
              UUID.randomUUID(),
              invoiceId,
              spec.productId(),
              spec.description(),
              spec.quantity(),
              spec.unitPrice(),
              spec.taxRate());
      invoiceLines.add(il);
      subtotal = subtotal.add(il.getLineSubtotal());
      taxTotal = taxTotal.add(il.getLineTax());
    }

    SalesInvoice invoice =
        SalesInvoice.createDraft(
            invoiceId,
            orgId,
            order.getCustomerId(),
            order.getId(),
            fulfillmentId,
            subtotal,
            taxTotal,
            BigDecimal.ZERO, // v1: no per-fulfillment discount proration
            order.getCurrency(),
            invoiceCustomerName(customer),
            customer == null ? null : customer.getEmail(),
            customer == null ? null : customer.getPhone(),
            customer == null ? null : customer.getAddress(),
            now);

    int year = now.getYear();
    // The allocator is the single owner of the number: it both claims the gapless sequence and
    // formats INV-YYYY-NNNN. This service never constructs an invoice number itself.
    String invoiceNumber = invoiceRepo.claimInvoiceNumber(orgId, year);
    invoice.issue(invoiceNumber, now);
    try {
      invoiceRepo.insert(invoice, invoiceLines);
    } catch (DataAccessException e) {
      // A counter drifted behind the table (a seed/import/fixture wrote invoice_number without
      // advancing invoice_number_counter) makes the just-minted number already taken, so the
      // (org_id, invoice_number) unique index rejects the insert. Surface it as a 409 that names
      // the
      // remedy, not an opaque 500 — narrowed to the number constraint so any other integrity
      // violation still bubbles. The enclosing transaction rolls back cleanly.
      if (NumberSequenceConflicts.isUniqueViolationOn(e, INVOICE_NUMBER_CONSTRAINT)) {
        throw new ConflictException("Invoice number sequence is out of sync — contact support.");
      }
      throw e;
    }

    // Auto-allocate prepayment FIFO up to the invoice grand total.
    List<PaymentAllocation> allocations = new ArrayList<>();
    List<Payment> consumedPayments = new ArrayList<>();
    BigDecimal remaining = invoice.getGrandTotal();
    for (Payment payment : paymentRepo.findUnallocatedByOrderForUpdate(orgId, order.getId())) {
      if (remaining.signum() <= 0) {
        break;
      }
      BigDecimal amount = payment.getUnallocatedAmount().min(remaining);
      if (amount.signum() <= 0) {
        continue;
      }
      payment.allocate(amount, now);
      paymentRepo.updateAllocationState(payment);
      consumedPayments.add(payment);

      PaymentAllocation allocation =
          PaymentAllocation.create(
              UUID.randomUUID(),
              orgId,
              payment.getId(),
              invoiceId,
              amount,
              payment.getReceivedAt(),
              now);
      allocationRepo.insert(allocation);
      allocations.add(allocation);

      invoice.recordAllocation(amount, now);
      remaining = remaining.subtract(amount);
    }
    invoiceRepo.updatePaymentState(invoice);

    log.info(
        "Issued invoice {} (id={}) orgId={} order={} fulfillment={} grandTotal={} allocated={} status={}",
        invoice.getInvoiceNumber(),
        invoiceId,
        orgId,
        order.getOrderNumber(),
        fulfillmentId,
        invoice.getGrandTotal(),
        allocations.size(),
        invoice.getStatus());
    return new Issued(invoice, invoiceLines, allocations, consumedPayments);
  }

  /**
   * True when the order has at least one <b>live</b> (non-VOID) invoice and every live invoice is
   * PAID — the CLOSED precondition for an online order's roll-up. Read-only; runs in the caller's
   * {@code txDsl}.
   *
   * <p>VOID invoices are excluded deliberately: the cancelled predecessor of a void+reissue must
   * neither block CLOSE (a VOID is not PAID, so {@code allMatch} would wrongly fail) nor — when it
   * is the <em>only</em> invoice — let an order CLOSE vacuously over an empty set. The non-empty
   * guard handles the latter. This is safe because the roll-up is only ever evaluated on a delivery
   * event (which atomically issues an invoice for the just-delivered fulfillment); there is no path
   * that re-triggers it after a bare void, so a delivered fulfillment can never be left with only a
   * VOID invoice at the moment this runs.
   */
  public boolean allLiveInvoicesPaid(DSLContext txDsl, UUID orgId, UUID salesOrderId) {
    List<SalesInvoice> live =
        invoiceRepoFactory.create(txDsl).findByOrderId(orgId, salesOrderId).stream()
            .filter(inv -> !inv.isVoid())
            .toList();
    return !live.isEmpty() && live.stream().allMatch(SalesInvoice::isPaid);
  }

  private static String invoiceCustomerName(Customer customer) {
    if (customer == null) {
      return "Walk-in customer";
    }
    if (customer.getName() != null && !customer.getName().isBlank()) {
      return customer.getName();
    }
    if (customer.getEmail() != null && !customer.getEmail().isBlank()) {
      return customer.getEmail();
    }
    return "Customer " + customer.getId();
  }
}
