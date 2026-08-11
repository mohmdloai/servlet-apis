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
import java.math.RoundingMode;
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

  /** Money is scale-2 HALF_EVEN system-wide; the proration below is money arithmetic. */
  private static final int MONEY_SCALE = 2;

  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

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
            shippingToBill(invoiceRepo, orgId, order),
            discountToBill(invoiceRepo, orgId, order, subtotal),
            order.getCurrency(),
            invoiceCustomerName(order, customer),
            customer == null ? null : customer.getEmail(),
            // Contact-of-record for the parcel: the ORDER's frozen delivery contact (V80) when it
            // has one, else the customer row. The fallback is what keeps every pre-V80 invoice
            // rendering exactly as it did — those orders carry no snapshot, because the delivery
            // contact used to be merged destructively onto the customer and cannot be recovered
            // per-order. Email stays the customer's: that is the billing identity and the address
            // the order-view link was sent to, and it is not part of the delivery block.
            firstNonBlank(order.getDeliveryPhone(), customer == null ? null : customer.getPhone()),
            firstNonBlank(
                order.getDeliveryAddress(), customer == null ? null : customer.getAddress()),
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
   * The shipping to bill on the invoice being issued (V68, roadmap item 5): the order's frozen
   * {@code shipping_total}, charged on the <b>first live invoice only</b> — so on a
   * partial-delivery order the sum of live invoice grand totals still equals the order grand total
   * (the CLOSED roll-up and the FIFO allocator both depend on that identity). A later invoice sees
   * a live predecessor already carrying it and bills 0. Void+reissue re-carries naturally: the
   * predecessor is VOID by the time the replacement issues, so the replacement picks it back up.
   */
  private static BigDecimal shippingToBill(
      SalesInvoiceRepository invoiceRepo, UUID orgId, SalesOrder order) {
    if (order.getShippingTotal() == null || order.getShippingTotal().signum() <= 0) {
      return BigDecimal.ZERO;
    }
    boolean alreadyBilled =
        invoiceRepo.findByOrderId(orgId, order.getId()).stream()
            .filter(inv -> !inv.isVoid())
            .anyMatch(inv -> inv.getShippingTotal() != null && inv.getShippingTotal().signum() > 0);
    return alreadyBilled ? BigDecimal.ZERO : order.getShippingTotal();
  }

  /**
   * The order-level discount to bill on the invoice being issued (V72, roadmap item 9) — the
   * sibling of {@link #shippingToBill} and the reason it could not simply copy it.
   *
   * <p>Shipping is billed on the first live invoice only, because a shipping fee is indivisible: it
   * is one delivery charge and it fits inside any invoice's own total. A discount is neither. It
   * can be larger than the first fulfillment's goods value, and charging it all to invoice #1 would
   * drive that invoice's grand total negative — so it <b>prorates</b> across the invoices by their
   * share of the goods:
   *
   * <pre>
   *   remainingDiscount = order.discount_total − Σ live invoices' discount_total
   *   remainingSubtotal = order.subtotal       − Σ live invoices' subtotal
   *   bill = (invoiceSubtotal >= remainingSubtotal)   // the completing invoice
   *          ? remainingDiscount                      // takes the exact remainder
   *          : round(remainingDiscount × invoiceSubtotal / remainingSubtotal, HALF_EVEN)
   * </pre>
   *
   * <p>Three properties, all load-bearing:
   *
   * <ul>
   *   <li><b>Penny-exact.</b> Every partial share rounds, but the invoice that completes the
   *       order's goods takes the exact remainder rather than its own rounded share — so an
   *       odd-piastre discount lands whole and {@code Σ live invoice grand totals == order grand
   *       total} survives, which is the identity the CLOSED roll-up and the FIFO allocator both
   *       depend on.
   *   <li><b>Self-healing on void+reissue.</b> Both sums are re-derived from the <em>live</em> rows
   *       on every call (never from a stored cursor), so voiding an invoice returns its share to
   *       the pool and the replacement picks it back up — the same property that makes {@code
   *       shippingToBill} correct.
   *   <li><b>Per-invoice {@code grand > 0} holds.</b> The prorated share never exceeds the
   *       invoice's own subtotal: the partial branch scales by {@code invoiceSubtotal /
   *       remainingSubtotal ≤ 1} against a remainder that is itself ≤ the remaining subtotal, and
   *       the completing branch hands over a remainder bounded by that same relation.
   * </ul>
   */
  private static BigDecimal discountToBill(
      SalesInvoiceRepository invoiceRepo,
      UUID orgId,
      SalesOrder order,
      BigDecimal invoiceSubtotal) {
    if (order.getDiscountTotal() == null || order.getDiscountTotal().signum() <= 0) {
      return BigDecimal.ZERO;
    }
    List<SalesInvoice> live =
        invoiceRepo.findByOrderId(orgId, order.getId()).stream()
            .filter(inv -> !inv.isVoid())
            .toList();
    BigDecimal billedDiscount = BigDecimal.ZERO;
    BigDecimal billedSubtotal = BigDecimal.ZERO;
    for (SalesInvoice inv : live) {
      if (inv.getDiscountTotal() != null) {
        billedDiscount = billedDiscount.add(inv.getDiscountTotal());
      }
      if (inv.getSubtotal() != null) {
        billedSubtotal = billedSubtotal.add(inv.getSubtotal());
      }
    }
    BigDecimal remainingDiscount = order.getDiscountTotal().subtract(billedDiscount);
    if (remainingDiscount.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal remainingSubtotal = order.getSubtotal().subtract(billedSubtotal);
    // The completing invoice (or a degenerate zero/negative remainder, which only a reissue with
    // corrected lines can produce) takes the exact remainder — that is what keeps the sum identity.
    if (remainingSubtotal.signum() <= 0 || invoiceSubtotal.compareTo(remainingSubtotal) >= 0) {
      return remainingDiscount.min(invoiceSubtotal.max(BigDecimal.ZERO)).max(BigDecimal.ZERO);
    }
    return remainingDiscount
        .multiply(invoiceSubtotal)
        .divide(remainingSubtotal, MONEY_SCALE, MONEY_ROUNDING);
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

  /**
   * The name on the invoice's frozen contact block. The order's {@code delivery_recipient} (V80)
   * wins when present, so the block stays <em>internally coherent</em> — a name printed above a
   * phone and address that belong to someone else is worse than either choice alone. That also
   * makes this byte-identical to the pre-V80 rendering: back then the recipient had been merged
   * onto the customer row, so this method read the same string by a longer route.
   */
  private static String invoiceCustomerName(SalesOrder order, Customer customer) {
    String recipient = order == null ? null : order.getDeliveryRecipient();
    if (recipient != null && !recipient.isBlank()) {
      return recipient;
    }
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

  /** First present, non-blank value — the order's frozen snapshot, else the customer row. */
  private static String firstNonBlank(String preferred, String fallback) {
    return preferred != null && !preferred.isBlank() ? preferred : fallback;
  }
}
