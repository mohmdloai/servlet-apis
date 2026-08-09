package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.repository.CreditNoteRepository;
import com.loai.inventory.domain.repository.CreditNoteRepositoryFactory;
import com.loai.inventory.domain.repository.CustomerRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentAllocationRepository;
import com.loai.inventory.domain.repository.PaymentAllocationRepositoryFactory;
import com.loai.inventory.domain.repository.SalesInvoiceRepository;
import com.loai.inventory.domain.repository.SalesInvoiceRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import com.loai.inventory.service.InvoiceService.Issued;
import com.loai.inventory.service.InvoiceService.LineSpec;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin lifecycle operations on an already-issued {@link SalesInvoice}: read, <em>void</em>
 * (cancel), and <em>reissue</em> (cancel + replace with a corrected document). Owns its transaction
 * boundary via {@code rootDsl.transactionResult(...)} — unlike {@link InvoiceService}, which is a
 * collaborator that issues invoices inside a fulfillment/sale transaction.
 *
 * <p>Reissue exists because an issued invoice is an immutable legal/tax document: when its frozen
 * numbers or customer snapshot are wrong, the customer needs a <em>corrected</em> invoice, not just
 * a cancelled one. A DELIVERED fulfillment is terminal and cannot re-fire its issuance trigger, so
 * the correction is voided-and-replaced here — reusing the very same {@link
 * InvoiceService#issueForFulfillment} collaborator the delivery path uses (one issuance routine,
 * two triggers). The partial unique index {@code sales_invoice_fulfillment_id_live_uq} lets the
 * corrected invoice coexist with its voided predecessor on the same fulfillment.
 *
 * <p>Void and reissue are legal only inside a narrow window — the invoice must be {@code ISSUED},
 * <em>unpaid</em> (no allocations) and <em>uncredited</em> (no live credit notes). Outside it the
 * correct instrument is a credit note, not a void: money or a financial trail has attached and may
 * not be silently erased.
 */
public final class InvoiceAdminService {

  private static final Logger log = LoggerFactory.getLogger(InvoiceAdminService.class);

  private final DSLContext rootDsl;
  private final SalesInvoiceRepositoryFactory invoiceRepoFactory;
  private final PaymentAllocationRepositoryFactory allocationRepoFactory;
  private final CreditNoteRepositoryFactory creditNoteRepoFactory;
  private final SalesOrderRepositoryFactory orderRepoFactory;
  private final CustomerRepositoryFactory customerRepoFactory;
  private final InvoiceService invoiceService;

  public InvoiceAdminService(
      DSLContext rootDsl,
      SalesInvoiceRepositoryFactory invoiceRepoFactory,
      PaymentAllocationRepositoryFactory allocationRepoFactory,
      CreditNoteRepositoryFactory creditNoteRepoFactory,
      SalesOrderRepositoryFactory orderRepoFactory,
      CustomerRepositoryFactory customerRepoFactory,
      InvoiceService invoiceService) {
    this.rootDsl = rootDsl;
    this.invoiceRepoFactory = invoiceRepoFactory;
    this.allocationRepoFactory = allocationRepoFactory;
    this.creditNoteRepoFactory = creditNoteRepoFactory;
    this.orderRepoFactory = orderRepoFactory;
    this.customerRepoFactory = customerRepoFactory;
    this.invoiceService = invoiceService;
  }

  /** One corrected line for a reissue. */
  public record ReissueLine(
      UUID productId, String description, int quantity, BigDecimal unitPrice, BigDecimal taxRate) {}

  /** An invoice with its lines, for read / void responses. */
  public record InvoiceView(SalesInvoice invoice, List<SalesInvoiceLine> lines) {}

  /** Read an invoice with its lines. */
  public InvoiceView get(UUID orgId, UUID id) {
    SalesInvoiceRepository invoiceRepo = invoiceRepoFactory.create(rootDsl);
    SalesInvoice invoice =
        invoiceRepo
            .findById(orgId, id)
            .orElseThrow(() -> new NotFoundException("SalesInvoice", id));
    return new InvoiceView(invoice, invoiceRepo.findLinesByInvoiceId(id));
  }

  /**
   * A worklist row: the invoice header plus its batch-loaded {@code salesOrderNumber}, so a card
   * can name its order without a per-row fetch. Lean — no lines (the {@code GET /invoices/{id}}
   * detail carries those). The customer snapshot (name/email/…) and the money meter ({@code
   * grand_total} / {@code paid_amount}) already ride on {@link SalesInvoice}.
   */
  public record InvoiceSummary(SalesInvoice invoice, String salesOrderNumber) {}

