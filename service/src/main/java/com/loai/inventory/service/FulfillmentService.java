package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.InsufficientStockException;
import com.loai.inventory.common.exception.InsufficientStockException.Shortage;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.text.Text;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.FulfillmentLine;
import com.loai.inventory.domain.model.FulfillmentResolution;
import com.loai.inventory.domain.model.FulfillmentStatus;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.InventoryReservation;
import com.loai.inventory.domain.model.NotificationRecipient;
import com.loai.inventory.domain.model.NotificationType;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentAllocation;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.Refund;
import com.loai.inventory.domain.model.ReservationStatus;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.domain.model.SalesInvoiceLine;
import com.loai.inventory.domain.model.SalesOrder;
import com.loai.inventory.domain.model.SalesOrderLine;
import com.loai.inventory.domain.model.StockReason;
import com.loai.inventory.domain.repository.FulfillmentRepository;
import com.loai.inventory.domain.repository.FulfillmentRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryLogRepository;
import com.loai.inventory.domain.repository.InventoryLogRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryRepository;
import com.loai.inventory.domain.repository.InventoryRepositoryFactory;
import com.loai.inventory.domain.repository.InventoryReservationRepository;
import com.loai.inventory.domain.repository.InventoryReservationRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import java.math.BigDecimal;
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
 * Outbound flow: ship and deliver part of a paid order, plus the in-store direct-DELIVERED move.
 * Slice specs {@code stories/ship_fulfillment.md}, {@code stories/deliver_issue_invoice.md} and
 * {@code stories/in_store_sale.md}.
 *
 * <p>Two public transaction entry points ({@link #create}, {@link #ship}, {@link #markDelivered})
 * own their own DB transaction; {@link #createDelivered} is a collaborator that runs inside a
 * caller-supplied {@code txDsl} (the in-store checkout txn).
 *
 * <ol>
 *   <li>{@link #create} — build a PENDING {@link Fulfillment} with a subset of the order's lines.
 *       Nothing physically moves yet; each line is linked to the ACTIVE reservation it will
 *       consume.
 *   <li>{@link #ship} — PENDING → SHIPPED. The consequential transition: per line write a {@code
 *       -stock} {@code inventory_log} row, mark the linked reservation CONSUMED, and decrement
 *       {@code inventory.on_hand} and {@code reserved} by the same amount (so {@code available} is
 *       unchanged). The order flips PAID → FULFILLING on its first shipment.
 *   <li>{@link #markDelivered} — SHIPPED → DELIVERED. Delegates invoice issuance + prepayment
 *       allocation to {@link InvoiceService}, then rolls the order up to FULFILLED / CLOSED.
 *   <li>{@link #createDelivered} — in-store: a fulfillment created directly DELIVERED, decrementing
 *       {@code stock_qty} on the spot (no reservation to consume).
 * </ol>
 *
 * <p>v1 ships whole lines: each fulfillment line consumes one line's single ACTIVE reservation in
 * full. "Part of the order" therefore means a subset of lines (the canonical partial-fulfillment
 * shape in {@code sys-analysis/outbound/fulfillment.md}); partial-quantity-within-a-line is
 * deferred.
 */
public final class FulfillmentService {

  private static final Logger log = LoggerFactory.getLogger(FulfillmentService.class);

  /**
   * Default refund channel for a failed-fulfillment refund — online prepayments arrive InstaPay.
   */
  private static final PaymentProvider DEFAULT_REFUND_METHOD = PaymentProvider.INSTAPAY_MANUAL;

  private final DSLContext rootDsl;
  private final FulfillmentRepositoryFactory fulfillmentRepoFactory;
  private final SalesOrderRepositoryFactory salesOrderRepoFactory;
  private final InventoryRepositoryFactory inventoryRepoFactory;
  private final InventoryReservationRepositoryFactory reservationRepoFactory;
  private final InventoryLogRepositoryFactory inventoryLogRepoFactory;
  private final PaymentRepositoryFactory paymentRepoFactory;
  private final InvoiceService invoiceService;
  private final RefundService refundService;
  private final ReservationService reservationService;
  private final NotificationService notificationService;
  private final MagicLinkService magicLinkService;
  private final LowStockNotifier lowStockNotifier;

  public FulfillmentService(
      DSLContext rootDsl,
      FulfillmentRepositoryFactory fulfillmentRepoFactory,
      SalesOrderRepositoryFactory salesOrderRepoFactory,
      InventoryRepositoryFactory inventoryRepoFactory,
      InventoryReservationRepositoryFactory reservationRepoFactory,
      InventoryLogRepositoryFactory inventoryLogRepoFactory,
      PaymentRepositoryFactory paymentRepoFactory,
      InvoiceService invoiceService,
      RefundService refundService,
      ReservationService reservationService,
      NotificationService notificationService,
      MagicLinkService magicLinkService) {
    this(
        rootDsl,
        fulfillmentRepoFactory,
        salesOrderRepoFactory,
        inventoryRepoFactory,
        reservationRepoFactory,
        inventoryLogRepoFactory,
        paymentRepoFactory,
        invoiceService,
        refundService,
        reservationService,
        notificationService,
        magicLinkService,
        null);
  }

  /**
   * @param lowStockNotifier the reorder-point check run after the in-store sale's stock writes
   *     ({@code stories/reorder_point.md}); {@code null} = no check.
   */
  public FulfillmentService(
      DSLContext rootDsl,
      FulfillmentRepositoryFactory fulfillmentRepoFactory,
      SalesOrderRepositoryFactory salesOrderRepoFactory,
      InventoryRepositoryFactory inventoryRepoFactory,
      InventoryReservationRepositoryFactory reservationRepoFactory,
      InventoryLogRepositoryFactory inventoryLogRepoFactory,
      PaymentRepositoryFactory paymentRepoFactory,
      InvoiceService invoiceService,
      RefundService refundService,
      ReservationService reservationService,
      NotificationService notificationService,
      MagicLinkService magicLinkService,
      LowStockNotifier lowStockNotifier) {
    this.rootDsl = rootDsl;
    this.fulfillmentRepoFactory = fulfillmentRepoFactory;
    this.salesOrderRepoFactory = salesOrderRepoFactory;
    this.inventoryRepoFactory = inventoryRepoFactory;
    this.reservationRepoFactory = reservationRepoFactory;
    this.inventoryLogRepoFactory = inventoryLogRepoFactory;
    this.paymentRepoFactory = paymentRepoFactory;
    this.invoiceService = invoiceService;
    this.refundService = refundService;
    this.reservationService = reservationService;
    this.notificationService = notificationService;
    this.magicLinkService = magicLinkService;
    this.lowStockNotifier = lowStockNotifier;
  }

  /** Which order line to ship; quantity is derived from that line's ACTIVE reservation (v1). */
  public record LineInput(UUID salesOrderLineId) {}

  /** A line to fulfill in-store: order line + its product + quantity (no reservation involved). */
  public record DeliveredLineInput(UUID salesOrderLineId, UUID productId, int quantity) {}

  /**
   * Carries a fulfillment + its lines for response mapping. Read paths additionally decorate it
   * with the parent order's human-readable number (all reads) and the fulfillment's monetary value
   * (detail + by-order reads — the figure {@link #refundFailed} sizes its refund by, so a client
   * can pre-warn about the refund-approval threshold with the guard's own number). Mutation paths
   * leave both null (omitted from JSON).
   */
  public record FulfillmentView(
      Fulfillment fulfillment,
      List<FulfillmentLine> lines,
      String salesOrderNumber,
      BigDecimal fulfillmentValue) {

    public FulfillmentView(Fulfillment fulfillment, List<FulfillmentLine> lines) {
      this(fulfillment, lines, null, null);
    }
  }

  /**
   * The full result of marking a fulfillment DELIVERED: the delivered fulfillment, the SalesInvoice
   * it issued (+ its lines), the PaymentAllocations auto-created from prepayment, and the (possibly
   * rolled-up) SalesOrder.
   */
  public record DeliveredView(
      Fulfillment fulfillment,
      SalesInvoice invoice,
      List<SalesInvoiceLine> invoiceLines,
      List<PaymentAllocation> allocations,
      SalesOrder order) {}

  /** A failed-fulfillment refund: the failed fulfillment plus the PENDING refund(s) created. */
  public record FailedRefundResult(
      Fulfillment fulfillment, List<Refund> refunds, BigDecimal pendingRefundTotal) {}

  /**
   * Create a PENDING fulfillment for {@code salesOrderId} covering the requested order lines. The
   * order must be PAID (or already FULFILLING). Each requested line must have an ACTIVE reservation
   * and must not already be fulfilled (over-fulfillment is rejected here).
   */
  public FulfillmentView create(
      UUID orgId,
      UUID salesOrderId,
      List<LineInput> lines,
      String carrier,
      String trackingNumber,
      String notes,
      ActorContext actor) {

    validateCreateInputs(salesOrderId, lines);
    return rootDsl.transactionResult(
        cfg ->
            createInTx(
                DSL.using(cfg),
                orgId,
                salesOrderId,
                lines,
                carrier,
                trackingNumber,
                notes,
                null,
                actor,
                OffsetDateTime.now(ZoneOffset.UTC)));
  }

  /**
   * Build a PENDING fulfillment inside the caller's {@code txDsl}: validate line membership, claim
   * each line's ACTIVE reservation, enforce the over-fulfillment ceiling, then insert. Shared by
   * {@link #create} (own transaction) and {@link #replaceFailed} (which re-reserves first, then
   * builds the replacement in the same transaction). A non-null {@code replacesFulfillmentId}
   * stamps the audit link back to the FAILED fulfillment being re-shipped.
   */
  private FulfillmentView createInTx(
      DSLContext txDsl,
      UUID orgId,
      UUID salesOrderId,
      List<LineInput> lines,
      String carrier,
      String trackingNumber,
      String notes,
      UUID replacesFulfillmentId,
      ActorContext actor,
      OffsetDateTime now) {
    SalesOrderRepository orderRepo = salesOrderRepoFactory.create(txDsl);
    FulfillmentRepository fulfillmentRepo = fulfillmentRepoFactory.create(txDsl);
    InventoryReservationRepository reservationRepo = reservationRepoFactory.create(txDsl);

    SalesOrder order =
        orderRepo
            .findByIdForUpdate(orgId, salesOrderId)
            .orElseThrow(() -> new NotFoundException("SalesOrder", salesOrderId));
    requireFulfillable(order);

    // Order lines, by id — used to validate membership and the over-fulfillment ceiling.
    Map<UUID, SalesOrderLine> orderLines = new LinkedHashMap<>();
    for (SalesOrderLine l : orderRepo.findLinesByOrderId(salesOrderId)) {
      orderLines.put(l.getId(), l);
    }

    // ACTIVE reservations for this order, keyed by sales_order_line_id (one per line).
    Map<UUID, InventoryReservation> activeByLine = new LinkedHashMap<>();
    for (InventoryReservation r : reservationRepo.findActiveByOrderId(salesOrderId)) {
      activeByLine.put(r.getSalesOrderLineId(), r);
    }

    Map<UUID, Integer> alreadyFulfilled = fulfillmentRepo.sumFulfilledQtyByOrderLine(salesOrderId);

    UUID fulfillmentId = UUID.randomUUID();
    List<FulfillmentLine> fulfillmentLines = new ArrayList<>(lines.size());
    for (LineInput in : lines) {
      UUID lineId = in.salesOrderLineId();
      SalesOrderLine orderLine = orderLines.get(lineId);
      if (orderLine == null) {
        throw new ValidationException(
            "sales_order_line " + lineId + " does not belong to order " + salesOrderId);
      }
      InventoryReservation reservation = activeByLine.get(lineId);
      if (reservation == null) {
        throw new ValidationException(
            "sales_order_line "
                + lineId
                + " has no active reservation (already fulfilled or released)");
      }
      int already = alreadyFulfilled.getOrDefault(lineId, 0);
      if (already + reservation.getQuantity() > orderLine.getQuantity()) {
        throw new ValidationException(
            "over-fulfillment of sales_order_line "
                + lineId
                + ": already "
                + already
                + " + "
                + reservation.getQuantity()
                + " > ordered "
                + orderLine.getQuantity());
      }
      fulfillmentLines.add(
          FulfillmentLine.create(
              UUID.randomUUID(),
              fulfillmentId,
              lineId,
              reservation.getQuantity(),
              reservation.getId()));
    }

    Fulfillment fulfillment =
        replacesFulfillmentId == null
            ? Fulfillment.createPending(
                fulfillmentId,
                orgId,
                salesOrderId,
                Text.normalizeText(carrier),
                Text.normalizeNumeric(trackingNumber),
                Text.normalizeText(notes),
                now)
            : Fulfillment.createReplacementPending(
                fulfillmentId,
                orgId,
                salesOrderId,
                replacesFulfillmentId,
                Text.normalizeText(carrier),
                Text.normalizeNumeric(trackingNumber),
                Text.normalizeText(notes),
                now);
    fulfillmentRepo.insert(fulfillment, fulfillmentLines);

    log.info(
        "Created PENDING fulfillment id={} orgId={} order={} lines={} replaces={} by actor={}",
        fulfillment.getId(),
        orgId,
        order.getOrderNumber(),
        fulfillmentLines.size(),
        replacesFulfillmentId,
        actor == null ? null : actor.actorId());
    return new FulfillmentView(fulfillment, fulfillmentLines);
  }

  /**
   * Ship a PENDING fulfillment: PENDING → SHIPPED with all stock side effects in one transaction.
   * The order is locked FOR UPDATE so concurrent shipments of the same order serialize.
   */
  public FulfillmentView ship(UUID orgId, UUID fulfillmentId, ActorContext actor) {
    if (fulfillmentId == null) {
      throw new ValidationException("fulfillmentId is required");
    }

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          FulfillmentRepository fulfillmentRepo = fulfillmentRepoFactory.create(txDsl);
          SalesOrderRepository orderRepo = salesOrderRepoFactory.create(txDsl);
          InventoryRepository inventoryRepo = inventoryRepoFactory.create(txDsl);
          InventoryReservationRepository reservationRepo = reservationRepoFactory.create(txDsl);
          InventoryLogRepository inventoryLogRepo = inventoryLogRepoFactory.create(txDsl);

          Fulfillment fulfillment =
              fulfillmentRepo
                  .findByIdForUpdate(orgId, fulfillmentId)
                  .orElseThrow(() -> new NotFoundException("Fulfillment", fulfillmentId));
          // Domain guard also enforces PENDING; check here for a clean 409 instead of a 500.
          if (fulfillment.getStatus()
              != com.loai.inventory.domain.model.FulfillmentStatus.PENDING) {
            throw new ConflictException(
                "fulfillment "
                    + fulfillmentId
                    + " is "
                    + fulfillment.getStatus()
                    + ", not PENDING");
          }

          List<FulfillmentLine> lines = fulfillmentRepo.findLinesByFulfillmentId(fulfillmentId);
          if (lines.isEmpty()) {
            throw new ValidationException("fulfillment " + fulfillmentId + " has no lines to ship");
          }

          // Lock the order: v1 requires PAID before any shipment; later shipments see FULFILLING.
          SalesOrder order =
              orderRepo
                  .findByIdForUpdate(orgId, fulfillment.getSalesOrderId())
                  .orElseThrow(
                      () -> new NotFoundException("SalesOrder", fulfillment.getSalesOrderId()));
          requireFulfillable(order);

          // Resolve the reservations these lines consume; every v1 line carries one.
          List<UUID> reservationIds = new ArrayList<>(lines.size());
          for (FulfillmentLine line : lines) {
            if (line.getInventoryReservationId() == null) {
              throw new IllegalStateException(
                  "fulfillment line " + line.getId() + " has no reservation to consume");
            }
            reservationIds.add(line.getInventoryReservationId());
          }
          Map<UUID, InventoryReservation> reservationsById = new LinkedHashMap<>();
          for (InventoryReservation r : reservationRepo.findByIds(reservationIds)) {
            reservationsById.put(r.getId(), r);
          }
          for (UUID rid : reservationIds) {
            InventoryReservation r = reservationsById.get(rid);
            if (r == null || r.getStatus() != ReservationStatus.ACTIVE) {
              throw new ConflictException(
                  "reservation " + rid + " is not ACTIVE (already consumed or released)");
            }
          }

          // Aggregate the shipped quantity per product so the same SKU on two lines locks once and
          // writes a single inventory delta + log row.
          Map<UUID, Integer> shippedByProduct = new LinkedHashMap<>();
          for (FulfillmentLine line : lines) {
            UUID productId = reservationsById.get(line.getInventoryReservationId()).getProductId();
            shippedByProduct.merge(productId, line.getQuantity(), Integer::sum);
          }
          List<UUID> sortedProductIds = shippedByProduct.keySet().stream().sorted().toList();
          Map<UUID, Inventory> locked = inventoryRepo.lockForUpdate(orgId, sortedProductIds);

          for (UUID pid : sortedProductIds) {
            int qty = shippedByProduct.get(pid);
            Inventory current = locked.get(pid);
            if (current == null) {
              // A reserved product must have an inventory row — its absence is data corruption.
              throw new IllegalStateException(
                  "no inventory row for reserved product " + pid + " in org " + orgId);
            }
            // -stock and -reserved by the same amount: on_hand drops, available is unchanged.
            Inventory updated =
                inventoryRepo.adjustQuantities(orgId, pid, -qty, -qty, current.getVersion());
            inventoryLogRepo.insert(
                orgId,
                pid,
                -qty,
                -qty,
                updated.getStockQty(),
                updated.getReservedQty(),
                StockReason.SOLD,
                order.getId(),
                actor);
          }

          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          int consumed = reservationRepo.markConsumed(reservationIds, now);
          if (consumed != reservationIds.size()) {
            // Lost a race despite the ACTIVE check above — roll back rather than ship phantom
            // stock.
            throw new ConflictException(
                "reservation state changed during ship of fulfillment " + fulfillmentId);
          }

          fulfillment.ship(now);
          fulfillmentRepo.updateStatus(fulfillment);

          // First shipment of the order moves it PAID → FULFILLING. updatePaymentState persists
          // status (+ unchanged prepaid_amount) + updated_at.
          if (order.getStatus() == OrderStatus.PAID) {
            order.markFulfilling(now);
            orderRepo.updatePaymentState(order);
          }

          // ORDER_SHIPPED — the first of the three events that close the PAID→FULFILLED silence
          // (stories/order_lifecycle_notifications.md). Inside the ship txn, so a rolled-back
          // shipment sends nothing. Once per FULFILLMENT, not per order: a split order really does
          // put a second box on the road, and the shopper is owed that news too. Silent when the
          // order has no customer (PHONE orders may carry none) — there is nobody to tell.
          if (order.getCustomerId() != null) {
            notifyShipped(txDsl, orgId, order, fulfillment, now);
          }

          log.info(
              "Shipped fulfillment id={} orgId={} order={} products={} reservationsConsumed={} orderStatus={}",
              fulfillment.getId(),
              orgId,
              order.getOrderNumber(),
              sortedProductIds.size(),
              consumed,
              order.getStatus());
          return new FulfillmentView(fulfillment, lines);
        });
  }

  /**
   * ORDER_SHIPPED producer: the shopper's "it's on the way" message, raised inside the ship txn
   * after the fulfillment and order rows are persisted, so the notification exists iff the shipment
   * commits. A fresh order-view magic link is minted per message, exactly as placement and
   * ORDER_PAID do — the link is the shopper's whole self-serve surface, and reconstructing an
   * earlier token is impossible by design (they are stored hashed).
   *
   * <p>{@code carrier} and {@code tracking_number} are optional columns on {@code fulfillment}, so
   * they go into the payload only when the merchant recorded them; the template writes a sentence
   * per present field rather than rendering an empty one. Caller has already excluded the
   * customer-less order.
   */
  private void notifyShipped(
      DSLContext txDsl, UUID orgId, SalesOrder order, Fulfillment fulfillment, OffsetDateTime now) {
    MagicLinkService.OrderViewLink viewLink =
        magicLinkService.issueOrderViewLink(
            txDsl, orgId, order.getCustomerId(), order.getId(), now);
    // LinkedHashMap, not Map.of: the two optional fields are frequently null and Map.of rejects a
    // null value outright.
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("order_number", order.getOrderNumber());
    if (fulfillment.getCarrier() != null && !fulfillment.getCarrier().isBlank()) {
      payload.put("carrier", fulfillment.getCarrier());
    }
    if (fulfillment.getTrackingNumber() != null && !fulfillment.getTrackingNumber().isBlank()) {
      payload.put("tracking_number", fulfillment.getTrackingNumber());
    }
    notificationService.notify(
        txDsl,
        orgId,
        NotificationRecipient.customer(order.getCustomerId()),
        NotificationType.ORDER_SHIPPED,
        payload,
        "sales_order",
        order.getId(),
        viewLink.absolute());
  }

  /**
   * Mark a SHIPPED fulfillment DELIVERED — the one transaction that ties the online flow together.
   * Guards SHIPPED → DELIVERED, then delegates invoice issuance + prepayment auto-allocation to
   * {@link InvoiceService#issueForFulfillment} and rolls the order up: FULFILLED once every line is
   * delivered, then CLOSED once all its invoices are PAID.
   *
   * <p>The fulfillment and order rows are locked {@code FOR UPDATE}, so a double deliver fails the
   * SHIPPED guard (409) and concurrent deliveries of the same order serialize.
   */
  public DeliveredView markDelivered(UUID orgId, UUID fulfillmentId, ActorContext actor) {
    if (orgId == null) {
      throw new ValidationException("orgId is required");
    }
    if (fulfillmentId == null) {
      throw new ValidationException("fulfillmentId is required");
    }

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          FulfillmentRepository fulfillmentRepo = fulfillmentRepoFactory.create(txDsl);
          SalesOrderRepository orderRepo = salesOrderRepoFactory.create(txDsl);

          Fulfillment fulfillment =
              fulfillmentRepo
                  .findByIdForUpdate(orgId, fulfillmentId)
                  .orElseThrow(() -> new NotFoundException("Fulfillment", fulfillmentId));
          if (fulfillment.getStatus()
              != com.loai.inventory.domain.model.FulfillmentStatus.SHIPPED) {
            throw new ConflictException(
                "fulfillment "
                    + fulfillmentId
                    + " is "
                    + fulfillment.getStatus()
                    + ", not SHIPPED");
          }

          List<FulfillmentLine> lines = fulfillmentRepo.findLinesByFulfillmentId(fulfillmentId);
          if (lines.isEmpty()) {
            throw new ValidationException(
                "fulfillment " + fulfillmentId + " has no lines to invoice");
          }

          // Lock the order: serializes concurrent deliveries and the FULFILLED/CLOSED roll-up.
          SalesOrder order =
              orderRepo
                  .findByIdForUpdate(orgId, fulfillment.getSalesOrderId())
                  .orElseThrow(
                      () -> new NotFoundException("SalesOrder", fulfillment.getSalesOrderId()));

          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

          // (a) Fulfillment SHIPPED → DELIVERED.
          fulfillment.markDelivered(now);
          fulfillmentRepo.updateStatus(fulfillment);

          // (b) Build the billed-line specs from the delivered fulfillment lines.
          Map<UUID, SalesOrderLine> orderLines = new LinkedHashMap<>();
          for (SalesOrderLine l : orderRepo.findLinesByOrderId(order.getId())) {
            orderLines.put(l.getId(), l);
          }
          List<InvoiceService.LineSpec> specs = new ArrayList<>(lines.size());
          for (FulfillmentLine fl : lines) {
            SalesOrderLine ol = orderLines.get(fl.getSalesOrderLineId());
            if (ol == null) {
              throw new IllegalStateException(
                  "fulfillment line " + fl.getId() + " references unknown order line");
            }
            specs.add(
                new InvoiceService.LineSpec(
                    ol.getProductId(),
                    ol.getDescription(),
                    fl.getQuantity(),
                    ol.getUnitPrice(),
                    ol.getTaxRate()));
          }

          Customer customer =
              order.getCustomerId() == null
                  ? null
                  : orderRepo.findCustomerById(orgId, order.getCustomerId()).orElse(null);

          // (c) Issue the invoice + auto-allocate prepayment FIFO (shared with the in-store sale).
          InvoiceService.Issued issued =
              invoiceService.issueForFulfillment(
                  txDsl, orgId, order, fulfillmentId, customer, specs, now);

          // (d) Order roll-up: FULFILLED when every line is delivered; CLOSED when all invoices
          // PAID. The transitions live in OrderRollUp, shared with the invoice-reissue path —
          // delivery is not the only event that can settle an order's last live invoice.
          boolean wasFulfilling = order.getStatus() == OrderStatus.FULFILLING;
          OrderRollUp.afterDelivery(
              txDsl, orgId, order, orderLines, fulfillmentRepo, orderRepo, invoiceService, now);

          // (e) Review-request notification (roadmap item 1): fire once per order, on the
          // FULFILLING → FULFILLED|CLOSED edge this txn drew — so a multi-shipment order fires only
          // when its last line delivers, and a partial delivery (stays FULFILLING) never fires. The
          // in-store sale never reaches here (no FULFILLING roll-up). An email-less customer used
          // to be excluded here too, because notify()'s email leg threw and would have rolled back
          // the whole deliver txn (D3). It no longer throws — it suppresses that one channel — so
          // such a customer now gets the in-app feed row like everyone else.
          boolean nowComplete =
              order.getStatus() == OrderStatus.FULFILLED || order.getStatus() == OrderStatus.CLOSED;
          if (wasFulfilling && nowComplete && customer != null) {
            MagicLinkService.OrderViewLink reviewLink =
                magicLinkService.issueOrderViewLink(
                    txDsl, orgId, order.getCustomerId(), order.getId(), now);
            notificationService.notify(
                txDsl,
                orgId,
                com.loai.inventory.domain.model.NotificationRecipient.customer(
                    order.getCustomerId()),
                com.loai.inventory.domain.model.NotificationType.REVIEW_REQUESTED,
                Map.of("order_number", order.getOrderNumber()),
                "sales_order",
                order.getId(),
                reviewLink.absolute());
          }

          log.info(
              "Delivered fulfillment id={} orgId={} order={} invoice={} allocated={} orderStatus={}",
              fulfillmentId,
              orgId,
              order.getOrderNumber(),
              issued.invoice().getInvoiceNumber(),
              issued.allocations().size(),
              order.getStatus());
          return new DeliveredView(
              fulfillment, issued.invoice(), issued.lines(), issued.allocations(), order);
        });
  }

  /**
   * Cancel a PENDING fulfillment — the shipment is called off before anything leaves the warehouse
   * (order being cancelled, payment disputed, goods unshippable). PENDING → CANCELLED, releasing
   * the reservations its lines link to back to {@code available} ({@code fulfillment.md}
   * §CANCELLED; state-machines.md B "Enter CANCELLED before SHIPPED"). No stock moved at PENDING,
   * so nothing else unwinds. Only PENDING cancels: SHIPPED goods are FAILED, not cancelled (409).
   *
   * <p>Note the consequence of the released reservations: the lines cannot be re-fulfilled
   * afterwards (fulfillment creation requires an ACTIVE reservation per line), so cancelling a
   * fulfillment is giving up on shipping those lines — the money path out is an order cancel
   * (direct refund of the un-invoiced prepayment), not a re-ship.
   */
  public FulfillmentView cancelPending(UUID orgId, UUID fulfillmentId, ActorContext actor) {
    if (orgId == null) {
      throw new ValidationException("orgId is required");
    }
    if (fulfillmentId == null) {
      throw new ValidationException("fulfillmentId is required");
    }
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          FulfillmentRepository fulfillmentRepo = fulfillmentRepoFactory.create(txDsl);

          Fulfillment fulfillment =
              fulfillmentRepo
                  .findByIdForUpdate(orgId, fulfillmentId)
                  .orElseThrow(() -> new NotFoundException("Fulfillment", fulfillmentId));
          // Domain guard also enforces PENDING; pre-check for a clean 409 instead of a 500.
          if (fulfillment.getStatus() != FulfillmentStatus.PENDING) {
            throw new ConflictException(
                "fulfillment "
                    + fulfillmentId
                    + " is "
                    + fulfillment.getStatus()
                    + ", not PENDING; a shipped fulfillment fails, it does not cancel");
          }

          List<FulfillmentLine> lines = fulfillmentRepo.findLinesByFulfillmentId(fulfillmentId);
          List<UUID> reservationIds = new ArrayList<>(lines.size());
          for (FulfillmentLine line : lines) {
            if (line.getInventoryReservationId() != null) {
              reservationIds.add(line.getInventoryReservationId());
            }
          }
          ReservationService.ReleaseResult released =
              reservationService.releaseByIds(
                  txDsl,
                  fulfillment.getSalesOrderId(),
                  reservationIds,
                  "FULFILLMENT_CANCELLED",
                  actor,
                  now);

          fulfillment.cancel(now);
          fulfillmentRepo.updateStatus(fulfillment);

          log.info(
              "Cancelled PENDING fulfillment id={} orgId={} order={} reservationsReleased={}",
              fulfillmentId,
              orgId,
              fulfillment.getSalesOrderId(),
              released.released());
          return new FulfillmentView(fulfillment, lines);
        });
  }

  /**
   * Mark a SHIPPED fulfillment FAILED — the shipment never arrived (lost, refused, returned to
   * sender). Locks the fulfillment, guards SHIPPED → FAILED, and stamps {@code failed_at}/{@code
   * failed_reason}. Nothing else moves: the stock is still out there and the order keeps its
   * current status. Resolution is a separate admin step — {@link #refundFailed} (money back) and/or
   * {@link #recordReturn} (goods came back). STAFF may flag a failure; the money-moving resolution
   * is MANAGER-gated at the API layer.
   */
  public FulfillmentView markFailed(UUID orgId, UUID fulfillmentId, String reason) {
    if (orgId == null) {
      throw new ValidationException("orgId is required");
    }
    if (fulfillmentId == null) {
      throw new ValidationException("fulfillmentId is required");
    }
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          FulfillmentRepository fulfillmentRepo = fulfillmentRepoFactory.create(txDsl);

          Fulfillment fulfillment =
              fulfillmentRepo
                  .findByIdForUpdate(orgId, fulfillmentId)
                  .orElseThrow(() -> new NotFoundException("Fulfillment", fulfillmentId));
          // Domain guard also enforces SHIPPED; pre-check for a clean 409 instead of a 500.
          if (fulfillment.getStatus() != FulfillmentStatus.SHIPPED) {
            throw new ConflictException(
                "fulfillment "
                    + fulfillmentId
                    + " is "
                    + fulfillment.getStatus()
                    + ", not SHIPPED; only a shipped fulfillment can fail");
          }

          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          fulfillment.markFailed(reason, now);
          fulfillmentRepo.updateStatus(fulfillment);

          List<FulfillmentLine> lines = fulfillmentRepo.findLinesByFulfillmentId(fulfillmentId);
          log.info(
              "Failed fulfillment id={} orgId={} order={} reason={}",
              fulfillmentId,
              orgId,
              fulfillment.getSalesOrderId(),
              reason);
          return new FulfillmentView(fulfillment, lines);
        });
  }

  /**
   * Refund the customer for a FAILED fulfillment they paid for but never received. Locks the
   * fulfillment, asserts FAILED, then creates a <b>PENDING</b> direct (payment-backed) refund for
   * every prepayment on the order still carrying an unallocated balance — the same two-step
   * lifecycle as an order cancel ({@code refund.md}: PENDING → EXECUTED). No money moves here; the
   * admin performs the real reverse transfer and calls {@code POST /refunds/{id}/execute} after.
   *
   * <p>The refund is direct-from-Payment, not CreditNote-backed: failure is reachable only from
   * SHIPPED, before any invoice is issued, so the prepayment is still unallocated and there is
   * nothing to credit. An above-threshold refund escalates to OWNER ({@code callerIsOwnerOrAdmin});
   * the whole call rolls back if denied.
   *
   * <p>Idempotency: a duplicate call records a second PENDING refund against the same balance, but
   * {@link RefundService#execute} re-checks {@code unallocated_amount} at execution, so only the
   * first can move money — the surplus is a never-executable PENDING refund the admin can cancel.
   */
  public FailedRefundResult refundFailed(
      UUID orgId,
      UUID fulfillmentId,
      PaymentProvider refundMethod,
      UUID actorId,
      boolean callerIsOwnerOrAdmin) {
    if (orgId == null) {
      throw new ValidationException("orgId is required");
    }
    if (fulfillmentId == null) {
      throw new ValidationException("fulfillmentId is required");
    }
    if (actorId == null) {
      throw new ValidationException("actor identity is required");
    }
    PaymentProvider method = refundMethod == null ? DEFAULT_REFUND_METHOD : refundMethod;

    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          FulfillmentRepository fulfillmentRepo = fulfillmentRepoFactory.create(txDsl);
          PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);
          SalesOrderRepository orderRepo = salesOrderRepoFactory.create(txDsl);

          Fulfillment fulfillment =
              fulfillmentRepo
                  .findByIdForUpdate(orgId, fulfillmentId)
                  .orElseThrow(() -> new NotFoundException("Fulfillment", fulfillmentId));
          if (fulfillment.getStatus() != FulfillmentStatus.FAILED) {
            throw new ConflictException(
                "fulfillment "
                    + fulfillmentId
                    + " is "
                    + fulfillment.getStatus()
                    + ", not FAILED; only a failed fulfillment can be refunded");
          }
          // A failure is resolved once: a refunded-or-replaced fulfillment can't be refunded again.
          if (fulfillment.getResolution() != null) {
            throw new ConflictException(
                "fulfillment "
                    + fulfillmentId
                    + " already resolved as "
                    + fulfillment.getResolution());
          }

          // Refund only THIS fulfillment's value — the grand total the invoice would have carried
          // had it been delivered — NOT the order's whole unallocated prepayment (that is an order
          // cancel). The order continues, so its other fulfillments must keep their funding. No
          // invoice exists for a SHIPPED-failed fulfillment, so the refund is direct-from-Payment.
          BigDecimal fulfillmentValue =
              fulfillmentValueOf(
                  orderLinesById(orderRepo, fulfillment.getSalesOrderId()),
                  fulfillmentRepo.findLinesByFulfillmentId(fulfillmentId));

          // Draw that value FIFO across the order's unallocated prepayment(s): each Payment
          // contributes min(its unallocated, remaining). createDirectPendingInTx caps each refund
          // at the Payment's unallocated balance, so a partial draw is always valid.
          List<Payment> payments =
              paymentRepo.findUnallocatedByOrderForUpdate(orgId, fulfillment.getSalesOrderId());
          List<Refund> refunds = new ArrayList<>();
          BigDecimal remaining = fulfillmentValue;
          BigDecimal pendingRefundTotal = BigDecimal.ZERO;
          for (Payment payment : payments) {
            if (remaining.signum() <= 0) {
              break;
            }
            BigDecimal available = payment.getUnallocatedAmount();
            if (available.signum() <= 0) {
              continue;
            }
            BigDecimal amount = available.min(remaining);
            Refund refund =
                refundService.createDirectPendingInTx(
                    txDsl,
                    orgId,
                    payment,
                    amount,
                    method,
                    "fulfillment " + fulfillmentId + " failed",
                    callerIsOwnerOrAdmin,
                    now);
            refunds.add(refund);
            remaining = remaining.subtract(amount);
            pendingRefundTotal = pendingRefundTotal.add(amount);
          }
          if (remaining.signum() > 0) {
            // Invariant: a PAID order's prepayment covers every fulfillment, and a failed
            // (never-delivered) fulfillment's share was never allocated — so unallocated should
            // always cover it. Log loudly if that ever breaks rather than silently under-refunding.
            log.warn(
                "Failed-fulfillment refund under-covered: fulfillment={} order={} value={}"
                    + " refunded={} shortfall={}",
                fulfillmentId,
                fulfillment.getSalesOrderId(),
                fulfillmentValue,
                pendingRefundTotal,
                remaining);
          }

          fulfillment.resolve(FulfillmentResolution.REFUNDED, now);
          fulfillmentRepo.updateStatus(fulfillment);

          log.info(
              "Refunded failed fulfillment id={} orgId={} order={} pendingRefunds={} total={}",
              fulfillmentId,
              orgId,
              fulfillment.getSalesOrderId(),
              refunds.size(),
              pendingRefundTotal);
          return new FailedRefundResult(fulfillment, refunds, pendingRefundTotal);
        });
  }

  /**
   * Replace a FAILED fulfillment by re-shipping its goods: create a new PENDING fulfillment for the
   * same order lines, funded by the prepayment that was never refunded. The failed fulfillment is
   * stamped REPLACED (resolved once — so it can no longer be refunded). Its original reservations
   * were consumed at ship, so the lines are re-reserved from current {@code available} stock first;
   * if any line is short the whole call fails 409 (the admin must restock — e.g. record the goods'
   * physical return — before replacing). No money moves here: the replacement's invoice is funded
   * at its own delivery. See {@code fulfillment.md} §FAILED.
   */
  public FulfillmentView replaceFailed(
      UUID orgId,
      UUID fulfillmentId,
      String carrier,
      String trackingNumber,
      String notes,
      ActorContext actor) {
    if (orgId == null) {
      throw new ValidationException("orgId is required");
    }
    if (fulfillmentId == null) {
      throw new ValidationException("fulfillmentId is required");
    }
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          FulfillmentRepository fulfillmentRepo = fulfillmentRepoFactory.create(txDsl);
          SalesOrderRepository orderRepo = salesOrderRepoFactory.create(txDsl);

          Fulfillment failed =
              fulfillmentRepo
                  .findByIdForUpdate(orgId, fulfillmentId)
                  .orElseThrow(() -> new NotFoundException("Fulfillment", fulfillmentId));
          if (failed.getStatus() != FulfillmentStatus.FAILED) {
            throw new ConflictException(
                "fulfillment "
                    + fulfillmentId
                    + " is "
                    + failed.getStatus()
                    + ", not FAILED; only a failed fulfillment can be replaced");
          }
          if (failed.getResolution() != null) {
            throw new ConflictException(
                "fulfillment " + fulfillmentId + " already resolved as " + failed.getResolution());
          }

          UUID orderId = failed.getSalesOrderId();
          SalesOrder order =
              orderRepo
                  .findByIdForUpdate(orgId, orderId)
                  .orElseThrow(() -> new NotFoundException("SalesOrder", orderId));

          // If the order left PAID/FULFILLING between fail and replace, that's a resource-state
          // conflict (409), not a malformed request. Guard here with the right status before any
          // reservation work — createInTx's requireFulfillable would otherwise surface it as a 400.
          if (order.getStatus() != OrderStatus.PAID
              && order.getStatus() != OrderStatus.FULFILLING) {
            throw new ConflictException(
                "order "
                    + order.getOrderNumber()
                    + " is "
                    + order.getStatus()
                    + ", not PAID/FULFILLING; cannot replace a fulfillment on it");
          }

          // The failed fulfillment's order lines, in order — re-reserve exactly these.
          List<UUID> lineIds = new ArrayList<>();
          for (FulfillmentLine fl : fulfillmentRepo.findLinesByFulfillmentId(fulfillmentId)) {
            lineIds.add(fl.getSalesOrderLineId());
          }
          List<SalesOrderLine> linesToReserve = new ArrayList<>();
          for (SalesOrderLine ol : orderRepo.findLinesByOrderId(orderId)) {
            if (lineIds.contains(ol.getId())) {
              linesToReserve.add(ol);
            }
          }

          // Re-reserve from current stock (originals were consumed at ship). Throws
          // InsufficientStockException (409) if short — and the whole replace rolls back.
          reservationService.reserveForOrder(txDsl, orgId, order, linesToReserve, actor);

          // Resolve the failed one, then build the replacement over the fresh reservations.
          failed.resolve(FulfillmentResolution.REPLACED, now);
          fulfillmentRepo.updateStatus(failed);

          List<LineInput> replacementLines = new ArrayList<>(lineIds.size());
          for (UUID lineId : lineIds) {
            replacementLines.add(new LineInput(lineId));
          }
          FulfillmentView replacement =
              createInTx(
                  txDsl,
                  orgId,
                  orderId,
                  replacementLines,
                  carrier,
                  trackingNumber,
                  notes,
                  fulfillmentId,
                  actor,
                  now);

          log.info(
              "Replaced failed fulfillment id={} orgId={} order={} with replacement id={}",
              fulfillmentId,
              orgId,
              orderId,
              replacement.fulfillment().getId());
          return replacement;
        });
  }

  /**
   * Record that a FAILED fulfillment's goods physically returned to the warehouse: write a {@code
   * +stock} {@code inventory_log} row per product (reason {@code RESTOCKED_FAILED_FULFILLMENT} —
   * the spec's {@code due_to='failed_fulfillment'}) and stamp {@code returned_at}. Only {@code
   * on_hand} rises; {@code reserved} is untouched (the reservation was consumed at SHIPPED).
   * Idempotent — a second return is rejected (409) so the same goods are never restocked twice.
   */
  public FulfillmentView recordReturn(UUID orgId, UUID fulfillmentId, ActorContext actor) {
    if (orgId == null) {
      throw new ValidationException("orgId is required");
    }
    if (fulfillmentId == null) {
      throw new ValidationException("fulfillmentId is required");
    }
    return rootDsl.transactionResult(
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          FulfillmentRepository fulfillmentRepo = fulfillmentRepoFactory.create(txDsl);
          InventoryRepository inventoryRepo = inventoryRepoFactory.create(txDsl);
          InventoryReservationRepository reservationRepo = reservationRepoFactory.create(txDsl);
          InventoryLogRepository inventoryLogRepo = inventoryLogRepoFactory.create(txDsl);

          Fulfillment fulfillment =
              fulfillmentRepo
                  .findByIdForUpdate(orgId, fulfillmentId)
                  .orElseThrow(() -> new NotFoundException("Fulfillment", fulfillmentId));
          if (fulfillment.getStatus() != FulfillmentStatus.FAILED) {
            throw new ConflictException(
                "fulfillment "
                    + fulfillmentId
                    + " is "
                    + fulfillment.getStatus()
                    + ", not FAILED; only a failed fulfillment's goods can be returned");
          }
          if (fulfillment.getReturnedAt() != null) {
            throw new ConflictException(
                "fulfillment " + fulfillmentId + " goods already returned to stock");
          }

          List<FulfillmentLine> lines = fulfillmentRepo.findLinesByFulfillmentId(fulfillmentId);
          if (lines.isEmpty()) {
            throw new ValidationException(
                "fulfillment " + fulfillmentId + " has no lines to return");
          }

          // Resolve the (now CONSUMED) reservations to recover each line's product, then aggregate
          // the returned quantity per product so a SKU on two lines writes a single +stock row.
          List<UUID> reservationIds = new ArrayList<>(lines.size());
          for (FulfillmentLine line : lines) {
            if (line.getInventoryReservationId() == null) {
              throw new IllegalStateException(
                  "fulfillment line "
                      + line.getId()
                      + " has no reservation to resolve its product");
            }
            reservationIds.add(line.getInventoryReservationId());
          }
          Map<UUID, InventoryReservation> reservationsById = new LinkedHashMap<>();
          for (InventoryReservation r : reservationRepo.findByIds(reservationIds)) {
            reservationsById.put(r.getId(), r);
          }
          Map<UUID, Integer> returnedByProduct = new LinkedHashMap<>();
          for (FulfillmentLine line : lines) {
            InventoryReservation r = reservationsById.get(line.getInventoryReservationId());
            if (r == null) {
              throw new IllegalStateException(
                  "reservation " + line.getInventoryReservationId() + " not found for return");
            }
            returnedByProduct.merge(r.getProductId(), line.getQuantity(), Integer::sum);
          }

          List<UUID> sortedProductIds = returnedByProduct.keySet().stream().sorted().toList();
          Map<UUID, Inventory> locked = inventoryRepo.lockForUpdate(orgId, sortedProductIds);
          for (UUID pid : sortedProductIds) {
            int qty = returnedByProduct.get(pid);
            Inventory current = locked.get(pid);
            if (current == null) {
              throw new IllegalStateException(
                  "no inventory row for returned product " + pid + " in org " + orgId);
            }
            // +stock, reserved unchanged: the goods are back on the shelf.
            Inventory updated =
                inventoryRepo.adjustQuantities(orgId, pid, qty, 0, current.getVersion());
            inventoryLogRepo.insert(
                orgId,
                pid,
                qty,
                0,
                updated.getStockQty(),
                updated.getReservedQty(),
                StockReason.RESTOCKED_FAILED_FULFILLMENT,
                fulfillment.getSalesOrderId(),
                actor);
          }

          fulfillment.markReturned(now);
          fulfillmentRepo.updateStatus(fulfillment);

          log.info(
              "Returned failed fulfillment id={} orgId={} order={} products={}",
              fulfillmentId,
              orgId,
              fulfillment.getSalesOrderId(),
              sortedProductIds.size());
          return new FulfillmentView(fulfillment, lines);
        });
  }

  /**
   * Create a fulfillment directly in DELIVERED state inside the caller's {@code txDsl} — the
   * in-store checkout path. There is no PENDING/SHIPPED phase and no reservation to consume: per
   * product the stock must be {@code available}, then {@code stock_qty} is decremented (a {@code
   * -stock} SOLD log row) while {@code reserved_qty} stays untouched. Throws {@link
   * InsufficientStockException} (409) listing every short product, which rolls back the surrounding
   * checkout txn.
   */
  public FulfillmentView createDelivered(
      DSLContext txDsl,
      UUID orgId,
      SalesOrder order,
      List<DeliveredLineInput> lines,
      String carrier,
      String trackingNumber,
      String notes,
      ActorContext actor,
      OffsetDateTime now) {

    FulfillmentRepository fulfillmentRepo = fulfillmentRepoFactory.create(txDsl);
    InventoryRepository inventoryRepo = inventoryRepoFactory.create(txDsl);
    InventoryLogRepository inventoryLogRepo = inventoryLogRepoFactory.create(txDsl);

    // Aggregate demand per product so the same SKU on two lines locks once and is checked against
    // the sum (mirrors ReservationService); lock in product_id ASC order for deadlock safety.
    Map<UUID, Integer> demandByProduct = new LinkedHashMap<>();
    for (DeliveredLineInput in : lines) {
      demandByProduct.merge(in.productId(), in.quantity(), Integer::sum);
    }
    List<UUID> sortedProductIds = demandByProduct.keySet().stream().sorted().toList();
    Map<UUID, Inventory> locked = inventoryRepo.lockForUpdate(orgId, sortedProductIds);

    // Availability check — collect every shortage (a missing inventory row is available = 0).
    List<Shortage> shortages = new ArrayList<>();
    for (UUID pid : sortedProductIds) {
      int requested = demandByProduct.get(pid);
      Inventory inv = locked.get(pid);
      int available = (inv == null) ? 0 : inv.getAvailableQty();
      if (available < requested) {
        shortages.add(new Shortage(pid, requested, available));
      }
    }
    if (!shortages.isEmpty()) {
      log.info(
          "In-store sale failed for order={} number={} — {} shortage(s)",
          order.getId(),
          order.getOrderNumber(),
          shortages.size());
      throw new InsufficientStockException(shortages);
    }

    // -stock only (reserved unchanged): the customer takes the goods now, no reservation existed.
    List<LowStockNotifier.StockMove> moves = new ArrayList<>(sortedProductIds.size());
    for (UUID pid : sortedProductIds) {
      int qty = demandByProduct.get(pid);
      Inventory current = locked.get(pid); // non-null: shortages was empty
      Inventory updated = inventoryRepo.adjustQuantities(orgId, pid, -qty, 0, current.getVersion());
      inventoryLogRepo.insert(
          orgId,
          pid,
          -qty,
          0,
          updated.getStockQty(),
          updated.getReservedQty(),
          StockReason.SOLD,
          order.getId(),
          actor);
      moves.add(
          new LowStockNotifier.StockMove(
              pid, current.getAvailableQty(), updated.getAvailableQty()));
    }
    // The in-store sale is the other place a sale reduces what can be sold; same txn, same rule
    // (stories/reorder_point.md). A ship is deliberately NOT a caller — it leaves available as is.
    if (lowStockNotifier != null) {
      lowStockNotifier.afterSale(txDsl, orgId, moves);
    }

    UUID fulfillmentId = UUID.randomUUID();
    List<FulfillmentLine> fulfillmentLines = new ArrayList<>(lines.size());
    for (DeliveredLineInput in : lines) {
      // inventory_reservation_id = NULL: in-store lines never had a reservation.
      fulfillmentLines.add(
          FulfillmentLine.create(
              UUID.randomUUID(), fulfillmentId, in.salesOrderLineId(), in.quantity(), null));
    }
    Fulfillment fulfillment =
        Fulfillment.createDelivered(
            fulfillmentId,
            orgId,
            order.getId(),
            Text.normalizeText(carrier),
            Text.normalizeNumeric(trackingNumber),
            Text.normalizeText(notes),
            now);
    fulfillmentRepo.insert(fulfillment, fulfillmentLines);

    log.info(
        "Created DELIVERED fulfillment id={} orgId={} order={} products={} by actor={}",
        fulfillmentId,
        orgId,
        order.getOrderNumber(),
        sortedProductIds.size(),
        actor == null ? null : actor.actorId());
    return new FulfillmentView(fulfillment, fulfillmentLines);
  }

  // Reads (stories/fulfillment_reads.md)

  /** One page of the fulfillment queue/ledger plus the filtered total (for tab badges). */
  public record FulfillmentPage(List<FulfillmentView> items, long total) {}

  /** An order's shipment story: the header + every fulfillment oldest-first, each with lines. */
  public record OrderFulfillments(SalesOrder order, List<FulfillmentView> fulfillments) {}

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  /**
   * Read one fulfillment + its lines — the detail view behind the queue row. Read-only on {@code
   * rootDsl}, no lock: mutations re-read {@code FOR UPDATE} inside their own transactions, so a
   * stale read can never corrupt a write. Decorated with the parent order's number and the
   * fulfillment's monetary value ({@link #fulfillmentValueOf} — the exact figure {@link
   * #refundFailed} would refund).
   *
   * @throws NotFoundException if the fulfillment is not in {@code orgId}
   */
  public FulfillmentView get(UUID orgId, UUID fulfillmentId) {
    if (fulfillmentId == null) {
      throw new ValidationException("fulfillment id is required");
    }
    FulfillmentRepository repo = fulfillmentRepoFactory.create(rootDsl);
    Fulfillment fulfillment =
        repo.findById(orgId, fulfillmentId)
            .orElseThrow(() -> new NotFoundException("Fulfillment", fulfillmentId));
    SalesOrderRepository orderRepo = salesOrderRepoFactory.create(rootDsl);
    UUID orderId = fulfillment.getSalesOrderId();
    List<FulfillmentLine> lines = repo.findLinesByFulfillmentId(fulfillmentId);
    return new FulfillmentView(
        fulfillment,
        lines,
        orderRepo.findOrderNumbersByIds(orgId, List.of(orderId)).get(orderId),
        fulfillmentValueOf(orderLinesById(orderRepo, orderId), lines));
  }

  /**
   * Read one page of the org's fulfillments — filtered by {@code status} it is the packing/shipping
   * queue (oldest first, the FIFO worklist); unfiltered it is the ledger (newest first). Mirrors
   * {@code PaymentTransactionService#list}: {@code page} floors at 0, {@code size} is clamped to
   * {@code [1, MAX_PAGE_SIZE]}; lines and order numbers are batch-loaded (one query each per page,
   * not per row). Rows carry no {@code fulfillmentValue} — pricing a page would need every parent
   * order's lines; the detail/by-order reads carry it, and that is where the refund flow lives.
   */
  public FulfillmentPage list(UUID orgId, FulfillmentStatus status, int page, int size) {
    int p = Math.max(page, 0);
    int s = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    FulfillmentRepository repo = fulfillmentRepoFactory.create(rootDsl);
    List<Fulfillment> items = repo.list(orgId, status, p * s, s);
    long total = repo.count(orgId, status);
    Map<UUID, String> orderNumbers =
        salesOrderRepoFactory
            .create(rootDsl)
            .findOrderNumbersByIds(
                orgId, items.stream().map(Fulfillment::getSalesOrderId).distinct().toList());
    Map<UUID, List<FulfillmentLine>> lines =
        repo.findLinesByFulfillmentIds(items.stream().map(Fulfillment::getId).toList());
    List<FulfillmentView> views =
        items.stream()
            .map(
                f ->
                    new FulfillmentView(
                        f,
                        lines.getOrDefault(f.getId(), List.of()),
                        orderNumbers.get(f.getSalesOrderId()),
                        null))
            .toList();
    return new FulfillmentPage(views, total);
  }

  /**
   * The shipment story of an order — every fulfillment ever created for it regardless of status,
   * oldest first ({@code created_at ASC}), each with its lines, order number and monetary value,
   * plus the order header so the panel renders standalone. The by-order mirror of {@code
   * PaymentService#listForOrder}. No pagination: fulfillment count is bounded by the order's line
   * count.
   *
   * @throws NotFoundException if the order is not in {@code orgId}
   */
  public OrderFulfillments listForOrder(UUID orgId, UUID salesOrderId) {
    if (salesOrderId == null) {
      throw new ValidationException("sales order id is required");
    }
    SalesOrderRepository orderRepo = salesOrderRepoFactory.create(rootDsl);
    SalesOrder order =
        orderRepo
            .findById(orgId, salesOrderId)
            .orElseThrow(() -> new NotFoundException("SalesOrder", salesOrderId));
    FulfillmentRepository repo = fulfillmentRepoFactory.create(rootDsl);
    List<Fulfillment> fulfillments = repo.findByOrderId(orgId, salesOrderId);
    Map<UUID, List<FulfillmentLine>> lines =
        repo.findLinesByFulfillmentIds(fulfillments.stream().map(Fulfillment::getId).toList());
    Map<UUID, SalesOrderLine> orderLines = orderLinesById(orderRepo, salesOrderId);
    List<FulfillmentView> views =
        fulfillments.stream()
            .map(
                f -> {
                  List<FulfillmentLine> fLines = lines.getOrDefault(f.getId(), List.of());
                  return new FulfillmentView(
                      f, fLines, order.getOrderNumber(), fulfillmentValueOf(orderLines, fLines));
                })
            .toList();
    return new OrderFulfillments(order, views);
  }

  /** The order's lines keyed by id — the pricing source for {@link #fulfillmentValueOf}. */
  private static Map<UUID, SalesOrderLine> orderLinesById(
      SalesOrderRepository orderRepo, UUID salesOrderId) {
    Map<UUID, SalesOrderLine> byId = new LinkedHashMap<>();
    for (SalesOrderLine ol : orderRepo.findLinesByOrderId(salesOrderId)) {
      byId.put(ol.getId(), ol);
    }
    return byId;
  }

  /**
   * The monetary value of a fulfillment — the grand total the invoice for its lines would carry at
   * delivery ({@link InvoiceService#grandTotalOf}: identical scale and rounding to real issuance).
   * The single valuation used both by {@link #refundFailed} to size the refund (and hence the
   * OWNER-approval threshold check) and by the reads that expose it, so a client-side threshold
   * pre-warning can never disagree with the guard.
   */
  private static BigDecimal fulfillmentValueOf(
      Map<UUID, SalesOrderLine> orderLinesById, List<FulfillmentLine> fulfillmentLines) {
    List<InvoiceService.LineSpec> specs = new ArrayList<>();
    for (FulfillmentLine fl : fulfillmentLines) {
      SalesOrderLine ol = orderLinesById.get(fl.getSalesOrderLineId());
      if (ol == null) {
        throw new IllegalStateException(
            "fulfillment line " + fl.getId() + " references unknown order line");
      }
      specs.add(
          new InvoiceService.LineSpec(
              ol.getProductId(),
              ol.getDescription(),
              fl.getQuantity(),
              ol.getUnitPrice(),
              ol.getTaxRate()));
    }
    return InvoiceService.grandTotalOf(specs);
  }

  private void requireFulfillable(SalesOrder order) {
    if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.FULFILLING) {
      throw new ValidationException(
          "order "
              + order.getOrderNumber()
              + " is "
              + order.getStatus()
              + "; must be PAID to ship");
    }
  }

  private void validateCreateInputs(UUID salesOrderId, List<LineInput> lines) {
    if (salesOrderId == null) {
      throw new ValidationException("sales_order_id is required");
    }
    if (lines == null || lines.isEmpty()) {
      throw new ValidationException("lines must not be empty");
    }
    java.util.Set<UUID> seen = new java.util.HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      LineInput l = lines.get(i);
      if (l == null || l.salesOrderLineId() == null) {
        throw new ValidationException("lines[" + i + "].sales_order_line_id is required");
      }
      if (!seen.add(l.salesOrderLineId())) {
        throw new ValidationException(
            "duplicate sales_order_line_id in lines: " + l.salesOrderLineId());
      }
    }
  }
}
