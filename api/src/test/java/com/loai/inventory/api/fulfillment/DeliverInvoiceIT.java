package com.loai.inventory.api.fulfillment;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.INVOICE_NUMBER_COUNTER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_ALLOCATION;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE;
import static com.loai.inventory.repository.generated.Tables.SALES_INVOICE_LINE;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.NumberSequenceReconciliationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.enums.PaymentProvider;
import com.loai.inventory.repository.generated.enums.PaymentVerificationStatus;
import com.loai.inventory.repository.generated.enums.ReservationStatus;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.DeliveredView;
import com.loai.inventory.service.FulfillmentService.LineInput;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.NumberSequenceReconciliationService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration coverage for the deliver / issue-invoice / auto-allocate slice ({@code
 * stories/deliver_issue_invoice.md}). Boots one PostgreSQL container, runs all Flyway migrations,
 * and drives {@link FulfillmentService} against the real jOOQ repository factories — no
 * Tomcat/Redis/JWT.
 *
 * <p>The headline property: marking a SHIPPED fulfillment DELIVERED issues a SalesInvoice for the
 * delivered lines, auto-allocates the order's prepayment FIFO, and rolls the order up to
 * FULFILLED/CLOSED — all in one transaction. This exercises the full ONLINE flow end to end: order
 * → reserve → pay → ship → deliver → invoice + allocated + closed.
 */
