package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.CreditNote;
import com.loai.inventory.domain.model.CreditNoteLine;
import com.loai.inventory.domain.model.CreditNoteReason;
import com.loai.inventory.domain.model.CreditNoteStatus;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.InventoryLog;
import com.loai.inventory.domain.model.InvoiceStatus;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.domain.repository.CreditNoteRepository;
import com.loai.inventory.domain.repository.CreditNoteRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryLogRepository;
import com.loai.inventory.domain.repository.InventoryLogRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentTransactionRepositoryFactory;
import com.loai.inventory.domain.repository.RefundRepository;
import com.loai.inventory.domain.repository.RefundRepositoryFactory;
import com.loai.inventory.domain.repository.SalesInvoiceRepository;
import com.loai.inventory.domain.repository.SalesInvoiceRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refund from the receipt — a counter return in one transaction ({@code stories/counter_return.md},
 * Loyverse gap #3).
 *
 * <p>A composite over the primitives, not a fourth path: {@link CreditNoteService#issueInTx} →
 * {@link Refund#createPending} → {@link RefundService#executeInTx} (cash only) → restock, inside
 * <b>one</b> {@code transactionResult}. The same ledger rows as the three admin screens, the same
 * DEBIT transaction, the same allocation unwind, the same SETTLED flip — and the same MANAGER /
 * OWNER-threshold gates, because issuance runs unchanged. Nothing about a counter return is a
 * different <em>kind</em> of refund; it is the same refund with no waiting room.
 *
 * <p>What the primitives never had to face, and this owns:
 *
 * <ul>
 *   <li><b>The receipt is the authority for prices.</b> Lines are {@code (product, quantity)} only;
 *       description, unit price and tax rate are copied from the invoice line.
 *   <li><b>Discount proration.</b> A note credits gross lines against a net invoice; each return's
 *       share of the invoice discount is {@code round(discount × gross / invoice.subtotal)}
 *       HALF_EVEN, except the return that brings every line's credited quantity to its billed
 *       quantity, which takes the exact remainder — so partial returns are fair to the piastre and
 *       a complete return sums to exactly the grand total ({@code InvoiceService.discountToBill}'s
 *       rule, one level down).
 *   <li><b>Cash executes now; a transfer stays PENDING.</b> The refund's method is the sale's
 *       tender: CASH is handed back and executed here; INSTAPAY_IN_STORE creates a PENDING refund
 *       with method INSTAPAY_MANUAL for the existing two-step.
 *   <li><b>Restock.</b> {@code StockReason.RETURNED} — in the enum since V5, never written until
 *       now — with the order id; default on, off for damaged goods.
 *   <li><b>Idempotent, because it moves money.</b> A replay under the same key returns the prior
 *       note; a different body under the same key is a 409.
 * </ul>
 */
public final class CounterReturnService {

  private static final Logger log = LoggerFactory.getLogger(CounterReturnService.class);
  private static final int MONEY_SCALE = 2;
  private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_EVEN;

  /** Longest free-text reason a counter return may carry. */
  public static final int REASON_NOTE_MAX = 500;

  private final DSLContext rootDsl;
  private final SalesOrderRepositoryFactory orderRepoFactory;
  private final SalesInvoiceRepositoryFactory invoiceRepoFactory;
  private final CreditNoteRepositoryFactory creditNoteRepoFactory;
  private final RefundRepositoryFactory refundRepoFactory;
  private final PaymentRepositoryFactory paymentRepoFactory;
  private final PaymentTransactionRepositoryFactory txnRepoFactory;
  private final InventoryRepositoryFactory inventoryRepoFactory;
  private final InventoryLogRepositoryFactory inventoryLogRepoFactory;
  private final CreditNoteService creditNoteService;
  private final RefundService refundService;

  public CounterReturnService(
      DSLContext rootDsl,
      SalesOrderRepositoryFactory orderRepoFactory,
      SalesInvoiceRepositoryFactory invoiceRepoFactory,
      CreditNoteRepositoryFactory creditNoteRepoFactory,
      RefundRepositoryFactory refundRepoFactory,
      PaymentRepositoryFactory paymentRepoFactory,
      PaymentTransactionRepositoryFactory txnRepoFactory,
      InventoryRepositoryFactory inventoryRepoFactory,
      InventoryLogRepositoryFactory inventoryLogRepoFactory,
      CreditNoteService creditNoteService,
      RefundService refundService) {
    this.rootDsl = rootDsl;
    this.orderRepoFactory = orderRepoFactory;
    this.invoiceRepoFactory = invoiceRepoFactory;
    this.creditNoteRepoFactory = creditNoteRepoFactory;
    this.refundRepoFactory = refundRepoFactory;
    this.paymentRepoFactory = paymentRepoFactory;
    this.txnRepoFactory = txnRepoFactory;
    this.inventoryRepoFactory = inventoryRepoFactory;
    this.inventoryLogRepoFactory = inventoryLogRepoFactory;
    this.creditNoteService = creditNoteService;
    this.refundService = refundService;
  }

  // Wire shapes

  /** How the money goes back: the sale's tender decides. */
  public enum RefundMode {
    /** A CASH sale — the refund is EXECUTED in the same transaction, cash across the counter. */
    IMMEDIATE_CASH,
    /** An InstaPay sale — a PENDING refund the merchant executes after sending the transfer. */
    PENDING_TRANSFER
  }

  /** One invoice line as the return sheet sees it. {@code unitRefund} is display-only. */
  public record ReturnableLine(
      UUID productId,
      String description,
      BigDecimal unitPrice,
      BigDecimal taxRate,
      int billed,
      int returned,
      int returnable,
      BigDecimal unitRefund) {}

  /** The preview behind {@code GET /sales-orders/{id}/returnable}. */
  public record Returnable(
      SalesOrder order,
      SalesInvoice invoice,
      PaymentProvider tender,
      RefundMode refundMode,
      List<ReturnableLine> lines) {}

  public record LineInput(UUID productId, int quantity) {}

  public record ReturnCommand(List<LineInput> lines, boolean restock, String reasonNote) {}

  /** A restocked line: the quantity back on the shelf and the ledger's running stock after it. */
  public record StockMove(UUID productId, int quantity, int stockAfter) {}

  /** The result of one counter return — or of its replay ({@code replayed} then true). */
  public record Returned(
      CreditNote creditNote,
      List<CreditNoteLine> lines,
      Refund refund,
      PaymentTransaction debit,
      List<StockMove> stock,
      boolean replayed) {}

  // Preview

  /**
   * What the receipt can still take back: the live invoice's lines with their billed / already
   * credited / returnable quantities and a per-unit net refund for display, plus the tender and the
   * refund mode so the client says the right sentence before the tap. Read-only.
   */
  public Returnable returnable(UUID orgId, UUID orderId) {
    SalesOrderRepository orderRepo = orderRepoFactory.create(rootDsl);
    SalesOrder order =
        orderRepo
            .findById(orgId, orderId)
            .orElseThrow(() -> new NotFoundException("SalesOrder", orderId));
    requireCounterSale(order);
    SalesInvoiceRepository invoiceRepo = invoiceRepoFactory.create(rootDsl);
    SalesInvoice invoice = liveInvoice(invoiceRepo, orgId, order);
    List<SalesInvoiceLine> invoiceLines = invoiceRepo.findLinesByInvoiceId(invoice.getId());
    Map<UUID, Integer> credited =
        creditNoteRepoFactory.create(rootDsl).creditedQuantityByProduct(orgId, invoice.getId());
    PaymentProvider tender = tenderOf(rootDsl, orgId, order);

    List<ReturnableLine> lines = new ArrayList<>(invoiceLines.size());
    for (SalesInvoiceLine l : invoiceLines) {
      int returned = credited.getOrDefault(l.getProductId(), 0);
      int returnable = Math.max(l.getQuantity() - returned, 0);
      // Display only: the per-unit net = gross + tax − the unit's share of the invoice discount.
      // The POST recomputes with the remainder rule and is the authority.
      BigDecimal unitShare = share(invoice, l.getUnitPrice());
      BigDecimal unitTax =
          l.getUnitPrice().multiply(l.getTaxRate()).setScale(MONEY_SCALE, MONEY_ROUNDING);
      BigDecimal unitRefund = l.getUnitPrice().add(unitTax).subtract(unitShare);
      lines.add(
          new ReturnableLine(
              l.getProductId(),
              l.getDescription(),
              l.getUnitPrice(),
              l.getTaxRate(),
              l.getQuantity(),
              returned,
              returnable,
              unitRefund.max(BigDecimal.ZERO)));
    }
    return new Returnable(order, invoice, tender, modeFor(tender), lines);
  }

  // Return

  /**
   * The counter return. {@code callerIsOwnerOrAdmin} feeds the existing OWNER threshold gate inside
   * issuance; {@code actorUserId} verifies the DEBIT transaction (a CASH sale) and is the ledger's
   * actor. {@code idempotencyKey} is required — see the class doc.
   */
  public Returned returnFromReceipt(
      UUID orgId,
      UUID orderId,
      ReturnCommand cmd,
      String idempotencyKey,
      ActorContext actor,
      UUID actorUserId,
      boolean callerIsOwnerOrAdmin) {
    validate(cmd, idempotencyKey);
    if (actorUserId == null) {
      throw new ValidationException("actor identity is required");
    }

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          SalesOrderRepository orderRepo = orderRepoFactory.create(txDsl);
          SalesInvoiceRepository invoiceRepo = invoiceRepoFactory.create(txDsl);
          CreditNoteRepository creditNoteRepo = creditNoteRepoFactory.create(txDsl);
          RefundRepository refundRepo = refundRepoFactory.create(txDsl);
          InventoryRepository inventoryRepo = inventoryRepoFactory.create(txDsl);
          InventoryLogRepository inventoryLogRepo = inventoryLogRepoFactory.create(txDsl);

          // 0. Replay: the same key returns the prior note (and 409s a different body).
          var prior = creditNoteRepo.findByIdempotencyKey(orgId, idempotencyKey);
          if (prior.isPresent()) {
            return replay(txDsl, orgId, prior.get(), cmd);
          }

          // 1. Lock the order for the rest of the txn — two returns on one receipt serialize —
          //    and require the counter shape.
          SalesOrder order =
              orderRepo
                  .findByIdForUpdate(orgId, orderId)
                  .orElseThrow(() -> new NotFoundException("SalesOrder", orderId));
          requireCounterSale(order);
          SalesInvoice invoice = liveInvoice(invoiceRepo, orgId, order);
          List<SalesInvoiceLine> invoiceLines = invoiceRepo.findLinesByInvoiceId(invoice.getId());
          Map<UUID, SalesInvoiceLine> byProduct = new LinkedHashMap<>();
          for (SalesInvoiceLine l : invoiceLines) {
            byProduct.put(l.getProductId(), l);
          }

          // 2. Validate the lines against what the receipt can still give back.
          Map<UUID, Integer> credited =
              creditNoteRepo.creditedQuantityByProduct(orgId, invoice.getId());
          Map<UUID, Integer> asked = new LinkedHashMap<>();
          for (LineInput in : cmd.lines()) {
            asked.merge(in.productId(), in.quantity(), Integer::sum);
          }
          for (Map.Entry<UUID, Integer> e : asked.entrySet()) {
            SalesInvoiceLine l = byProduct.get(e.getKey());
            if (l == null) {
              throw new ValidationException(
                  "product " + e.getKey() + " is not on invoice " + invoice.getInvoiceNumber());
            }
            int returnable = l.getQuantity() - credited.getOrDefault(e.getKey(), 0);
            if (e.getValue() > returnable) {
              throw new ConflictException(
                  "cannot return "
                      + e.getValue()
                      + " × "
                      + l.getDescription()
                      + ": returnable "
                      + Math.max(returnable, 0)
                      + " of "
                      + l.getQuantity()
                      + " billed on "
                      + invoice.getInvoiceNumber());
            }
          }

          // 3. Build the note's lines from the INVOICE (the receipt is the authority for prices)
          //    and its share of the invoice discount by the proration rule.
          List<CreditNoteService.LineSpec> specs = new ArrayList<>(asked.size());
          BigDecimal gross = BigDecimal.ZERO;
          boolean completesInvoice = true;
          for (SalesInvoiceLine l : invoiceLines) {
            int qty = asked.getOrDefault(l.getProductId(), 0);
            int afterThis = credited.getOrDefault(l.getProductId(), 0) + qty;
            if (afterThis < l.getQuantity()) {
              completesInvoice = false;
            }
            if (qty > 0) {
              specs.add(
                  new CreditNoteService.LineSpec(
                      l.getProductId(), l.getDescription(), qty, l.getUnitPrice(), l.getTaxRate()));
              gross =
                  gross.add(
                      l.getUnitPrice()
                          .multiply(BigDecimal.valueOf(qty))
                          .setScale(MONEY_SCALE, MONEY_ROUNDING));
            }
          }
          BigDecimal discountShare =
              discountShare(txDsl, orgId, invoice, gross, completesInvoice, creditNoteRepo);

          // 4. Restock first (the writes roll back with everything else): lock the rows, refuse an
          //    untracked product before any money moves, then +stock with the RETURNED reason.
          List<StockMove> moves = new ArrayList<>();
          if (cmd.restock()) {
            List<UUID> productIds = asked.keySet().stream().sorted().toList();
            Map<UUID, Inventory> locked = inventoryRepo.lockForUpdate(orgId, productIds);
            for (UUID pid : productIds) {
              if (locked.get(pid) == null) {
                throw new ConflictException(
                    "product "
                        + byProduct.get(pid).getDescription()
                        + " ("
                        + pid
                        + ") is not tracked in inventory; retry with restock:false");
              }
            }
            for (UUID pid : productIds) {
              int qty = asked.get(pid);
              Inventory updated =
                  inventoryRepo.adjustQuantities(orgId, pid, qty, 0, locked.get(pid).getVersion());
              InventoryLog row =
                  inventoryLogRepo.insert(
                      orgId,
                      pid,
                      qty,
                      0,
                      updated.getStockQty(),
                      updated.getReservedQty(),
                      StockReason.RETURNED,
                      order.getId(),
                      actor);
              moves.add(new StockMove(pid, qty, row.getStockAfter()));
            }
          }

          // 5. Issue — the invoice lock, the cumulative cap and the OWNER gate run unchanged.
          CreditNoteService.IssueCommand issue =
              new CreditNoteService.IssueCommand(
                  invoice.getId(),
                  CreditNoteReason.RETURN,
                  cmd.reasonNote(),
                  specs,
                  discountShare,
                  idempotencyKey);
          CreditNoteService.Issued issued =
              creditNoteService.issueInTx(txDsl, orgId, issue, callerIsOwnerOrAdmin, now);
          CreditNote note = issued.creditNote();
          if (cmd.restock()) {
            note.markRestocked(now);
            creditNoteRepo.updateRestocked(note);
          }

          // 6. The refund: the sale's tender decides the method and whether it executes now.
          PaymentProvider tender = tenderOf(txDsl, orgId, order);
          PaymentProvider method =
              tender == PaymentProvider.CASH
                  ? PaymentProvider.CASH
                  : PaymentProvider.INSTAPAY_MANUAL;
          Refund refund =
              Refund.createPending(
                  UUID.randomUUID(),
                  orgId,
                  note.getCustomerId(),
                  note.getId(),
                  null,
                  note.getTotal(),
                  note.getCurrency(),
                  method,
                  "counter return of " + order.getOrderNumber(),
                  now);
          refundRepo.insert(refund);
          PaymentTransaction debit = null;
          if (tender == PaymentProvider.CASH) {
            RefundService.Executed executed =
                refundService.executeInTx(txDsl, orgId, refund.getId(), null, actorUserId, now);
            refund = executed.refund();
            debit = executed.debit();
            // executeInTx settles the note when covered; reload so the response says SETTLED.
            note =
                creditNoteRepo
                    .findById(orgId, note.getId())
                    .orElseThrow(() -> new IllegalStateException("credit note vanished"));
          }

          log.info(
              "Counter return order={} orgId={} note={} total={} discountShare={} refund={} {} restocked={}",
              order.getOrderNumber(),
              orgId,
              note.getCreditNoteNumber(),
              note.getTotal(),
              discountShare,
              refund.getId(),
              refund.getStatus(),
              cmd.restock());
          return new Returned(note, issued.lines(), refund, debit, moves, false);
        });
  }

  // Rules

  private static void requireCounterSale(SalesOrder order) {
    if (order.getChannel() != OrderChannel.IN_STORE || order.getStatus() != OrderStatus.CLOSED) {
      throw new ConflictException(
          "order "
              + order.getOrderNumber()
              + " is "
              + order.getChannel()
              + "/"
              + order.getStatus()
              + "; a counter return needs a CLOSED IN_STORE sale — credit any other order through"
              + " POST /credit-notes");
    }
  }

  /** The sale's one live (non-VOID) invoice — a reissue leaves the replacement as the live one. */
  private static SalesInvoice liveInvoice(
      SalesInvoiceRepository invoiceRepo, UUID orgId, SalesOrder order) {
    List<SalesInvoice> live =
        invoiceRepo.findByOrderId(orgId, order.getId()).stream().filter(i -> !i.isVoid()).toList();
    if (live.size() != 1) {
      throw new ConflictException(
          "order "
              + order.getOrderNumber()
              + " has "
              + live.size()
              + " live invoices; a counter return needs exactly one");
    }
    SalesInvoice invoice = live.get(0);
    if (invoice.getStatus() != InvoiceStatus.PAID) {
      throw new ConflictException(
          "invoice "
              + invoice.getInvoiceNumber()
              + " is "
              + invoice.getStatus()
              + "; a counter return needs a PAID invoice");
    }
    return invoice;
  }

  /** The tender the sale was paid with — the provider of its one payment's transaction. */
  private PaymentProvider tenderOf(DSLContext dsl, UUID orgId, SalesOrder order) {
    List<Payment> payments = paymentRepoFactory.create(dsl).findByOrderId(orgId, order.getId());
    if (payments.isEmpty()) {
      throw new ConflictException(
          "order " + order.getOrderNumber() + " has no payment to refund against");
    }
    UUID txnId = payments.get(0).getPaymentTransactionId();
    return txnRepoFactory
        .create(dsl)
        .findById(orgId, txnId)
        .map(PaymentTransaction::getProvider)
        .orElseThrow(() -> new IllegalStateException("payment transaction " + txnId + " missing"));
  }

  private static RefundMode modeFor(PaymentProvider tender) {
    return tender == PaymentProvider.CASH ? RefundMode.IMMEDIATE_CASH : RefundMode.PENDING_TRANSFER;
  }

  /**
   * {@code round(invoice.discount × gross / invoice.subtotal)} HALF_EVEN; zero without a discount.
   */
  private static BigDecimal share(SalesInvoice invoice, BigDecimal gross) {
    BigDecimal discount =
        invoice.getDiscountTotal() == null ? BigDecimal.ZERO : invoice.getDiscountTotal();
    if (discount.signum() <= 0
        || invoice.getSubtotal() == null
        || invoice.getSubtotal().signum() <= 0) {
      return BigDecimal.ZERO.setScale(MONEY_SCALE);
    }
    return discount
        .multiply(gross)
        .divide(invoice.getSubtotal(), MONEY_SCALE, MONEY_ROUNDING)
        .min(discount);
  }

  /**
   * This return's share of the invoice discount: the proration for a partial return, the exact
   * remainder for the return that completes the invoice — so the notes sum to the grand total.
   */
  private static BigDecimal discountShare(
      DSLContext txDsl,
      UUID orgId,
      SalesInvoice invoice,
      BigDecimal gross,
      boolean completesInvoice,
      CreditNoteRepository creditNoteRepo) {
    BigDecimal discount =
        invoice.getDiscountTotal() == null ? BigDecimal.ZERO : invoice.getDiscountTotal();
    if (discount.signum() <= 0) {
      return BigDecimal.ZERO.setScale(MONEY_SCALE);
    }
    BigDecimal alreadyShared = BigDecimal.ZERO;
    for (CreditNote prior : creditNoteRepo.findByInvoiceId(orgId, invoice.getId(), null)) {
      if (prior.getStatus() != CreditNoteStatus.VOID) {
        alreadyShared = alreadyShared.add(prior.getDiscountTotal());
      }
    }
    BigDecimal remaining = discount.subtract(alreadyShared).max(BigDecimal.ZERO);
    if (completesInvoice) {
      return remaining.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }
    return share(invoice, gross).min(remaining);
  }

  /** The prior result under a replayed key — the same note, its refund, the live stock figures. */
  private Returned replay(DSLContext txDsl, UUID orgId, CreditNote note, ReturnCommand cmd) {
    CreditNoteRepository creditNoteRepo = creditNoteRepoFactory.create(txDsl);
    List<CreditNoteLine> lines = creditNoteRepo.findLinesByCreditNoteId(note.getId());
    // Fingerprint: the same lines and the same restock choice, else the key is being reused for a
    // different return — a 409, never a silent "here is what you got last time".
    Map<UUID, Integer> asked = new LinkedHashMap<>();
    for (LineInput in : cmd.lines()) {
      asked.merge(in.productId(), in.quantity(), Integer::sum);
    }
    Map<UUID, Integer> issued = new LinkedHashMap<>();
    for (CreditNoteLine l : lines) {
      issued.merge(l.getProductId(), l.getQuantity(), Integer::sum);
    }
    boolean restockMatches = cmd.restock() == (note.getRestockedAt() != null);
    if (!asked.equals(issued) || !restockMatches) {
      throw new ConflictException(
          "Idempotency-Key already used for credit note "
              + note.getCreditNoteNumber()
              + " with a different return");
    }
    List<Refund> refunds = refundRepoFactory.create(txDsl).list(orgId, null, note.getId(), 0, 1);
    if (refunds.isEmpty()) {
      throw new IllegalStateException("counter return " + note.getId() + " has no refund");
    }
    Refund refund = refunds.get(0);
    PaymentTransaction debit =
        refund.getPaymentTransactionId() == null
            ? null
            : txnRepoFactory
                .create(txDsl)
                .findById(orgId, refund.getPaymentTransactionId())
                .orElse(null);
    List<StockMove> moves = new ArrayList<>();
    if (note.getRestockedAt() != null) {
      InventoryRepository inventoryRepo = inventoryRepoFactory.create(txDsl);
      for (CreditNoteLine l : lines) {
        int after =
            inventoryRepo
                .findByProductId(orgId, l.getProductId())
                .map(Inventory::getStockQty)
                .orElse(0);
        moves.add(new StockMove(l.getProductId(), l.getQuantity(), after));
      }
    }
    return new Returned(note, lines, refund, debit, moves, true);
  }

  private static void validate(ReturnCommand cmd, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new ValidationException("Idempotency-Key header is required");
    }
    if (cmd == null || cmd.lines() == null || cmd.lines().isEmpty()) {
      throw new ValidationException("lines must not be empty");
    }
    for (int i = 0; i < cmd.lines().size(); i++) {
      LineInput l = cmd.lines().get(i);
      if (l == null || l.productId() == null) {
        throw new ValidationException("lines[" + i + "].product_id is required");
      }
      if (l.quantity() < 1) {
        throw new ValidationException("lines[" + i + "].quantity must be >= 1");
      }
    }
    String note = Text.normalizeText(cmd.reasonNote());
    if (note != null && note.length() > REASON_NOTE_MAX) {
      throw new ValidationException(
          "reason_note must be at most " + REASON_NOTE_MAX + " characters");
    }
  }
}
