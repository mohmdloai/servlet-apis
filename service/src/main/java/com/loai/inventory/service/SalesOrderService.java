package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentAllocation;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sales-order placement for both channels.
 *
 * <ul>
 *   <li>{@link #placeOnlineOrder} — outbound flow TX-1 ({@code stories/place_online_order.md}):
 *       creates a SalesOrder + lines DRAFT → PENDING_PAYMENT and reserves stock, in one txn.
 *       Payment and fulfillment are later, separate transactions.
 *   <li>{@link #placeInStoreSale} — outbound Flow 1 ({@code stories/in_store_sale.md}): the whole
 *       sale — order, DELIVERED fulfillment, payment, issued+paid invoice, CLOSED order — in a
 *       single checkout txn, by composing {@link FulfillmentService}, {@link PaymentService} and
 *       {@link InvoiceService}. No reservation phase.
 * </ul>
 *
 * <p>The shared order-building core ({@link #buildDraftOrder}) is channel-agnostic; everything that
 * differs between the channels is the temporal sequence of transitions the two methods drive.
 */
public class SalesOrderService {

  private static final Logger log = LoggerFactory.getLogger(SalesOrderService.class);
  private static final BigDecimal DEFAULT_TAX_RATE = BigDecimal.ZERO;
  private static final Duration ONLINE_TTL = Duration.ofHours(24);
  private static final String CURRENCY_EGP = "EGP";

  private final DSLContext rootDsl;
  private final SalesOrderRepositoryFactory repoFactory;
  private final ReservationService reservationService;
  private final FulfillmentService fulfillmentService;
  private final PaymentService paymentService;
  private final InvoiceService invoiceService;
  private final NotificationService notificationService;

  public SalesOrderService(
      DSLContext rootDsl,
      SalesOrderRepositoryFactory repoFactory,
      ReservationService reservationService,
      FulfillmentService fulfillmentService,
      PaymentService paymentService,
      InvoiceService invoiceService,
      NotificationService notificationService) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.reservationService = reservationService;
    this.fulfillmentService = fulfillmentService;
    this.paymentService = paymentService;
    this.invoiceService = invoiceService;
    this.notificationService = notificationService;
  }

  /** Input contact info; {@code name} required, others optional. */
  public record CustomerInput(String name, String email, String phone, String address) {}

  /** Input order line; {@code quantity > 0}, product belongs to {@code orgId}. */
  public record OrderLineInput(UUID productId, int quantity) {}

  /**
   * Cashier tender for an in-store sale. {@code amount} is optional (defaults to the grand total).
   */
  public record PaymentInput(PaymentProvider provider, String providerRef, BigDecimal amount) {}

  /** Carries the placed order + its lines + the resolved customer for response mapping. */
  public record Placed(SalesOrder order, List<SalesOrderLine> lines, Customer customer) {}

  /** The full result of an in-store sale: every aggregate created in the one checkout txn. */
  public record InStoreSale(
      SalesOrder order,
      List<SalesOrderLine> lines,
      Fulfillment fulfillment,
      SalesInvoice invoice,
      List<SalesInvoiceLine> invoiceLines,
      Payment payment,
      List<PaymentAllocation> allocations) {}

  public Placed placeOnlineOrder(
      UUID orgId,
      CustomerInput customer,
      List<OrderLineInput> lines,
      String idempotencyKey,
      String notes,
      ActorContext actor) {

    validateOnlineInputs(customer, lines);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          SalesOrderRepository repo = repoFactory.create(txDsl);

          // 1. Idempotency short-circuit.
          if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = repo.findByIdempotencyKey(orgId, idempotencyKey);
            if (existing.isPresent()) {
              SalesOrder prior = existing.get();
              List<SalesOrderLine> priorLines = repo.findLinesByOrderId(prior.getId());
              Customer priorCustomer = loadCustomerOrThrow(repo, orgId, prior.getCustomerId());
              log.info(
                  "Idempotent replay: returning existing order id={} number={}",
                  prior.getId(),
                  prior.getOrderNumber());
              return new Placed(prior, priorLines, priorCustomer);
            }
          }

          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          BuiltOrder built =
              buildDraftOrder(
                  repo,
                  orgId,
                  OrderChannel.ONLINE,
                  customer,
                  lines,
                  idempotencyKey,
                  notes,
                  now,
                  true);
          SalesOrder order = built.order();
          List<SalesOrderLine> orderLines = built.lines();

          // Online: DRAFT → PENDING_PAYMENT with a TTL, persist, then reserve stock atomically.
          OffsetDateTime expiresAt = now.plus(ONLINE_TTL);
          order.markPendingPayment(now, expiresAt);
          repo.insert(order, orderLines);

          // Throws InsufficientStockException → rolls back the whole placement.
          reservationService.reserveForOrder(txDsl, orgId, order, orderLines, actor);

          // Notify org staff — inside the placement txn, so a rolled-back order sends nothing.
          notificationService.notifyOrgStaff(
              txDsl,
              orgId,
              NotificationType.ORDER_PLACED,
              Map.of("order_number", order.getOrderNumber()),
              "sales_order",
              order.getId(),
              "/orgs/" + orgId + "/sales-orders/" + order.getId());

          log.info(
              "Placed online order id={} orgId={} number={} customerId={} grandTotal={} lines={}",
              order.getId(),
              orgId,
              order.getOrderNumber(),
              built.customer() == null ? null : built.customer().getId(),
              order.getGrandTotal(),
              orderLines.size());

          return new Placed(order, orderLines, built.customer());
        });
  }

  /**
   * In-store sale (channel IN_STORE) — the whole transaction in one go (outbound Flow 1). The order
   * is driven DRAFT → PAID → CLOSED while, in causal order, the money is taken before the goods are
   * released: payment recorded → order PAID → DELIVERED fulfillment (stock decremented) → invoice
   * issued + auto-allocated → order CLOSED. (The invoice can only follow the fulfillment because
   * {@code sales_invoice.fulfillment_id} is NOT NULL.) Any failure (out of stock, bad tender) rolls
   * everything back.
   *
   * <p>Double-submit is blocked on two independent layers: the order's {@code (org_id,
   * idempotency_key)} UNIQUE (this method short-circuits a replay before doing any work) and the
   * payment transaction's {@code (provider, provider_ref)} UNIQUE (the cash ref is derived from the
   * same idempotency key — see {@link PaymentService#recordInStorePayment}).
   */
  public InStoreSale placeInStoreSale(
      UUID orgId,
      CustomerInput customer,
      List<OrderLineInput> lines,
      PaymentInput payment,
      String notes,
      ActorContext actor,
      String idempotencyKey,
      UUID verifiedBy) {

    validateInStoreInputs(lines, payment);

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          SalesOrderRepository repo = repoFactory.create(txDsl);

          // 0. Idempotency short-circuit (barrier 1). A retried checkout must not double-charge. We
          // don't reconstruct the full prior aggregate the way the online replay returns its order
          // —
          // reading fulfillment/invoice/payment/allocations back lands with the query slice — so a
          // retry gets a clean 409. The (org_id, idempotency_key) UNIQUE on insert is the backstop
          // behind this check.
          if (idempotencyKey != null
              && !idempotencyKey.isBlank()
              && repo.findByIdempotencyKey(orgId, idempotencyKey).isPresent()) {
            throw new ConflictException(
                "in-store sale already recorded for idempotency key " + idempotencyKey);
          }

          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          // 1. Build + persist the DRAFT order (customer optional for walk-in). The idempotency key
          // rides on the order row so the UNIQUE above is what actually enforces barrier 1.
          BuiltOrder built =
              buildDraftOrder(
                  repo,
                  orgId,
                  OrderChannel.IN_STORE,
                  customer,
                  lines,
                  idempotencyKey,
                  notes,
                  now,
                  false);
          SalesOrder order = built.order();
          List<SalesOrderLine> orderLines = built.lines();
          repo.insert(order, orderLines);

          // v1: exact tender only. The settled amount is the grand total; a different explicit
          // amount is a 400 (overpay/underpay needs the refund/credit-note machinery — later
          // slice).
          BigDecimal amount = order.getGrandTotal();
          if (payment.amount() != null && payment.amount().compareTo(amount) != 0) {
            throw new ValidationException(
                "in-store payment amount "
                    + payment.amount()
                    + " must equal grand total "
                    + amount
                    + " (exact tender only in v1)");
          }

          // 2. Take the money: payment recorded VERIFIED + MATCHED, RECEIVED (unallocated for now).
          Payment paymentRow =
              paymentService.recordInStorePayment(
                  txDsl,
                  orgId,
                  order,
                  payment.provider(),
                  payment.providerRef(),
                  amount,
                  verifiedBy,
                  now);

          // 3. Order DRAFT → PAID (prepaid = grand total) — paid before any goods move.
          order.markPaid(amount, now);
          repo.updatePaymentState(order);

          // 4. Release the goods: fulfillment created DELIVERED, stock decremented now (no
          // reservation). Done after PAID so stock never leaves the shelf for an unpaid order.
          List<FulfillmentService.DeliveredLineInput> deliveredLines =
              new ArrayList<>(orderLines.size());
          for (SalesOrderLine l : orderLines) {
            deliveredLines.add(
                new FulfillmentService.DeliveredLineInput(
                    l.getId(), l.getProductId(), l.getQuantity()));
          }
          FulfillmentService.FulfillmentView fulfillment =
              fulfillmentService.createDelivered(
                  txDsl, orgId, order, deliveredLines, null, null, null, actor, now);

          // 5. Invoice ISSUED + auto-allocation consumes the just-created payment → invoice PAID.
          List<InvoiceService.LineSpec> specs = new ArrayList<>(orderLines.size());
          for (SalesOrderLine l : orderLines) {
            specs.add(
                new InvoiceService.LineSpec(
                    l.getProductId(),
                    l.getDescription(),
                    l.getQuantity(),
                    l.getUnitPrice(),
                    l.getTaxRate()));
          }
          InvoiceService.Issued issued =
              invoiceService.issueForFulfillment(
                  txDsl,
                  orgId,
                  order,
                  fulfillment.fulfillment().getId(),
                  built.customer(),
                  specs,
                  now);

          // 6. Order PAID → CLOSED.
          order.closeInStore(now);
          repo.updateFulfillmentState(order);

          // The FIFO allocator mutated its own copy of the payment; surface that (ALLOCATED,
          // unallocated 0) rather than the as-created RECEIVED instance. v1 is exact tender, so the
          // single same-txn payment is fully consumed by the invoice — exactly one consumed
          // payment.
          // Guard the invariant so overpay/underpay (a later slice) can't silently pick the wrong
          // one.
          if (issued.consumedPayments().size() > 1) {
            throw new IllegalStateException(
                "in-store exact tender expected at most one consumed payment, got "
                    + issued.consumedPayments().size());
          }
          Payment finalPayment =
              issued.consumedPayments().isEmpty() ? paymentRow : issued.consumedPayments().get(0);

          log.info(
              "In-store sale order id={} orgId={} number={} grandTotal={} invoice={} payment={} status={}",
              order.getId(),
              orgId,
              order.getOrderNumber(),
              order.getGrandTotal(),
              issued.invoice().getInvoiceNumber(),
              paymentRow.getStatus(),
              order.getStatus());

          return new InStoreSale(
              order,
              orderLines,
              fulfillment.fulfillment(),
              issued.invoice(),
              issued.lines(),
              finalPayment,
              issued.allocations());
        });
  }

  // Shared build

  private record BuiltOrder(SalesOrder order, List<SalesOrderLine> lines, Customer customer) {}

  /**
   * Build (but do not persist) a DRAFT {@link SalesOrder} + its lines: resolve the customer,
   * snapshot each product, compute line + order totals and claim the per-org per-year order number.
   * The caller drives the channel-specific transitions and the insert. {@code customerRequired} is
   * true for online (an email is mandatory) and false for in-store walk-ins (no email → no customer
   * row).
   */
  private BuiltOrder buildDraftOrder(
      SalesOrderRepository repo,
      UUID orgId,
      OrderChannel channel,
      CustomerInput customer,
      List<OrderLineInput> lines,
      String idempotencyKey,
      String notes,
      OffsetDateTime now,
      boolean customerRequired) {

    Customer resolvedCustomer = resolveCustomer(repo, orgId, customer, customerRequired);

    // Snapshot product data per line; fail if any product is missing for this org.
    List<UUID> productIds = lines.stream().map(OrderLineInput::productId).toList();
    Map<UUID, SalesOrderRepository.ProductSnapshot> snapshots =
        repo.fetchProductSnapshots(orgId, productIds);
    for (UUID pid : productIds) {
      if (!snapshots.containsKey(pid)) {
        throw new NotFoundException("Product", pid);
      }
    }

    UUID orderId = UUID.randomUUID();
    List<SalesOrderLine> orderLines = new ArrayList<>(lines.size());
    BigDecimal subtotal = BigDecimal.ZERO;
    BigDecimal taxTotal = BigDecimal.ZERO;
    for (OrderLineInput in : lines) {
      SalesOrderRepository.ProductSnapshot snap = snapshots.get(in.productId());
      SalesOrderLine line =
          SalesOrderLine.create(
              UUID.randomUUID(),
              orderId,
              snap.productId(),
              snap.description(),
              in.quantity(),
              snap.unitPrice(),
              DEFAULT_TAX_RATE);
      orderLines.add(line);
      subtotal = subtotal.add(line.getLineSubtotal());
      taxTotal = taxTotal.add(line.getLineTax());
    }
    BigDecimal discountTotal = BigDecimal.ZERO;

    int year = now.getYear();
    long seqVal = repo.claimOrderNumber(orgId, year);
    String orderNumber = String.format("SO-%d-%05d", year, seqVal);

    SalesOrder order =
        SalesOrder.createDraft(
            orderId,
            orgId,
            resolvedCustomer == null ? null : resolvedCustomer.getId(),
            orderNumber,
            channel,
            CURRENCY_EGP,
            idempotencyKey,
            now);
    order.setTotals(subtotal, taxTotal, discountTotal, now);
    if (notes != null && !notes.isBlank()) {
      order.updateNotes(notes, now);
    }
    return new BuiltOrder(order, orderLines, resolvedCustomer);
  }

  /**
   * Resolve the order's customer. With an email present, upsert on {@code (orgId, email)}. Without
   * an email: required (online) → 400; optional (in-store walk-in) → null (no CRM record, {@code
   * customer_id} stays null).
   */
  private Customer resolveCustomer(
      SalesOrderRepository repo, UUID orgId, CustomerInput customer, boolean required) {
    boolean hasEmail = customer != null && customer.email() != null && !customer.email().isBlank();
    if (!hasEmail) {
      if (required) {
        throw new ValidationException("customer.email is required");
      }
      return null;
    }
    return repo.upsertCustomerByEmail(
        orgId,
        normalize(customer.email()),
        trimOrNull(customer.name()),
        trimOrNull(customer.phone()),
        trimOrNull(customer.address()));
  }

  // Validation

  private void validateOnlineInputs(CustomerInput customer, List<OrderLineInput> lines) {
    if (customer == null) {
      throw new ValidationException("customer is required");
    }
    if (customer.email() == null || customer.email().isBlank()) {
      throw new ValidationException("customer.email is required");
    }
    if (customer.name() == null || customer.name().isBlank()) {
      throw new ValidationException("customer.name is required");
    }
    validateLines(lines);
  }

  private void validateInStoreInputs(List<OrderLineInput> lines, PaymentInput payment) {
    validateLines(lines);
    if (payment == null || payment.provider() == null) {
      throw new ValidationException("payment.provider is required");
    }
    if (payment.provider() == PaymentProvider.INSTAPAY_MANUAL) {
      throw new ValidationException(PaymentService.IN_STORE_PROVIDER_REJECT_MSG);
    }
    if (payment.amount() != null && payment.amount().signum() <= 0) {
      throw new ValidationException("payment.amount must be > 0");
    }
  }

  private void validateLines(List<OrderLineInput> lines) {
    if (lines == null || lines.isEmpty()) {
      throw new ValidationException("lines must not be empty");
    }
    for (int i = 0; i < lines.size(); i++) {
      OrderLineInput l = lines.get(i);
      if (l == null) {
        throw new ValidationException("lines[" + i + "] is null");
      }
      if (l.productId() == null) {
        throw new ValidationException("lines[" + i + "].product_id is required");
      }
      if (l.quantity() <= 0) {
        throw new ValidationException("lines[" + i + "].quantity must be > 0");
      }
    }
  }

  private Customer loadCustomerOrThrow(SalesOrderRepository repo, UUID orgId, UUID customerId) {
    if (customerId == null) {
      throw new IllegalStateException("Idempotent replay: prior order has no customer_id");
    }
    return repo.findCustomerById(orgId, customerId)
        .orElseThrow(() -> new NotFoundException("Customer", customerId));
  }

  private static String normalize(String email) {
    return email == null ? null : email.trim().toLowerCase();
  }

  private static String trimOrNull(String s) {
    if (s == null) return null;
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