@Testcontainers
class DeliverInvoiceIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static FulfillmentService service;
  static NumberSequenceReconciliationService reconciliationService;

  private final AtomicInteger seq = new AtomicInteger(1);
  private final ActorContext actor = ActorContext.user(UUID.randomUUID().toString());

  @BeforeAll
  static void startInfra() {
    Flyway.configure()
        .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
        .schemas("inventorydb")
        .locations("classpath:db/migration")
        .load()
        .migrate();

    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(PG.getJdbcUrl());
    cfg.setUsername(PG.getUsername());
    cfg.setPassword(PG.getPassword());
    cfg.setMaximumPoolSize(8);
    cfg.setConnectionInitSql("SET search_path TO inventorydb");
    dataSource = new HikariDataSource(cfg);
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);

    InvoiceService invoiceService =
        new InvoiceService(
            new SalesInvoiceRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl());
    service =
        new FulfillmentService(
            dsl,
            new FulfillmentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new com.loai.inventory.repository.PaymentRepositoryFactoryImpl(),
            invoiceService,
            new com.loai.inventory.service.RefundService(
                dsl,
                new com.loai.inventory.repository.RefundRepositoryFactoryImpl(),
                new com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl(),
                new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
                new com.loai.inventory.repository.PaymentRepositoryFactoryImpl(),
                new com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl(),
                new com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl(),
                new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
                new com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl()),
            new com.loai.inventory.service.ReservationService(
                new com.loai.inventory.repository.InventoryRepositoryFactoryImpl(),
                new com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl(),
                new com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl()),
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl));
    reconciliationService =
        new NumberSequenceReconciliationService(
            dsl, new NumberSequenceReconciliationRepositoryFactoryImpl());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void freshSchema() {
    dsl.execute(
        "TRUNCATE payment_allocation, sales_invoice_line, sales_invoice, payment,"
            + " payment_transaction, fulfillment, fulfillment_line, inventory_reservation,"
            + " inventory_log, inventory, sales_order_line, sales_order, customer, product, org,"
            + " invoice_number_counter RESTART IDENTITY CASCADE");
  }

  // scenarios

  /** The magic moment: fully-prepaid single-fulfillment order → invoice PAID, order CLOSED. */
  @Test
  void deliver_issuesPaidInvoice_allocatesPrepayment_closesOrder() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "Nadia", "nadia@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 3);
    Order order =
        seedPaidOrder(org, customer, List.of(new Want(product, 3)), "30.00", now().minusHours(2));
    Line line = order.lines().get(0);

    shipLine(org, order, line);
    assertEquals("FULFILLING", orderStatus(order.id()));

    DeliveredView view = service.markDelivered(org, fulfillmentId(org, order), actor);

    // Fulfillment DELIVERED.
    assertEquals("DELIVERED", view.fulfillment().getStatus().name());
    assertNotNull(view.fulfillment().getDeliveredAt());

    // One invoice, ISSUED→PAID, gapless number, frozen totals + customer snapshot.
    assertEquals(1, invoiceCount(org));
    var inv = view.invoice();
    assertEquals("PAID", inv.getStatus().name());
    assertEquals("INV-" + now().getYear() + "-0001", inv.getInvoiceNumber());
    assertEquals(0, new BigDecimal("30.00").compareTo(inv.getGrandTotal()));
    assertEquals(0, new BigDecimal("30.00").compareTo(inv.getPaidAmount()));
    assertEquals("Nadia", invoiceCustomerName(inv.getId()));
    assertEquals(1, invoiceLineCount(inv.getId()));

    // One allocation consuming the full prepayment; payment now ALLOCATED, nothing unallocated.
    assertEquals(1, view.allocations().size());
    assertEquals(0, new BigDecimal("30.00").compareTo(view.allocations().get(0).getAmount()));
    assertEquals("ALLOCATED", paymentStatus(order.paymentId()));
    assertEquals(0, BigDecimal.ZERO.compareTo(paymentUnallocated(order.paymentId())));

    // Order fully fulfilled + invoiced → CLOSED, with fulfilled_at + closed_at stamped.
    assertEquals("CLOSED", orderStatus(order.id()));
    assertNotNull(orderTimestamp(order.id(), SALES_ORDER.FULFILLED_AT));
    assertNotNull(orderTimestamp(order.id(), SALES_ORDER.CLOSED_AT));
  }

  /**
   * Partial delivery: each delivered fulfillment issues its own invoice; order CLOSES on the last.
   */
  @Test
  void partialDelivery_issuesInvoicePerFulfillment_closesOnLastLine() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "Omar", "omar@acme.test");
    UUID a = createProduct(org, "A");
    UUID b = createProduct(org, "B");
    createInventory(org, a, 10, 2);
    createInventory(org, b, 10, 4);
    Order order =
        seedPaidOrder(
            org, customer, List.of(new Want(a, 2), new Want(b, 4)), "60.00", now().minusHours(1));
    Line lineA = order.lines().get(0);
    Line lineB = order.lines().get(1);

    // Deliver line A (20.00 of 60.00).
    UUID fa = shipLine(org, order, lineA);
    DeliveredView va = service.markDelivered(org, fa, actor);
    assertEquals("PAID", va.invoice().getStatus().name());
    assertEquals("INV-" + now().getYear() + "-0001", va.invoice().getInvoiceNumber());
    assertEquals(0, new BigDecimal("20.00").compareTo(va.invoice().getGrandTotal()));
    // Payment partially allocated; 40.00 left for the rest.
    assertEquals("PARTIALLY_ALLOCATED", paymentStatus(order.paymentId()));
    assertEquals(0, new BigDecimal("40.00").compareTo(paymentUnallocated(order.paymentId())));
    // Not all lines delivered → order stays FULFILLING.
    assertEquals("FULFILLING", orderStatus(order.id()));

    // Deliver line B (the remaining 40.00).
    UUID fb = shipLine(org, order, lineB);
    DeliveredView vb = service.markDelivered(org, fb, actor);
    assertEquals("PAID", vb.invoice().getStatus().name());
    assertEquals("INV-" + now().getYear() + "-0002", vb.invoice().getInvoiceNumber());
    assertEquals(0, new BigDecimal("40.00").compareTo(vb.invoice().getGrandTotal()));

    // Two invoices, payment fully consumed, order CLOSED.
    assertEquals(2, invoiceCount(org));
    assertEquals("ALLOCATED", paymentStatus(order.paymentId()));
    assertEquals(0, BigDecimal.ZERO.compareTo(paymentUnallocated(order.paymentId())));
    assertEquals("CLOSED", orderStatus(order.id()));
  }

  /** Re-delivering an already-DELIVERED fulfillment is rejected; no duplicate invoice. */
  @Test
  void deliverAlreadyDelivered_isRejected_noDuplicateInvoice() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "Sara", "sara@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 3);
    Order order =
        seedPaidOrder(org, customer, List.of(new Want(product, 3)), "30.00", now().minusHours(1));

    UUID f = shipLine(org, order, order.lines().get(0));
    service.markDelivered(org, f, actor);

    assertThrows(ConflictException.class, () -> service.markDelivered(org, f, actor));
    assertEquals(1, invoiceCount(org));
  }

  /** Delivering a SHIPPED fulfillment is the only legal path — a PENDING one is rejected. */
  @Test
  void deliverNonShippedFulfillment_isRejected() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "Lina", "lina@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 3);
    Order order =
        seedPaidOrder(org, customer, List.of(new Want(product, 3)), "30.00", now().minusHours(1));

    // Create the fulfillment but do NOT ship it.
    UUID f =
        service
            .create(
                org,
                order.id(),
                List.of(new LineInput(order.lines().get(0).lineId())),
                null,
                null,
                null,
                actor)
            .fulfillment()
            .getId();

    assertThrows(ConflictException.class, () -> service.markDelivered(org, f, actor));
    assertEquals(0, invoiceCount(org));
  }

  /**
   * Two prepayments are consumed FIFO (earliest received_at first): the earlier payment is
   * exhausted before the later one is touched.
   */
  @Test
  void autoAllocation_consumesPaymentsFifoByReceivedAt() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "Hana", "hana@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 5);
    // 5 @ 10.00 = 50.00 grand total.
    Order order = seedPaidOrderNoPayment(org, customer, List.of(new Want(product, 5)), "50.00");
    // P1 (earlier) 30.00; P2 (later) 30.00. Total prepaid 60.00 > 50.00 grand.
    UUID p1 = seedPayment(org, customer, order.id(), "30.00", now().minusHours(3));
    UUID p2 = seedPayment(org, customer, order.id(), "30.00", now().minusHours(1));
    setPrepaid(order.id(), "60.00");

    UUID f = shipLine(org, order, order.lines().get(0));
    DeliveredView view = service.markDelivered(org, f, actor);

    assertEquals("PAID", view.invoice().getStatus().name());
    assertEquals(0, new BigDecimal("50.00").compareTo(view.invoice().getGrandTotal()));

    // FIFO: P1 fully consumed (30), then P2 partially (20); 10 left unallocated on P2.
    assertEquals(0, new BigDecimal("30.00").compareTo(allocatedFromPayment(p1)));
    assertEquals(0, new BigDecimal("20.00").compareTo(allocatedFromPayment(p2)));
    assertEquals("ALLOCATED", paymentStatus(p1));
    assertEquals("PARTIALLY_ALLOCATED", paymentStatus(p2));
    assertEquals(0, BigDecimal.ZERO.compareTo(paymentUnallocated(p1)));
    assertEquals(0, new BigDecimal("10.00").compareTo(paymentUnallocated(p2)));

    // Invoice PAID + all lines delivered → order CLOSED.
    assertEquals("CLOSED", orderStatus(order.id()));
  }

  /**
   * Number-sequence integrity ({@code stories/number_sequence_integrity.md}): a counter that has
   * drifted <em>behind</em> the table makes the next issue re-mint an already-taken number. That
   * must surface as a clean 409 (not a raw 500), roll the whole delivery back, and be healable by
   * the reconcile routine — after which the retry succeeds and numbering resumes gaplessly.
   */
  @Test
  void deliver_counterDriftedBehindTable_is409NotDuplicate_thenReconcileHeals() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "Rana", "rana@acme.test");
    UUID a = createProduct(org, "A");
    UUID b = createProduct(org, "B");
    createInventory(org, a, 10, 2);
    createInventory(org, b, 10, 2);
    Order order =
        seedPaidOrder(
            org, customer, List.of(new Want(a, 2), new Want(b, 2)), "40.00", now().minusHours(1));

    // Deliver line A → INV-YYYY-0001 issued; the counter advances to 2.
    UUID fa = shipLine(org, order, order.lines().get(0));
    DeliveredView va = service.markDelivered(org, fa, actor);
    assertEquals("INV-" + now().getYear() + "-0001", va.invoice().getInvoiceNumber());

    // Simulate the drift the story describes (a seed/import wrote a number without advancing the
    // counter): rewind the counter behind the table, so the next claim regenerates the taken 0001.
    int reset =
        dsl.update(INVOICE_NUMBER_COUNTER)
            .set(INVOICE_NUMBER_COUNTER.NEXT_VAL, 1L)
            .where(INVOICE_NUMBER_COUNTER.ORG_ID.eq(org))
            .execute();
    assertEquals(1, reset);

    // Deliver line B → the allocator re-mints INV-YYYY-0001 → (org, invoice_number) unique
    // violation, translated to a 409 that names the remedy, not an "Unexpected error" 500.
    UUID fb = shipLine(org, order, order.lines().get(1));
    ConflictException ex =
        assertThrows(ConflictException.class, () -> service.markDelivered(org, fb, actor));
    assertTrue(ex.getMessage().contains("Invoice number sequence is out of sync"));

    // The whole delivery rolled back: still exactly one invoice, order still FULFILLING.
    assertEquals(1, invoiceCount(org));
    assertEquals("FULFILLING", orderStatus(order.id()));

    // Repair: reconcile detects the one drifted counter and realigns it forward to MAX+1 = 2.
    NumberSequenceReconciliationService.Summary summary = reconciliationService.reconcile();
    assertEquals(1, summary.counted());
    NumberSequenceReconciliationService.Realignment r = summary.realignments().get(0);
    assertEquals("INVOICE", r.documentType());
    assertEquals(org, r.orgId());
    assertEquals(now().getYear(), r.year());
    assertEquals(Long.valueOf(1L), r.fromNextVal());
    assertEquals(2L, r.toNextVal());

    // The retry now succeeds with the next gapless number and the order closes.
    DeliveredView vb = service.markDelivered(org, fb, actor);
    assertEquals("INV-" + now().getYear() + "-0002", vb.invoice().getInvoiceNumber());
    assertEquals(2, invoiceCount(org));
    assertEquals("CLOSED", orderStatus(order.id()));

    // Forward-only + idempotent: a second reconcile over the healed DB realigns nothing.
    assertEquals(0, reconciliationService.reconcile().counted());
  }

  /**
   * Review-request notification (roadmap item 1, {@code stories/review_request_on_delivery.md}):
   * completing an order's delivery raises exactly one {@code REVIEW_REQUESTED} to the customer, on
   * both the in-app and email channels. It fires on the FULFILLED/CLOSED roll-up, not before.
   */
  @Test
  void deliver_completesOrder_raisesOneReviewRequest_onBothChannels() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "Yara", "yara@acme.test");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 2);
    Order order =
        seedPaidOrder(org, customer, List.of(new Want(product, 2)), "20.00", now().minusHours(1));

    UUID f = shipLine(org, order, order.lines().get(0));
    assertEquals(0L, reviewRequestCount(customer)); // shipped, not yet delivered → nothing

    service.markDelivered(org, f, actor);

    assertEquals("CLOSED", orderStatus(order.id()));
    assertEquals(1L, reviewRequestCount(customer));
    assertEquals(Set.of("in_app", "email"), reviewRequestChannels(customer));
  }

  /**
   * Per-order-once idempotency: a multi-shipment order fires the review request only when its last
   * line delivers — a partial delivery (order stays FULFILLING) raises nothing.
   */
  @Test
  void partialDelivery_raisesReviewRequestOnlyOnFinalDelivery() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org, "Tarek", "tarek@acme.test");
    UUID a = createProduct(org, "A");
    UUID b = createProduct(org, "B");
    createInventory(org, a, 10, 1);
    createInventory(org, b, 10, 1);
    Order order =
        seedPaidOrder(
            org, customer, List.of(new Want(a, 1), new Want(b, 1)), "20.00", now().minusHours(1));

    UUID fa = shipLine(org, order, order.lines().get(0));
    service.markDelivered(org, fa, actor);
    assertEquals("FULFILLING", orderStatus(order.id()));
    assertEquals(0L, reviewRequestCount(customer)); // partial → no request yet

    UUID fb = shipLine(org, order, order.lines().get(1));
    service.markDelivered(org, fb, actor);
    assertEquals(1L, reviewRequestCount(customer)); // exactly one, on completion
  }

  // flow helpers

  /** How many REVIEW_REQUESTED notifications exist for this customer. */
  private long reviewRequestCount(UUID customer) {
    return dsl.fetchOne(
            "SELECT count(*) FROM notification"
                + " WHERE type = 'REVIEW_REQUESTED' AND recipient_customer_id = ?",
            customer)
        .get(0, Long.class);
  }

  /** The distinct delivery channels created for this customer's REVIEW_REQUESTED notifications. */
  private Set<String> reviewRequestChannels(UUID customer) {
    return new HashSet<>(
        dsl.fetch(
                "SELECT d.channel FROM notification n"
                    + " JOIN notification_delivery d ON d.notification_id = n.id"
                    + " WHERE n.type = 'REVIEW_REQUESTED' AND n.recipient_customer_id = ?",
                customer)
            .getValues("channel", String.class));
  }

  /** Create a single-line PENDING fulfillment and ship it; returns the fulfillment id. */
  private UUID shipLine(UUID org, Order order, Line line) {
    UUID f =
        service
            .create(org, order.id(), List.of(new LineInput(line.lineId())), null, null, null, actor)
            .fulfillment()
            .getId();
    service.ship(org, f, actor);
    return f;
  }

  // seed helpers

  private record Want(UUID productId, int qty) {}

  private record Line(UUID lineId, UUID productId, int qty, UUID reservationId) {}

  private record Order(UUID id, String number, UUID paymentId, List<Line> lines) {}

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private UUID createOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
        .execute();
    return id;
  }

  private UUID createCustomer(UUID org, String name, String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(com.loai.inventory.repository.generated.Tables.CUSTOMER)
        .set(com.loai.inventory.repository.generated.Tables.CUSTOMER.ID, id)
        .set(com.loai.inventory.repository.generated.Tables.CUSTOMER.ORG_ID, org)
        .set(com.loai.inventory.repository.generated.Tables.CUSTOMER.NAME, name)
        .set(com.loai.inventory.repository.generated.Tables.CUSTOMER.EMAIL, id + "-" + email)
        .execute();
    return id;
  }

  private UUID createProduct(UUID org, String sku) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    return id;
  }

  private void createInventory(UUID org, UUID product, int stockQty, int reservedQty) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, product)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .set(INVENTORY.RESERVED_QTY, reservedQty)
        .execute();
  }

  private Order seedPaidOrder(
      UUID org, UUID customer, List<Want> wants, String grandTotal, OffsetDateTime paymentAt) {
    Order order = seedPaidOrderNoPayment(org, customer, wants, grandTotal);
    UUID paymentId = seedPayment(org, customer, order.id(), grandTotal, paymentAt);
    return new Order(order.id(), order.number(), paymentId, order.lines());
  }

  private Order seedPaidOrderNoPayment(
      UUID org, UUID customer, List<Want> wants, String grandTotal) {
    UUID orderId = UUID.randomUUID();
    String number = "SO-" + now().getYear() + "-" + String.format("%05d", seq.getAndIncrement());
    BigDecimal grand = new BigDecimal(grandTotal);
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, org)
        .set(SALES_ORDER.CUSTOMER_ID, customer)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, OrderStatus.PAID)
        .set(SALES_ORDER.SUBTOTAL, grand)
        .set(SALES_ORDER.GRAND_TOTAL, grand)
        .set(SALES_ORDER.PREPAID_AMOUNT, grand)
        .set(SALES_ORDER.CURRENCY, "EGP")
        .execute();

    List<Line> lines = new ArrayList<>(wants.size());
    for (Want w : wants) {
      UUID lineId = UUID.randomUUID();
      BigDecimal lineTotal = new BigDecimal("10.00").multiply(BigDecimal.valueOf(w.qty()));
      dsl.insertInto(SALES_ORDER_LINE)
          .set(SALES_ORDER_LINE.ID, lineId)
          .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
          .set(SALES_ORDER_LINE.PRODUCT_ID, w.productId())
          .set(SALES_ORDER_LINE.DESCRIPTION, "line")
          .set(SALES_ORDER_LINE.QUANTITY, w.qty())
          .set(SALES_ORDER_LINE.UNIT_PRICE, new BigDecimal("10.00"))
          .set(SALES_ORDER_LINE.LINE_SUBTOTAL, lineTotal)
          .set(SALES_ORDER_LINE.LINE_TOTAL, lineTotal)
          .execute();

      UUID reservationId = UUID.randomUUID();
      dsl.insertInto(INVENTORY_RESERVATION)
          .set(INVENTORY_RESERVATION.ID, reservationId)
          .set(INVENTORY_RESERVATION.ORG_ID, org)
          .set(INVENTORY_RESERVATION.PRODUCT_ID, w.productId())
          .set(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID, lineId)
          .set(INVENTORY_RESERVATION.QUANTITY, w.qty())
          .set(INVENTORY_RESERVATION.STATUS, ReservationStatus.ACTIVE)
          .execute();
      lines.add(new Line(lineId, w.productId(), w.qty(), reservationId));
    }
    return new Order(orderId, number, null, lines);
  }

  /**
   * Insert a verified payment_transaction + a RECEIVED, fully-unallocated payment for the order.
   */
  private UUID seedPayment(
      UUID org, UUID customer, UUID orderId, String amount, OffsetDateTime receivedAt) {
    UUID txnId = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, txnId)
        .set(PAYMENT_TRANSACTION.ORG_ID, org)
        .set(PAYMENT_TRANSACTION.PROVIDER, PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "IPN-" + seq.getAndIncrement())
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, receivedAt)
        .execute();

    UUID paymentId = UUID.randomUUID();
    dsl.insertInto(PAYMENT)
        .set(PAYMENT.ID, paymentId)
        .set(PAYMENT.ORG_ID, org)
        .set(PAYMENT.CUSTOMER_ID, customer)
        .set(PAYMENT.SALES_ORDER_ID, orderId)
        .set(PAYMENT.PAYMENT_TRANSACTION_ID, txnId)
        .set(PAYMENT.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.UNALLOCATED_AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.STATUS, com.loai.inventory.repository.generated.enums.PaymentStatus.RECEIVED)
        .set(PAYMENT.RECEIVED_AT, receivedAt)
        .execute();
    return paymentId;
  }

  private void setPrepaid(UUID orderId, String prepaid) {
    dsl.update(SALES_ORDER)
        .set(SALES_ORDER.PREPAID_AMOUNT, new BigDecimal(prepaid))
        .where(SALES_ORDER.ID.eq(orderId))
        .execute();
  }

  // query helpers

  private UUID fulfillmentId(UUID org, Order order) {
    return dsl.select(com.loai.inventory.repository.generated.Tables.FULFILLMENT.ID)
        .from(com.loai.inventory.repository.generated.Tables.FULFILLMENT)
        .where(
            com.loai.inventory.repository.generated.Tables.FULFILLMENT.SALES_ORDER_ID.eq(
                order.id()))
        .fetchAny(com.loai.inventory.repository.generated.Tables.FULFILLMENT.ID);
  }

  private int invoiceCount(UUID org) {
    return dsl.fetchCount(dsl.selectFrom(SALES_INVOICE).where(SALES_INVOICE.ORG_ID.eq(org)));
  }

  private int invoiceLineCount(UUID invoiceId) {
    return dsl.fetchCount(
        dsl.selectFrom(SALES_INVOICE_LINE)
            .where(SALES_INVOICE_LINE.SALES_INVOICE_ID.eq(invoiceId)));
  }

  private String invoiceCustomerName(UUID invoiceId) {
    return dsl.select(SALES_INVOICE.CUSTOMER_NAME)
        .from(SALES_INVOICE)
        .where(SALES_INVOICE.ID.eq(invoiceId))
        .fetchOne(SALES_INVOICE.CUSTOMER_NAME);
  }

  private String paymentStatus(UUID paymentId) {
    return dsl.select(PAYMENT.STATUS)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(paymentId))
        .fetchOne(PAYMENT.STATUS)
        .getLiteral();
  }

  private BigDecimal paymentUnallocated(UUID paymentId) {
    return dsl.select(PAYMENT.UNALLOCATED_AMOUNT)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(paymentId))
        .fetchOne(PAYMENT.UNALLOCATED_AMOUNT);
  }

  private BigDecimal allocatedFromPayment(UUID paymentId) {
    BigDecimal sum =
        dsl.select(DSL.sum(PAYMENT_ALLOCATION.AMOUNT))
            .from(PAYMENT_ALLOCATION)
            .where(PAYMENT_ALLOCATION.PAYMENT_ID.eq(paymentId))
            .fetchOne(DSL.sum(PAYMENT_ALLOCATION.AMOUNT));
    return sum == null ? BigDecimal.ZERO : sum;
  }

  private String orderStatus(UUID orderId) {
    return dsl.select(SALES_ORDER.STATUS)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.STATUS)
        .getLiteral();
  }

  private OffsetDateTime orderTimestamp(
      UUID orderId, org.jooq.TableField<?, OffsetDateTime> field) {
    return dsl.select(field).from(SALES_ORDER).where(SALES_ORDER.ID.eq(orderId)).fetchOne(field);
  }
}
