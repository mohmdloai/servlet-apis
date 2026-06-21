package com.loai.inventory.service;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.FulfillmentLine;
import com.loai.inventory.domain.model.Inventory;
import com.loai.inventory.domain.model.InventoryReservation;
import com.loai.inventory.domain.model.OrderStatus;
import com.loai.inventory.domain.model.Payment;
import com.loai.inventory.domain.model.PaymentAllocation;
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
import com.loai.inventory.domain.repository.PaymentAllocationRepository;
import com.loai.inventory.domain.repository.PaymentAllocationRepositoryFactory;
import com.loai.inventory.domain.repository.PaymentRepository;
import com.loai.inventory.domain.repository.PaymentRepositoryFactory;
import com.loai.inventory.domain.repository.SalesInvoiceRepository;
import com.loai.inventory.domain.repository.SalesInvoiceRepositoryFactory;
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
 * Outbound flow: ship and deliver part of a paid order. Slice specs {@code
 * stories/ship_fulfillment.md} and {@code stories/deliver_issue_invoice.md}.
 *
 * <p>Three steps, each its own DB transaction:
 *
 * <ol>
 *   <li>{@link #create} — build a PENDING {@link Fulfillment} with a subset of the order's lines.
 *       Nothing physically moves yet; each line is linked to the ACTIVE reservation it will
 *       consume.
 *   <li>{@link #ship} — PENDING → SHIPPED. The consequential transition: per line write a {@code
 *       -stock} {@code inventory_log} row, mark the linked reservation CONSUMED, and decrement
 *       {@code inventory.on_hand} and {@code reserved} by the same amount (so {@code available} is
 *       unchanged). The order flips PAID → FULFILLING on its first shipment.
 *   <li>{@link #markDelivered} — SHIPPED → DELIVERED. Issues a {@link SalesInvoice} for the
 *       delivered lines, auto-allocates the order's prepayment FIFO, and rolls the order up to
 *       FULFILLED / CLOSED — the one transaction that ties the outbound flow together.
 * </ol>
 *
 * <p>v1 ships whole lines: each fulfillment line consumes one line's single ACTIVE reservation in
 * full. "Part of the order" therefore means a subset of lines (the canonical partial-fulfillment
 * shape in {@code sys-analysis/outbound/fulfillment.md}); partial-quantity-within-a-line is
 * deferred.
 */
public final class FulfillmentService {

  private static final Logger log = LoggerFactory.getLogger(FulfillmentService.class);

  private final DSLContext rootDsl;
  private final FulfillmentRepositoryFactory fulfillmentRepoFactory;
  private final SalesOrderRepositoryFactory salesOrderRepoFactory;
  private final InventoryRepositoryFactory inventoryRepoFactory;
  private final InventoryReservationRepositoryFactory reservationRepoFactory;
  private final InventoryLogRepositoryFactory inventoryLogRepoFactory;
  private final SalesInvoiceRepositoryFactory salesInvoiceRepoFactory;
  private final PaymentRepositoryFactory paymentRepoFactory;
  private final PaymentAllocationRepositoryFactory paymentAllocationRepoFactory;

  public FulfillmentService(
      DSLContext rootDsl,
      FulfillmentRepositoryFactory fulfillmentRepoFactory,
      SalesOrderRepositoryFactory salesOrderRepoFactory,
      InventoryRepositoryFactory inventoryRepoFactory,
      InventoryReservationRepositoryFactory reservationRepoFactory,
      InventoryLogRepositoryFactory inventoryLogRepoFactory,
      SalesInvoiceRepositoryFactory salesInvoiceRepoFactory,
      PaymentRepositoryFactory paymentRepoFactory,
      PaymentAllocationRepositoryFactory paymentAllocationRepoFactory) {
    this.rootDsl = rootDsl;
    this.fulfillmentRepoFactory = fulfillmentRepoFactory;
    this.salesOrderRepoFactory = salesOrderRepoFactory;
    this.inventoryRepoFactory = inventoryRepoFactory;
    this.reservationRepoFactory = reservationRepoFactory;
    this.inventoryLogRepoFactory = inventoryLogRepoFactory;
    this.salesInvoiceRepoFactory = salesInvoiceRepoFactory;
    this.paymentRepoFactory = paymentRepoFactory;
    this.paymentAllocationRepoFactory = paymentAllocationRepoFactory;
  }

  /** Which order line to ship; quantity is derived from that line's ACTIVE reservation (v1). */
  public record LineInput(UUID salesOrderLineId) {}

  /** Carries a fulfillment + its lines for response mapping. */
  public record FulfillmentView(Fulfillment fulfillment, List<FulfillmentLine> lines) {}

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
        cfg -> {
          DSLContext txDsl = DSL.using(cfg);
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

          Map<UUID, Integer> alreadyFulfilled =
              fulfillmentRepo.sumFulfilledQtyByOrderLine(salesOrderId);

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

          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          Fulfillment fulfillment =
              Fulfillment.createPending(
                  fulfillmentId,
                  orgId,
                  salesOrderId,
                  trimOrNull(carrier),
                  trimOrNull(trackingNumber),
                  trimOrNull(notes),
                  now);
          fulfillmentRepo.insert(fulfillment, fulfillmentLines);

          log.info(
              "Created PENDING fulfillment id={} orgId={} order={} lines={} by actor={}",
              fulfillment.getId(),
              orgId,
              order.getOrderNumber(),
              fulfillmentLines.size(),
              actor == null ? null : actor.actorId());
          return new FulfillmentView(fulfillment, fulfillmentLines);
        });
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
   * Mark a SHIPPED fulfillment DELIVERED — the one transaction that ties the outbound flow
   * together. In a single DB transaction:
   *
   * <ol>
   *   <li>Fulfillment SHIPPED → DELIVERED.
   *   <li>A SalesInvoice DRAFT is built mirroring the delivered FulfillmentLines (snapshot prices,
   *       tax and customer data), then ISSUED with a gapless per-org per-year invoice number.
   *   <li>The order's prepayment Payment(s) are auto-allocated FIFO (by {@code received_at ASC, id
   *       ASC}, {@code FOR UPDATE}) up to the invoice's grand total — PaymentAllocation rows
   *       inserted, payment {@code unallocated_amount}/status and invoice {@code
   *       paid_amount}/status updated.
   *   <li>The order rolls up: FULFILLED once every line is delivered, then CLOSED once all its
   *       invoices are PAID.
   * </ol>
   *
   * The fulfillment and order rows are locked {@code FOR UPDATE}, so a double deliver fails the
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
          SalesInvoiceRepository invoiceRepo = salesInvoiceRepoFactory.create(txDsl);
          PaymentRepository paymentRepo = paymentRepoFactory.create(txDsl);
          PaymentAllocationRepository allocationRepo = paymentAllocationRepoFactory.create(txDsl);

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

          // (b) Build + issue the invoice mirroring the delivered lines.
          Map<UUID, SalesOrderLine> orderLines = new LinkedHashMap<>();
          for (SalesOrderLine l : orderRepo.findLinesByOrderId(order.getId())) {
            orderLines.put(l.getId(), l);
          }

          UUID invoiceId = UUID.randomUUID();
          List<SalesInvoiceLine> invoiceLines = new ArrayList<>(lines.size());
          BigDecimal subtotal = BigDecimal.ZERO;
          BigDecimal taxTotal = BigDecimal.ZERO;
          for (FulfillmentLine fl : lines) {
            SalesOrderLine ol = orderLines.get(fl.getSalesOrderLineId());
            if (ol == null) {
              throw new IllegalStateException(
                  "fulfillment line " + fl.getId() + " references unknown order line");
            }
            SalesInvoiceLine il =
                SalesInvoiceLine.create(
                    UUID.randomUUID(),
                    invoiceId,
                    ol.getProductId(),
                    ol.getDescription(),
                    fl.getQuantity(),
                    ol.getUnitPrice(),
                    ol.getTaxRate());
            invoiceLines.add(il);
            subtotal = subtotal.add(il.getLineSubtotal());
            taxTotal = taxTotal.add(il.getLineTax());
          }

          Customer customer =
              order.getCustomerId() == null
                  ? null
                  : orderRepo.findCustomerById(orgId, order.getCustomerId()).orElse(null);
          String customerName = invoiceCustomerName(customer);

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
                  customerName,
                  customer == null ? null : customer.getEmail(),
                  customer == null ? null : customer.getPhone(),
                  customer == null ? null : customer.getAddress(),
                  now);

          int year = now.getYear();
          long seq = invoiceRepo.claimInvoiceNumber(orgId, year);
          invoice.issue(String.format("INV-%d-%04d", year, seq), now);
          invoiceRepo.insert(invoice, invoiceLines);

          // (c)+(d)+(e) Auto-allocate prepayment FIFO up to the invoice grand total.
          List<PaymentAllocation> allocations = new ArrayList<>();
          BigDecimal remaining = invoice.getGrandTotal();
          for (Payment payment :
              paymentRepo.findUnallocatedByOrderForUpdate(orgId, order.getId())) {
            if (remaining.signum() <= 0) {
              break;
            }
            BigDecimal amount = payment.getUnallocatedAmount().min(remaining);
            if (amount.signum() <= 0) {
              continue;
            }
            payment.allocate(amount, now);
            paymentRepo.updateAllocationState(payment);

            PaymentAllocation allocation =
                PaymentAllocation.create(
                    UUID.randomUUID(), orgId, payment.getId(), invoiceId, amount, now);
            allocationRepo.insert(allocation);
            allocations.add(allocation);

            invoice.recordAllocation(amount, now);
            remaining = remaining.subtract(amount);
          }
          invoiceRepo.updatePaymentState(invoice);

          // (f) Order roll-up: FULFILLED when every line is delivered; CLOSED when all invoices
          // PAID.
          maybeRollUpOrder(orgId, order, orderLines, invoiceRepo, fulfillmentRepo, orderRepo, now);

          log.info(
              "Delivered fulfillment id={} orgId={} order={} invoice={} grandTotal={} allocated={} "
                  + "invoiceStatus={} orderStatus={}",
              fulfillmentId,
              orgId,
              order.getOrderNumber(),
              invoice.getInvoiceNumber(),
              invoice.getGrandTotal(),
              allocations.size(),
              invoice.getStatus(),
              order.getStatus());
          return new DeliveredView(fulfillment, invoice, invoiceLines, allocations, order);
        });
  }

  /**
   * Roll the order forward after a delivery: FULFILLING → FULFILLED once every order line's
   * delivered quantity equals its ordered quantity, then FULFILLED → CLOSED once every invoice for
   * the order is PAID. Persists only if something changed.
   */
  private void maybeRollUpOrder(
      UUID orgId,
      SalesOrder order,
      Map<UUID, SalesOrderLine> orderLines,
      SalesInvoiceRepository invoiceRepo,
      FulfillmentRepository fulfillmentRepo,
      SalesOrderRepository orderRepo,
      OffsetDateTime now) {
    if (order.getStatus() != OrderStatus.FULFILLING) {
      return; // e.g. already terminal; nothing to roll up
    }
    Map<UUID, Integer> delivered = fulfillmentRepo.sumDeliveredQtyByOrderLine(order.getId());
    boolean allDelivered = true;
    for (SalesOrderLine ol : orderLines.values()) {
      if (delivered.getOrDefault(ol.getId(), 0) < ol.getQuantity()) {
        allDelivered = false;
        break;
      }
    }
    if (!allDelivered) {
      return; // partial delivery: stays FULFILLING
    }

    order.markFulfilled(now);
    boolean allInvoicesPaid =
        invoiceRepo.findByOrderId(orgId, order.getId()).stream().allMatch(SalesInvoice::isPaid);
    if (allInvoicesPaid) {
      order.close(now);
    }
    orderRepo.updateFulfillmentState(order);
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

  private static String trimOrNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}
