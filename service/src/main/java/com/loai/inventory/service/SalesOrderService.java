package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.domain.model.OrderChannel;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.Org;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentAllocation;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.domain.repository.OrgRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import com.loai.inventory.service.email.EmailAddresses;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  private static final String CURRENCY_EGP = "EGP";

  private final DSLContext rootDsl;
  private final SalesOrderRepositoryFactory repoFactory;
  private final OrgRepositoryFactory orgRepoFactory;
  private final ReservationService reservationService;
  private final FulfillmentService fulfillmentService;
  private final PaymentService paymentService;
  private final InvoiceService invoiceService;
  private final RefundService refundService;
  private final NotificationService notificationService;
  private final MagicLinkService magicLinkService;

  public SalesOrderService(
      DSLContext rootDsl,
      SalesOrderRepositoryFactory repoFactory,
      OrgRepositoryFactory orgRepoFactory,
      ReservationService reservationService,
      FulfillmentService fulfillmentService,
      PaymentService paymentService,
      InvoiceService invoiceService,
      RefundService refundService,
      NotificationService notificationService,
      MagicLinkService magicLinkService) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.orgRepoFactory = orgRepoFactory;
    this.reservationService = reservationService;
    this.fulfillmentService = fulfillmentService;
    this.paymentService = paymentService;
    this.invoiceService = invoiceService;
    this.refundService = refundService;
    this.notificationService = notificationService;
    this.magicLinkService = magicLinkService;
  }

  /** Input contact info; {@code name} required, others optional. */
  public record CustomerInput(String name, String email, String phone, String address) {}

  /** Input order line; {@code quantity > 0}, product belongs to {@code orgId}. */
  public record OrderLineInput(UUID productId, int quantity) {}

  /**
   * Storefront order line: like {@link OrderLineInput} but carries the {@code unitPrice} the line
   * is snapshotted at — the resolved {@code product_listing.sales_price}, not {@code
   * product.base_price}. See {@code stories/public_checkout.md} §Placement variant.
   */
  public record StorefrontLineInput(UUID productId, int quantity, BigDecimal unitPrice) {}

  /**
   * The result of an anonymous storefront placement: the placed order + lines + customer, the
   * relative {@code trackUrl} (the anonymous order-view magic link minted in-txn; null on
   * idempotent replay, which can't reconstruct the raw token), and {@code created} — true for a
   * fresh order (201), false when a duplicate {@code Idempotency-Key} replayed a prior order (200).
   */
  public record StorefrontPlaced(
      SalesOrder order,
      List<SalesOrderLine> lines,
      Customer customer,
      String trackUrl,
      boolean created) {}

  /**
   * Internal unified line — an optional {@code unitPriceOverride} (storefront) or null (online).
   */
  private record ResolvedLine(UUID productId, int quantity, BigDecimal unitPriceOverride) {}

  /** Internal placement outcome carried out of the shared reservation txn. */
  private record PlacementResult(
      SalesOrder order,
      List<SalesOrderLine> lines,
      Customer customer,
      String trackUrl,
      boolean created) {}

  /**
   * Cashier tender for an in-store sale. {@code amount} is optional (defaults to the grand total).
   */
  public record PaymentInput(PaymentProvider provider, String providerRef, BigDecimal amount) {}

  /** Carries the placed order + its lines + the resolved customer for response mapping. */
  public record Placed(SalesOrder order, List<SalesOrderLine> lines, Customer customer) {}

  /**
   * The full result of an in-store sale: every aggregate created in the one checkout txn. {@code
   * changeRefund} is the EXECUTED cash refund of the tender's excess over the grand total — null
   * for an exact tender.
   */
  public record InStoreSale(
      SalesOrder order,
      List<SalesOrderLine> lines,
      Fulfillment fulfillment,
      SalesInvoice invoice,
      List<SalesInvoiceLine> invoiceLines,
      Payment payment,
      List<PaymentAllocation> allocations,
      Refund changeRefund) {}

  public Placed placeOnlineOrder(
      UUID orgId,
      CustomerInput customer,
      List<OrderLineInput> lines,
      String idempotencyKey,
      String notes,
      ActorContext actor) {

    validateOnlineInputs(customer, lines);
    List<ResolvedLine> resolved =
        lines.stream().map(l -> new ResolvedLine(l.productId(), l.quantity(), null)).toList();
    PlacementResult r = placeReserved(orgId, customer, resolved, idempotencyKey, notes, actor);
    return new Placed(r.order(), r.lines(), r.customer());
  }

  /**
   * Anonymous storefront placement ({@code stories/public_checkout.md}, B5). Identical to {@link
   * #placeOnlineOrder} — same {@code ONLINE} channel, customer upsert, order-number claim, per-org
   * TTL {@code expires_at}, stock reservation, {@code ORDER_PLACED} notifications, and order-view
   * magic link — except each line's {@code unit_price} is snapshotted from the caller-supplied
   * {@code product_listing.sales_price} (what the shopper saw), never {@code product.base_price}.
   * Returns the {@code trackUrl} (the anonymous order-view link) and whether the order was freshly
   * created vs an idempotent replay.
   */
  public StorefrontPlaced placeStorefrontOrder(
      UUID orgId,
      CustomerInput customer,
      List<StorefrontLineInput> lines,
      String idempotencyKey,
      String notes,
      ActorContext actor) {

    validateOnlineInputs(customer, toOrderLineInputs(lines));
    List<ResolvedLine> resolved =
        lines.stream()
            .map(l -> new ResolvedLine(l.productId(), l.quantity(), l.unitPrice()))
            .toList();
    PlacementResult r = placeReserved(orgId, customer, resolved, idempotencyKey, notes, actor);
    return new StorefrontPlaced(r.order(), r.lines(), r.customer(), r.trackUrl(), r.created());
  }

  /**
   * The shared placement + reservation transaction behind both {@link #placeOnlineOrder} and {@link
   * #placeStorefrontOrder}: idempotency short-circuit, build DRAFT → PENDING_PAYMENT with the org's
   * TTL, insert, reserve stock, notify staff, and mint the customer's order-view magic link — all
   * in one txn, so a shortage or any failure rolls the whole thing back.
   */
  private PlacementResult placeReserved(
      UUID orgId,
      CustomerInput customer,
      List<ResolvedLine> lines,
      String idempotencyKey,
      String notes,
      ActorContext actor) {

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          SalesOrderRepository repo = repoFactory.create(txDsl);

          // 1. Idempotency short-circuit — a duplicate key replays the prior order (200), no second
          // reservation, no raw token to reconstruct (trackUrl null).
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
              return new PlacementResult(prior, priorLines, priorCustomer, null, false);
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

          // Online: DRAFT → PENDING_PAYMENT with the org's payment-hold window (reservation.md
          // §Default TTL, "Configurable per-org" — V47, default 1440 min), read inside this txn so
          // the stamped expires_at always reflects the org's current setting. The TTL sweeper keys
          // off expires_at alone.
          Org org =
              orgRepoFactory
                  .create(txDsl)
                  .findById(orgId)
                  .orElseThrow(() -> new NotFoundException("Org", orgId));
          OffsetDateTime expiresAt = now.plus(Duration.ofMinutes(org.getOrderTtlMinutes()));
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

          // Notify the customer by email, carrying an order-scoped magic link (view your order, no
          // login). Both the token and the notification are written in this txn, so a rolled-back
          // order leaves neither. Online/storefront placement always resolves a customer (email
          // required). The same raw link is returned as the response's track_url.
          Customer resolvedCustomer = built.customer();
          String trackUrl = null;
          if (resolvedCustomer != null) {
            String viewLink =
                magicLinkService.issueOrderViewLink(
                    txDsl, orgId, resolvedCustomer.getId(), order.getId(), now);
            trackUrl = toRelativeTrackUrl(viewLink);
            notificationService.notify(
                txDsl,
                orgId,
                com.loai.inventory.domain.model.NotificationRecipient.customer(
                    resolvedCustomer.getId()),
                NotificationType.ORDER_PLACED,
                Map.of("order_number", order.getOrderNumber()),
                "sales_order",
                order.getId(),
                viewLink);
          }

          log.info(
              "Placed online order id={} orgId={} number={} customerId={} grandTotal={} lines={}",
              order.getId(),
              orgId,
              order.getOrderNumber(),
              built.customer() == null ? null : built.customer().getId(),
              order.getGrandTotal(),
              orderLines.size());

          return new PlacementResult(order, orderLines, built.customer(), trackUrl, true);
        });
  }

  private static List<OrderLineInput> toOrderLineInputs(List<StorefrontLineInput> lines) {
    if (lines == null) {
      return null;
    }
    return lines.stream()
        .map(l -> l == null ? null : new OrderLineInput(l.productId(), l.quantity()))
        .toList();
  }

  /**
   * Reduce the absolute order-view URL minted by {@link MagicLinkService} ({@code
   * {publicBaseUrl}/api/public/orders/{token}}) to the relative {@code /api/public/orders/{token}}
   * the public checkout contract returns as {@code track_url}.
   */
  private static String toRelativeTrackUrl(String absolute) {
    if (absolute == null) {
      return null;
    }
    int i = absolute.indexOf("/api/public/orders/");
    return i >= 0 ? absolute.substring(i) : absolute;
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
                  lines.stream()
                      .map(l -> new ResolvedLine(l.productId(), l.quantity(), null))
                      .toList(),
                  idempotencyKey,
                  notes,
                  now,
                  false);
          SalesOrder order = built.order();
          List<SalesOrderLine> orderLines = built.lines();
          repo.insert(order, orderLines);

          // Tender rules: overpaid is accepted (the customer hands a round amount, the excess is
          // returned as counter change — payment.md §Overpaid (in-store)); underpaid is rejected —
          // v1 releases goods only against full payment (salesOrder.md edge case "pay full or
          // cancel"; fulfillment.md: SalesOrder must be PAID before goods move). Partial-accept
          // (write off the shortfall) is a MANAGER CreditNote decision, not a STAFF checkout path.
          BigDecimal grandTotal = order.getGrandTotal();
          BigDecimal tender = payment.amount() == null ? grandTotal : payment.amount();
          if (tender.compareTo(grandTotal) < 0) {
            throw new ValidationException(
                "in-store tender "
                    + tender
                    + " is less than grand total "
                    + grandTotal
                    + " — underpaid sales are not accepted (v1: pay full or cancel)");
          }
          BigDecimal change = tender.subtract(grandTotal);

          // 2. Take the money: payment recorded for the FULL tender, VERIFIED + MATCHED, RECEIVED.
          Payment paymentRow =
              paymentService.recordInStorePayment(
                  txDsl,
                  orgId,
                  order,
                  payment.provider(),
                  payment.providerRef(),
                  tender,
                  verifiedBy,
                  now);

          // 3. Order DRAFT → PAID — paid before any goods move. prepaid_amount is the NET money
          // attached to the order (SUM(payments) − SUM(executed refunds)); the change refund
          // executes before this transaction commits, so the net is the grand total.
          order.markPaid(grandTotal, now);
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

          // The FIFO allocator mutated its own copy of the payment; surface that rather than the
          // as-created RECEIVED instance. The sale has exactly one same-txn payment — guard the
          // invariant so a future multi-tender slice can't silently pick the wrong one.
          if (issued.consumedPayments().size() > 1) {
            throw new IllegalStateException(
                "in-store sale expected at most one consumed payment, got "
                    + issued.consumedPayments().size());
          }
          Payment finalPayment =
              issued.consumedPayments().isEmpty() ? paymentRow : issued.consumedPayments().get(0);

          // 7. Counter change: the allocator consumed the invoice total, leaving the excess as the
          // payment's unallocated balance — hand it straight back as an EXECUTED cash refund
          // (payment.md §Overpaid (in-store) steps 4–5). Payment ends ALLOCATED, unallocated 0.
          RefundService.Executed changeRefund = null;
          if (change.signum() > 0) {
            changeRefund =
                refundService.createExecutedChangeInTx(
                    txDsl, orgId, finalPayment, change, verifiedBy, now);
          }

          log.info(
              "In-store sale order id={} orgId={} number={} grandTotal={} tender={} change={} invoice={} payment={} status={}",
              order.getId(),
              orgId,
              order.getOrderNumber(),
              order.getGrandTotal(),
              tender,
              change,
              issued.invoice().getInvoiceNumber(),
              finalPayment.getStatus(),
              order.getStatus());

          return new InStoreSale(
              order,
              orderLines,
              fulfillment.fulfillment(),
              issued.invoice(),
              issued.lines(),
              finalPayment,
              issued.allocations(),
              changeRefund == null ? null : changeRefund.refund());
        });
  }

  /**
   * Look up an order by its human-readable number — the pre-flight for the manual money path
   * ({@code stories/lookup_order_by_number.md}): the admin previews status / outstanding balance
   * before recording or resolving a transaction against the number. Exact, case-sensitive match
   * after trimming (numbers arrive by copy-paste from transfer notes). Read-only on {@code
   * rootDsl}, no lock — the money endpoints re-read {@code FOR UPDATE} inside their own
   * transactions, so a stale preview can never corrupt a write.
   *
   * @throws ValidationException on a missing/blank number
   * @throws NotFoundException if no order with that number exists in {@code orgId}
   */
  public Placed getByNumber(UUID orgId, String orderNumber) {
    if (orderNumber == null || orderNumber.isBlank()) {
      throw new ValidationException("order_number is required");
    }
    String number = orderNumber.trim();
    SalesOrderRepository repo = repoFactory.create(rootDsl);
    SalesOrder order =
        repo.findByOrderNumber(orgId, number)
            .orElseThrow(() -> new NotFoundException("SalesOrder not found: " + number));
    return new Placed(order, repo.findLinesByOrderId(order.getId()), null);
  }

  /**
   * Look up an order by id — the detail read behind the worklist row ({@code
   * stories/fulfillment_reads.md}). Same response shape as {@link #getByNumber}, keyed by the
   * stable id instead of the human-readable number. Read-only on {@code rootDsl}, no lock.
   *
   * @throws NotFoundException if the order is not in {@code orgId}
   */
  public Placed getById(UUID orgId, UUID orderId) {
    if (orderId == null) {
      throw new ValidationException("order id is required");
    }
    return findPlaced(orgId, orderId)
        .orElseThrow(() -> new NotFoundException("SalesOrder", orderId));
  }

  /**
   * Load an order + its lines + customer for a read-only view (the anonymous magic-link route). The
   * caller has already proven access via the token, so this takes no {@link ActorContext}; it is
   * still org-scoped. Returns empty if the order does not exist in {@code orgId}.
   */
  public Optional<Placed> findPlaced(UUID orgId, UUID orderId) {
    SalesOrderRepository repo = repoFactory.create(rootDsl);
    return repo.findById(orgId, orderId)
        .map(
            order -> {
              List<SalesOrderLine> lines = repo.findLinesByOrderId(order.getId());
              Customer customer =
                  order.getCustomerId() == null
                      ? null
                      : repo.findCustomerById(orgId, order.getCustomerId()).orElse(null);
              return new Placed(order, lines, customer);
            });
  }

  /** Paging bounds for the order worklist — mirrors the other worklists. */
  public static final int DEFAULT_PAGE_SIZE = 20;

  public static final int MAX_PAGE_SIZE = 100;

  /**
   * One page of the order worklist ({@link Placed} rows carry the order + its lines) + the total.
   */
  public record OrderListPage(List<Placed> items, long total) {}

  /**
   * The order worklist ({@code GET /sales-orders?status=&page=&size=}): filtered by {@code status}
   * it is a queue (oldest first); unfiltered it is the ledger (newest first). Lines are
   * batch-loaded (one query per page). Customer is not resolved — the list DTO doesn't carry it.
   * Read-only on {@code rootDsl}.
   */
  public OrderListPage list(UUID orgId, OrderStatus status, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    SalesOrderRepository repo = repoFactory.create(rootDsl);
    List<SalesOrder> orders = repo.list(orgId, status, p * s, s);
    long total = repo.count(orgId, status);
    Map<UUID, List<SalesOrderLine>> linesByOrder =
        repo.findLinesByOrderIds(orders.stream().map(SalesOrder::getId).toList());
    List<Placed> items =
        orders.stream()
            .map(o -> new Placed(o, linesByOrder.getOrDefault(o.getId(), List.of()), null))
            .toList();
    return new OrderListPage(items, total);
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
      List<ResolvedLine> lines,
      String idempotencyKey,
      String notes,
      OffsetDateTime now,
      boolean customerRequired) {

    Customer resolvedCustomer = resolveCustomer(repo, orgId, customer, customerRequired);

    // Snapshot product data per line; fail if any product is missing for this org.
    List<UUID> productIds = lines.stream().map(ResolvedLine::productId).toList();
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
    for (ResolvedLine in : lines) {
      SalesOrderRepository.ProductSnapshot snap = snapshots.get(in.productId());
      // Storefront lines carry a unit-price override (the published sales_price the shopper saw);
      // online/in-store lines snapshot product.base_price.
      BigDecimal unitPrice =
          in.unitPriceOverride() != null ? in.unitPriceOverride() : snap.unitPrice();
      SalesOrderLine line =
          SalesOrderLine.create(
              UUID.randomUUID(),
              orderId,
              snap.productId(),
              snap.description(),
              in.quantity(),
              unitPrice,
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
    // A single valid address only — a comma list or injected header would otherwise be stored and
    // later fan the emailed order link out to arbitrary recipients (see MagicLink/email channel).
    String normalizedEmail = normalize(customer.email());
    if (!EmailAddresses.isSingleValid(normalizedEmail)) {
      throw new ValidationException("customer.email is not a valid single email address");
    }
    return repo.upsertCustomerByEmail(
        orgId,
        normalizedEmail,
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
