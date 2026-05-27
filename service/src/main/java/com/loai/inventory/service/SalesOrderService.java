package com.loai.inventory.service;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.domain.model.OrderChannel;
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
 * Outbound flow TX-1: place an online order.
 *
 * <p>Per the slice spec in {@code stories/place_online_order.md}: creates a SalesOrder + lines in
 * DRAFT then transitions to PENDING_PAYMENT inside one DB transaction. No reservations, no payment,
 * no fulfillment — those are subsequent slices.
 */
public class SalesOrderService {

  private static final Logger log = LoggerFactory.getLogger(SalesOrderService.class);
  private static final BigDecimal DEFAULT_TAX_RATE = BigDecimal.ZERO;
  private static final Duration ONLINE_TTL = Duration.ofHours(24);
  private static final String CURRENCY_EGP = "EGP";

  private final DSLContext rootDsl;
  private final SalesOrderRepositoryFactory repoFactory;
  private final ReservationService reservationService;

  public SalesOrderService(
      DSLContext rootDsl,
      SalesOrderRepositoryFactory repoFactory,
      ReservationService reservationService) {
    this.rootDsl = rootDsl;
    this.repoFactory = repoFactory;
    this.reservationService = reservationService;
  }

  /** Input contact info; {@code name} required, others optional. */
  public record CustomerInput(String name, String email, String phone, String address) {}

  /** Input order line; {@code quantity > 0}, product belongs to {@code orgId}. */
  public record OrderLineInput(UUID productId, int quantity) {}

  /** Carries the placed order + its lines + the resolved customer for response mapping. */
  public record Placed(SalesOrder order, List<SalesOrderLine> lines, Customer customer) {}

  public Placed placeOnlineOrder(
      UUID orgId,
      CustomerInput customer,
      List<OrderLineInput> lines,
      String idempotencyKey,
      String notes,
      ActorContext actor) {

    validateInputs(customer, lines);

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

          // 2. Upsert customer on (orgId, email).
          Customer resolvedCustomer =
              repo.upsertCustomerByEmail(
                  orgId,
                  normalize(customer.email()),
                  trimOrNull(customer.name()),
                  trimOrNull(customer.phone()),
                  trimOrNull(customer.address()));

          // 3. Snapshot product data per line; fail if any product is missing for this org.
          List<UUID> productIds = lines.stream().map(OrderLineInput::productId).toList();
          Map<UUID, SalesOrderRepository.ProductSnapshot> snapshots =
              repo.fetchProductSnapshots(orgId, productIds);
          for (UUID pid : productIds) {
            if (!snapshots.containsKey(pid)) {
              throw new NotFoundException("Product", pid);
            }
          }

          // 4. Build SalesOrderLine list with computed line totals.
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

          // 5. Claim per-org per-year order number.
          OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
          int year = now.getYear();
          long seqVal = repo.claimOrderNumber(orgId, year);
          String orderNumber = String.format("SO-%d-%05d", year, seqVal);

          // 6. Build domain SalesOrder: DRAFT → setTotals → PENDING_PAYMENT, then persist once.
          SalesOrder order =
              SalesOrder.createDraft(
                  orderId,
                  orgId,
                  resolvedCustomer.getId(),
                  orderNumber,
                  OrderChannel.ONLINE,
                  CURRENCY_EGP,
                  idempotencyKey,
                  now);
          order.setTotals(subtotal, taxTotal, discountTotal, now);
          if (notes != null && !notes.isBlank()) {
            order.updateNotes(notes, now);
          }
          OffsetDateTime expiresAt = now.plus(ONLINE_TTL);
          order.markPendingPayment(now, expiresAt);

          // 7. Insert order + lines in the same txn.
          repo.insert(order, orderLines);

          // 8. Reserve stock atomically. Throws InsufficientStockException → rolls back the whole
          //    placement (no order, no lines, no customer write persisted). The idempotent-replay
          //    branch above skips this entirely — reservations were created at original placement.
          reservationService.reserveForOrder(txDsl, orgId, order, orderLines, actor);

          log.info(
              "Placed online order id={} orgId={} number={} customerId={} grandTotal={} lines={}",
              order.getId(),
              orgId,
              order.getOrderNumber(),
              resolvedCustomer.getId(),
              order.getGrandTotal(),
              orderLines.size());

          return new Placed(order, orderLines, resolvedCustomer);
        });
  }

  // Validation

  private void validateInputs(CustomerInput customer, List<OrderLineInput> lines) {
    if (customer == null) {
      throw new ValidationException("customer is required");
    }
    if (customer.email() == null || customer.email().isBlank()) {
      throw new ValidationException("customer.email is required");
    }
    if (customer.name() == null || customer.name().isBlank()) {
      throw new ValidationException("customer.name is required");
    }
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
