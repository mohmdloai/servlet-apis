package com.loai.inventory.api.fulfillment;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.Fulfillment;
import com.loai.inventory.domain.model.FulfillmentResolution;
import com.loai.inventory.domain.model.FulfillmentStatus;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.enums.PaymentStatus;
import com.loai.inventory.repository.generated.enums.PaymentVerificationStatus;
import com.loai.inventory.repository.generated.enums.ReservationStatus;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.FailedRefundResult;
import com.loai.inventory.service.FulfillmentService.FulfillmentPage;
import com.loai.inventory.service.FulfillmentService.FulfillmentView;
import com.loai.inventory.service.FulfillmentService.LineInput;
import com.loai.inventory.service.FulfillmentService.OrderFulfillments;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
 * Integration coverage for the fulfillment reads ({@code stories/fulfillment_reads.md}): the {@code
 * /{id}} detail (every lifecycle timestamp, disposition and lineage field), the status-filtered
 * queue / unfiltered ledger list with paging, and the by-order shipment story.
 *
 * <p>Drives {@link FulfillmentService} directly against the real jOOQ repositories — no Tomcat.
 * VIEWER authorization and query-param parsing (400s) are enforced in the handler ({@code
 * FulfillmentReadHandlerAuthTest}), as everywhere.
 */