  /** One page of the invoice queue/ledger plus the filtered total (for tab badges). */
  public record InvoicePage(List<InvoiceSummary> items, long total) {}

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  /**
   * Read one page of the org's invoices — filtered by {@code status} it is a worklist ({@code
   * ?status=ISSUED} is the awaiting-payment queue, oldest first); unfiltered it is the ledger
   * (every status including VOID, newest first). Mirrors {@link RefundService#list}: {@code page}
   * floors at 0, {@code size} is clamped to {@code [1, MAX_PAGE_SIZE]}. The per-row {@code
   * salesOrderNumber} is batch-loaded — one projection per page, never per row.
   */
  public InvoicePage list(UUID orgId, InvoiceStatus status, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    SalesInvoiceRepository invoiceRepo = invoiceRepoFactory.create(rootDsl);
    List<SalesInvoice> items = invoiceRepo.list(orgId, status, p * s, s);
    long total = invoiceRepo.count(orgId, status);

    java.util.Map<UUID, String> orderNumbers =
        orderRepoFactory
            .create(rootDsl)
            .findOrderNumbersByIds(
                orgId,
                items.stream()
                    .map(SalesInvoice::getSalesOrderId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList());

    List<InvoiceSummary> views =
        items.stream()
            .map(inv -> new InvoiceSummary(inv, orderNumbers.get(inv.getSalesOrderId())))
            .toList();
    return new InvoicePage(views, total);
  }

  /** An order's billing story: the header + every invoice oldest-first, each with lines. */
  public record OrderInvoices(SalesOrder order, List<InvoiceView> invoices) {}

  /**
   * Every invoice ever issued against an order regardless of status (VOID included — a voided
   * invoice is part of the story), oldest first ({@code created_at ASC}), each with its lines, plus
   * the order header so the billing panel renders standalone. The billing mirror of {@code
   * PaymentService#listForOrder} / {@code FulfillmentService#listForOrder}. No pagination: invoice
   * count is bounded by the order's fulfillment count.
   *
   * @throws NotFoundException if the order is not in {@code orgId}
   */
  public OrderInvoices listForOrder(UUID orgId, UUID salesOrderId) {
    if (salesOrderId == null) {
      throw new ValidationException("sales order id is required");
    }
    SalesOrder order =
        orderRepoFactory
            .create(rootDsl)
            .findById(orgId, salesOrderId)
            .orElseThrow(() -> new NotFoundException("SalesOrder", salesOrderId));
    SalesInvoiceRepository invoiceRepo = invoiceRepoFactory.create(rootDsl);
    List<InvoiceView> invoices =
        invoiceRepo.findByOrderId(orgId, salesOrderId).stream()
            .map(inv -> new InvoiceView(inv, invoiceRepo.findLinesByInvoiceId(inv.getId())))
            .toList();
    return new OrderInvoices(order, invoices);
  }

  /**
   * Void an ISSUED, unpaid, uncredited invoice — cancel it without a replacement. The fulfillment
   * is left unbound (the partial unique index frees its slot for a later reissue).
   */
  public InvoiceView voidInvoice(UUID orgId, UUID id, String reason) {
    if (reason == null || reason.isBlank()) {
      throw new ValidationException("reason is required");
    }
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          SalesInvoiceRepository invoiceRepo = invoiceRepoFactory.create(txDsl);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          SalesInvoice invoice = lockVoidable(txDsl, invoiceRepo, orgId, id);
          invoice.voidInvoice(reason, now);
          invoiceRepo.updateVoidState(invoice);

          log.info(
              "Voided invoice {} (id={}) orgId={} reason={}",
              invoice.getInvoiceNumber(),
              id,
              orgId,
              reason);
          return new InvoiceView(invoice, invoiceRepo.findLinesByInvoiceId(id));
        });
  }

  /**
   * Atomically void an ISSUED, unpaid, uncredited invoice and issue a corrected replacement against
   * the same fulfillment, with admin-supplied lines. The new invoice claims a fresh number and
   * re-runs prepayment auto-allocation (the old invoice held no allocations, so the order's
   * payments are still free to consume).
   */
  public Issued reissue(UUID orgId, UUID id, String reason, List<ReissueLine> lines) {
    if (reason == null || reason.isBlank()) {
      throw new ValidationException("reason is required");
    }
    if (lines == null || lines.isEmpty()) {
      throw new ValidationException("at least one line is required");
    }
    validateReissueLines(lines);
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          SalesInvoiceRepository invoiceRepo = invoiceRepoFactory.create(txDsl);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          SalesInvoice old = lockVoidable(txDsl, invoiceRepo, orgId, id);

          // Cancel the wrong document first; the partial unique index lets the replacement bind the
          // same fulfillment once this row is VOID.
          old.voidInvoice(reason, now);
          invoiceRepo.updateVoidState(old);

          SalesOrder order =
              orderRepoFactory
                  .create(txDsl)
                  .findById(orgId, old.getSalesOrderId())
                  .orElseThrow(() -> new NotFoundException("SalesOrder", old.getSalesOrderId()));
          Customer customer = null;
          if (order.getCustomerId() != null) {
            customer =
                customerRepoFactory
                    .create(txDsl)
                    .findById(orgId, order.getCustomerId())
                    .orElse(null);
          }

          List<LineSpec> specs =
              lines.stream()
                  .map(
                      l ->
                          new LineSpec(
                              l.productId(),
                              l.description(),
                              l.quantity(),
                              l.unitPrice(),
                              l.taxRate()))
                  .toList();

          Issued issued =
              invoiceService.issueForFulfillment(
                  txDsl, orgId, order, old.getFulfillmentId(), customer, specs, now);

          // The replacement invoice re-runs prepayment auto-allocation and can land PAID. If the
          // goods are already delivered, that was the order's last open obligation — and the
          // FULFILLED → CLOSED roll-up only ever ran on delivery events, so an order corrected
          // after its final shipment could sit FULFILLED with nothing left to deliver. Same
          // transaction, same helper the delivery path uses.
          if (OrderRollUp.closeIfFullyPaid(txDsl, orgId, order, invoiceService, now)) {
            orderRepoFactory.create(txDsl).updateFulfillmentState(order);
            log.info(
                "Reissue settled order {} — rolled up FULFILLED → CLOSED", order.getOrderNumber());
          }

          log.info(
              "Reissued invoice: voided {} (id={}) -> issued {} (id={}) orgId={} fulfillment={} reason={}",
              old.getInvoiceNumber(),
              id,
              issued.invoice().getInvoiceNumber(),
              issued.invoice().getId(),
              orgId,
              old.getFulfillmentId(),
              reason);
          return issued;
        });
  }

  /**
   * Validate each corrected line here so bad input is a 400, not a 500 from {@link
   * SalesInvoiceLine#create}'s {@code IllegalArgumentException} / a DB CHECK violation (both of
   * which the servlet's generic catch maps to "Internal server error"). Same shape as {@code
   * CreditNoteService}'s per-line validation.
   */
  private void validateReissueLines(List<ReissueLine> lines) {
    for (int i = 0; i < lines.size(); i++) {
      ReissueLine line = lines.get(i);
      if (line == null) {
        throw new ValidationException("lines[" + i + "] is required");
      }
      if (line.description() == null) {
        throw new ValidationException("lines[" + i + "].description is required");
      }
      if (line.unitPrice() == null) {
        throw new ValidationException("lines[" + i + "].unit_price is required");
      }
      if (line.taxRate() == null) {
        throw new ValidationException("lines[" + i + "].tax_rate is required");
      }
      if (line.quantity() <= 0) {
        throw new ValidationException("lines[" + i + "].quantity must be > 0");
      }
      if (line.unitPrice().signum() < 0) {
        throw new ValidationException("lines[" + i + "].unit_price must be >= 0");
      }
      if (line.taxRate().signum() < 0) {
        throw new ValidationException("lines[" + i + "].tax_rate must be >= 0");
      }
    }
  }

  /**
   * Load the invoice {@code FOR UPDATE} and assert it is voidable: ISSUED, no payment allocations,
   * no live credit notes. The row lock serializes against concurrent void/reissue of the same
   * invoice; the allocation and credit-note checks draw the line between "correctable paperwork"
   * and "a financial trail that must instead be reversed with a credit note".
   */
  private SalesInvoice lockVoidable(
      DSLContext txDsl, SalesInvoiceRepository invoiceRepo, UUID orgId, UUID id) {
    SalesInvoice invoice =
        invoiceRepo
            .findByIdForUpdate(orgId, id)
            .orElseThrow(() -> new NotFoundException("SalesInvoice", id));
    if (invoice.getStatus() != InvoiceStatus.ISSUED) {
      throw new ConflictException(
          "cannot void/reissue invoice "
              + invoice.getInvoiceNumber()
              + " in status "
              + invoice.getStatus()
              + "; must be ISSUED");
    }
    PaymentAllocationRepository allocationRepo = allocationRepoFactory.create(txDsl);
    if (!allocationRepo.findByInvoiceIdForUpdate(orgId, id).isEmpty()) {
      throw new ConflictException(
          "cannot void/reissue invoice "
              + invoice.getInvoiceNumber()
              + "; it has payment allocations — reverse with a credit note instead");
    }
    CreditNoteRepository creditNoteRepo = creditNoteRepoFactory.create(txDsl);
    if (creditNoteRepo.sumIssuedTotalByInvoice(orgId, id).signum() != 0) {
      throw new ConflictException(
          "cannot void/reissue invoice "
              + invoice.getInvoiceNumber()
              + "; it has live credit notes — reverse those first");
    }
    return invoice;
  }
}