@Testcontainers
class FulfillmentReadIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static FulfillmentService service;

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

    service =
        new FulfillmentService(
            dsl,
            new FulfillmentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new com.loai.inventory.repository.PaymentRepositoryFactoryImpl(),
            new com.loai.inventory.service.InvoiceService(
                new com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl(),
                new com.loai.inventory.repository.PaymentRepositoryFactoryImpl(),
                new com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl()),
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
        "TRUNCATE payment_allocation, sales_invoice_line, sales_invoice, payment, fulfillment_line,"
            + " fulfillment, inventory_reservation, inventory_log, inventory, sales_order_line,"
            + " sales_order, product, org, invoice_number_counter RESTART IDENTITY CASCADE");
  }

  // detail

  @Test
  void get_returnsFulfillmentWithLines() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 3);
    Order order = seedPaidOrder(org, List.of(new Want(product, 3)));
    Line line = order.lines().get(0);
    UUID id =
        service
            .create(
                org,
                order.id(),
                List.of(new LineInput(line.lineId())),
                "Bosta",
                "TRK-1",
                "fragile",
                actor)
            .fulfillment()
            .getId();

    FulfillmentView view = service.get(org, id);

    Fulfillment f = view.fulfillment();
    assertEquals(FulfillmentStatus.PENDING, f.getStatus());
    assertEquals(order.id(), f.getSalesOrderId());
    assertEquals("Bosta", f.getCarrier());
    assertEquals("TRK-1", f.getTrackingNumber());
    assertNotNull(f.getCreatedAt());
    assertEquals(1, view.lines().size());
    assertEquals(3, view.lines().get(0).getQuantity());
    assertEquals(line.lineId(), view.lines().get(0).getSalesOrderLineId());
  }

  /** DELIVERED is readable back with its {@code delivered_at} — the field story 04 flagged. */
  @Test
  void get_deliveredFulfillment_carriesDeliveredAt() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 2);
    Order order = seedPaidOrder(org, List.of(new Want(product, 2)));
    UUID id = createFulfillment(org, order, 0);
    service.ship(org, id, actor);
    service.markDelivered(org, id, actor);

    Fulfillment f = service.get(org, id).fulfillment();

    assertEquals(FulfillmentStatus.DELIVERED, f.getStatus());
    assertNotNull(f.getShippedAt());
    assertNotNull(f.getDeliveredAt());
  }

  /**
   * A FAILED fulfillment's full disposition is readable back: {@code failed_at} + reason, the
   * orthogonal {@code returned_at} stamp, {@code resolution=REPLACED}, and the replacement's {@code
   * replaces_fulfillment_id} lineage — none of which the mutation responses used to expose.
   */
  @Test
  void get_failedResolvedFulfillment_exposesDispositionAndLineage() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 2);
    Order order = seedPaidOrder(org, List.of(new Want(product, 2)));
    UUID failedId = createFulfillment(org, order, 0);
    service.ship(org, failedId, actor);
    service.markFailed(org, failedId, "package lost");
    service.recordReturn(org, failedId, actor);
    UUID replacementId =
        service.replaceFailed(org, failedId, "Aramex", "TRK-2", null, actor).fulfillment().getId();

    Fulfillment failed = service.get(org, failedId).fulfillment();
    assertEquals(FulfillmentStatus.FAILED, failed.getStatus());
    assertNotNull(failed.getFailedAt());
    assertEquals("package lost", failed.getFailedReason());
    assertNotNull(failed.getReturnedAt());
    assertEquals(FulfillmentResolution.REPLACED, failed.getResolution());
    assertNull(failed.getReplacesFulfillmentId());

    Fulfillment replacement = service.get(org, replacementId).fulfillment();
    assertEquals(FulfillmentStatus.PENDING, replacement.getStatus());
    assertEquals(failedId, replacement.getReplacesFulfillmentId());
    assertEquals(1, service.get(org, replacementId).lines().size());
  }

  /**
   * The detail read carries the parent order's human-readable number and the fulfillment's monetary
   * value — the invoice arithmetic (subtotal + tax, {@code InvoiceService.grandTotalOf}), i.e. the
   * exact figure {@code refundFailed} sizes its refund (and the OWNER-threshold check) by, not a
   * naive sum of order-line totals.
   */
  @Test
  void get_carriesOrderNumberAndGuardValue() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 3);
    Order order = seedPaidOrder(org, List.of(new Want(product, 3)));
    dsl.update(SALES_ORDER_LINE)
        .set(SALES_ORDER_LINE.TAX_RATE, new BigDecimal("0.1000"))
        .where(SALES_ORDER_LINE.ID.eq(order.lines().get(0).lineId()))
        .execute();
    UUID id = createFulfillment(org, order, 0);

    FulfillmentView view = service.get(org, id);

    assertEquals(order.number(), view.salesOrderNumber());
    // 3 × 10.00 = 30.00 subtotal + 10% tax = 33.00 — what a refund of this fulfillment would move.
    assertEquals(new BigDecimal("33.00"), view.fulfillmentValue());
  }

  /**
   * Guard parity end-to-end: the value advertised on the read equals the PENDING refund total
   * {@code refundFailed} actually creates — a client-side threshold pre-warning built on it can
   * never disagree with the server's own guard.
   */
  @Test
  void get_advertisedValue_equalsWhatRefundFailedMoves() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 2);
    Order order = seedPaidOrder(org, List.of(new Want(product, 2)));
    seedReceivedPayment(org, order.id(), "20.00");
    UUID id = createFulfillment(org, order, 0);
    service.ship(org, id, actor);
    service.markFailed(org, id, "package lost");

    BigDecimal advertised = service.get(org, id).fulfillmentValue();
    FailedRefundResult result = service.refundFailed(org, id, null, UUID.randomUUID(), true);

    assertEquals(new BigDecimal("20.00"), advertised);
    assertEquals(advertised, result.pendingRefundTotal());
  }

  @Test
  void get_unknownOrForeignId_is404() {
    UUID org = createOrg("acme");
    UUID otherOrg = createOrg("globex");
    UUID product = createProduct(otherOrg, "SKU1");
    createInventory(otherOrg, product, 10, 2);
    Order order = seedPaidOrder(otherOrg, List.of(new Want(product, 2)));
    UUID foreign = createFulfillment(otherOrg, order, 0);

    assertThrows(NotFoundException.class, () -> service.get(org, UUID.randomUUID()));
    // Another org's fulfillment is invisible, not forbidden — scoping over the shared schema.
    assertThrows(NotFoundException.class, () -> service.get(org, foreign));
  }

  // list: queue and ledger

  @Test
  void statusFilter_returnsOnlyThatStatus_oldestFirst() {
    UUID org = createOrg("acme");
    UUID pending1 = seedFulfillment(org, "A");
    UUID shipped = seedFulfillment(org, "B");
    service.ship(org, shipped, actor);
    UUID pending2 = seedFulfillment(org, "C");

    FulfillmentPage page = service.list(org, FulfillmentStatus.PENDING, 0, 20);

    assertEquals(2, page.total());
    assertEquals(List.of(pending1, pending2), ids(page), "queue view must be created_at ASC");
    assertEquals(List.of(shipped), ids(service.list(org, FulfillmentStatus.SHIPPED, 0, 20)));
    assertEquals(0, service.list(org, FulfillmentStatus.FAILED, 0, 20).total());
  }

  @Test
  void unfilteredLedger_returnsEverything_newestFirst_withLines() {
    UUID org = createOrg("acme");
    UUID f1 = seedFulfillment(org, "A");
    UUID f2 = seedFulfillment(org, "B");
    service.ship(org, f2, actor);
    UUID f3 = seedFulfillment(org, "C");

    FulfillmentPage page = service.list(org, null, 0, 20);

    assertEquals(3, page.total());
    assertEquals(List.of(f3, f2, f1), ids(page), "ledger must be created_at DESC");
    // Lines are batch-loaded for the whole page — every row carries its own.
    for (FulfillmentView v : page.items()) {
      assertEquals(1, v.lines().size());
      assertEquals(v.fulfillment().getId(), v.lines().get(0).getFulfillmentId());
    }
  }

  /** Each queue row names its own parent order — the card is readable without a second fetch. */
  @Test
  void list_rowsCarryTheirOwnOrderNumber_valueOmitted() {
    UUID org = createOrg("acme");
    UUID a = createProduct(org, "A");
    UUID b = createProduct(org, "B");
    createInventory(org, a, 10, 2);
    createInventory(org, b, 10, 2);
    Order order1 = seedPaidOrder(org, List.of(new Want(a, 2)));
    Order order2 = seedPaidOrder(org, List.of(new Want(b, 2)));
    UUID f1 = createFulfillment(org, order1, 0);
    UUID f2 = createFulfillment(org, order2, 0);

    FulfillmentPage page = service.list(org, FulfillmentStatus.PENDING, 0, 20);

    assertEquals(List.of(f1, f2), ids(page));
    assertEquals(order1.number(), page.items().get(0).salesOrderNumber());
    assertEquals(order2.number(), page.items().get(1).salesOrderNumber());
    // Queue rows carry no monetary value — pricing a page would need every parent order's lines;
    // the detail and by-order reads (where the refund flow lives) carry it.
    assertNull(page.items().get(0).fulfillmentValue());
  }

  @Test
  void pagination_tilesTheFilteredSetWithoutOverlap() {
    UUID org = createOrg("acme");
    List<UUID> seeded = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      seeded.add(seedFulfillment(org, "P" + i));
    }

    FulfillmentPage p0 = service.list(org, FulfillmentStatus.PENDING, 0, 2);
    FulfillmentPage p1 = service.list(org, FulfillmentStatus.PENDING, 1, 2);
    FulfillmentPage p2 = service.list(org, FulfillmentStatus.PENDING, 2, 2);

    assertEquals(5, p0.total());
    assertEquals(2, p0.items().size());
    assertEquals(2, p1.items().size());
    assertEquals(1, p2.items().size());
    List<UUID> all = new ArrayList<>(ids(p0));
    all.addAll(ids(p1));
    all.addAll(ids(p2));
    assertEquals(seeded, all, "pages must tile the ASC queue exactly");
  }

  @Test
  void pageAndSize_areClampedNotErrors() {
    UUID org = createOrg("acme");
    seedFulfillment(org, "A");
    seedFulfillment(org, "B");

    // size floors at 1, negative page floors at 0 — a sloppy client gets data, not a 500.
    FulfillmentPage page = service.list(org, null, -3, 0);

    assertEquals(1, page.items().size());
    assertEquals(2, page.total());
  }

  @Test
  void listing_isOrgScoped() {
    UUID org = createOrg("acme");
    UUID otherOrg = createOrg("globex");
    UUID mine = seedFulfillment(org, "A");
    UUID foreign = seedFulfillment(otherOrg, "B");

    List<UUID> listed = ids(service.list(org, null, 0, 20));

    assertEquals(List.of(mine), listed);
    assertTrue(ids(service.list(otherOrg, null, 0, 20)).contains(foreign));
  }

  // by-order: the shipment story

  @Test
  void listForOrder_everyStatusOldestFirst_plusOrderHeader() {
    UUID org = createOrg("acme");
    UUID a = createProduct(org, "A");
    UUID b = createProduct(org, "B");
    createInventory(org, a, 10, 2);
    createInventory(org, b, 10, 3);
    Order order = seedPaidOrder(org, List.of(new Want(a, 2), new Want(b, 3)));
    UUID cancelled = createFulfillment(org, order, 0);
    service.cancelPending(org, cancelled, actor);
    UUID failed = createFulfillment(org, order, 1);
    service.ship(org, failed, actor);
    service.markFailed(org, failed, "refused");
    service.recordReturn(org, failed, actor);
    UUID replacement =
        service.replaceFailed(org, failed, null, null, null, actor).fulfillment().getId();

    OrderFulfillments story = service.listForOrder(org, order.id());

    // The header renders the panel standalone; first ship flipped the order to FULFILLING.
    assertEquals(order.id(), story.order().getId());
    assertEquals("FULFILLING", story.order().getStatus().name());

    // Every fulfillment regardless of status, oldest first, each with its lines.
    assertEquals(List.of(cancelled, failed, replacement), viewIds(story.fulfillments()));
    assertEquals(
        FulfillmentStatus.CANCELLED, story.fulfillments().get(0).fulfillment().getStatus());
    assertEquals(FulfillmentStatus.FAILED, story.fulfillments().get(1).fulfillment().getStatus());
    assertEquals(FulfillmentStatus.PENDING, story.fulfillments().get(2).fulfillment().getStatus());
    assertEquals(failed, story.fulfillments().get(2).fulfillment().getReplacesFulfillmentId());
    for (FulfillmentView v : story.fulfillments()) {
      assertEquals(1, v.lines().size());
      assertEquals(order.number(), v.salesOrderNumber());
    }
    // Each fulfillment is valued at its own lines' invoice arithmetic: line A (2 × 10.00), the
    // failed line B (3 × 10.00) and its replacement (same line, same value).
    assertEquals(new BigDecimal("20.00"), story.fulfillments().get(0).fulfillmentValue());
    assertEquals(new BigDecimal("30.00"), story.fulfillments().get(1).fulfillmentValue());
    assertEquals(new BigDecimal("30.00"), story.fulfillments().get(2).fulfillmentValue());
  }

  @Test
  void listForOrder_orderWithNoFulfillments_isEmptyDataNotError() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1");
    createInventory(org, product, 10, 2);
    Order order = seedPaidOrder(org, List.of(new Want(product, 2)));

    OrderFulfillments story = service.listForOrder(org, order.id());

    assertEquals(order.id(), story.order().getId());
    assertTrue(story.fulfillments().isEmpty());
  }

  @Test
  void listForOrder_unknownOrForeignOrder_is404() {
    UUID org = createOrg("acme");
    UUID otherOrg = createOrg("globex");
    UUID product = createProduct(otherOrg, "SKU1");
    createInventory(otherOrg, product, 10, 2);
    Order foreign = seedPaidOrder(otherOrg, List.of(new Want(product, 2)));

    assertThrows(NotFoundException.class, () -> service.listForOrder(org, UUID.randomUUID()));
    assertThrows(NotFoundException.class, () -> service.listForOrder(org, foreign.id()));
  }

  // helpers

  private static List<UUID> ids(FulfillmentPage page) {
    return viewIds(page.items());
  }

  private static List<UUID> viewIds(List<FulfillmentView> views) {
    return views.stream().map(v -> v.fulfillment().getId()).toList();
  }

  /** Create a PENDING fulfillment for {@code order}'s line at {@code lineIndex}. */
  private UUID createFulfillment(UUID org, Order order, int lineIndex) {
    return service
        .create(
            org,
            order.id(),
            List.of(new LineInput(order.lines().get(lineIndex).lineId())),
            null,
            null,
            null,
            actor)
        .fulfillment()
        .getId();
  }

  /** Seed product + inventory + one-line paid order, then create its PENDING fulfillment. */
  private UUID seedFulfillment(UUID org, String sku) {
    UUID product = createProduct(org, sku);
    createInventory(org, product, 10, 2);
    Order order = seedPaidOrder(org, List.of(new Want(product, 2)));
    return createFulfillment(org, order, 0);
  }

  private record Want(UUID productId, int qty) {}

  private record Line(UUID lineId, UUID productId, int qty, UUID reservationId) {}

  private record Order(UUID id, String number, List<Line> lines) {}

  private UUID createOrg(String slug) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug + "-" + id)
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

  /** A VERIFIED transaction + RECEIVED, fully-unallocated payment — refundFailed's funding pool. */
  private void seedReceivedPayment(UUID org, UUID orderId, String amount) {
    OffsetDateTime earlier = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
    UUID txnId = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, txnId)
        .set(PAYMENT_TRANSACTION.ORG_ID, org)
        .set(
            PAYMENT_TRANSACTION.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "IPN-" + seq.getAndIncrement())
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, earlier)
        .execute();
    dsl.insertInto(PAYMENT)
        .set(PAYMENT.ID, UUID.randomUUID())
        .set(PAYMENT.ORG_ID, org)
        .set(PAYMENT.SALES_ORDER_ID, orderId)
        .set(PAYMENT.PAYMENT_TRANSACTION_ID, txnId)
        .set(PAYMENT.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.UNALLOCATED_AMOUNT, new BigDecimal(amount))
        .set(PAYMENT.STATUS, PaymentStatus.RECEIVED)
        .set(PAYMENT.RECEIVED_AT, earlier)
        .execute();
  }

  private Order seedPaidOrder(UUID org, List<Want> wants) {
    UUID orderId = UUID.randomUUID();
    String number = "SO-2026-" + String.format("%05d", seq.getAndIncrement());
    BigDecimal grand = BigDecimal.ZERO;
    for (Want w : wants) {
      grand = grand.add(new BigDecimal("10.00").multiply(BigDecimal.valueOf(w.qty())));
    }
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, org)
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
    return new Order(orderId, number, lines);
  }
}
