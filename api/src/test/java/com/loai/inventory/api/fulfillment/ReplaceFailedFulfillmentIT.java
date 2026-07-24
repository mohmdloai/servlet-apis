package com.loai.inventory.api.fulfillment;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.FULFILLMENT;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.InsufficientStockException;
import com.loai.inventory.domain.model.ActorContext;
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
import com.loai.inventory.service.FulfillmentService.DeliveredView;
import com.loai.inventory.service.FulfillmentService.FulfillmentView;
import com.loai.inventory.service.FulfillmentService.LineInput;
import com.loai.inventory.service.RefundService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Integration coverage for the replace-a-failed-fulfillment slice
 * ([`replace_failed_fulfillment.md`](../../../../../../../stories/replace_failed_fulfillment.md)).
 *
 * <p>A FAILED fulfillment is re-shipped instead of refunded: a new PENDING fulfillment is created
 * for the same lines, re-reserved from current stock, and at its delivery the invoice is funded by
 * the prepayment that was never refunded. Refund and replace are mutually exclusive.
 */
@Testcontainers
class ReplaceFailedFulfillmentIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static FulfillmentService service;
  static RefundService refundService;
  static UUID actorId;

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

    refundService =
        new RefundService(
            dsl,
            new com.loai.inventory.repository.RefundRepositoryFactoryImpl(),
            new com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new com.loai.inventory.repository.PaymentRepositoryFactoryImpl(),
            new com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl(),
            new com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl());
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
            refundService,
            new com.loai.inventory.service.ReservationService(
                new InventoryRepositoryFactoryImpl(),
                new InventoryReservationRepositoryFactoryImpl(),
                new InventoryLogRepositoryFactoryImpl()),
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl));

    actorId = UUID.randomUUID();
    dsl.insertInto(com.loai.inventory.repository.generated.Tables.APP_USER)
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.ID, actorId)
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.EMAIL, "rf-admin@test")
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.PASSWORD_HASH, "x")
        .set(
            com.loai.inventory.repository.generated.Tables.APP_USER.ACTOR_TYPE,
            com.loai.inventory.repository.generated.enums.ActorType.USER)
        .execute();
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
        "TRUNCATE refund_allocation, refund, credit_note, payment_allocation, sales_invoice_line,"
            + " sales_invoice, payment, payment_transaction, fulfillment, fulfillment_line,"
            + " inventory_reservation, inventory_log, inventory, sales_order_line, sales_order,"
            + " customer, product, org, invoice_number_counter RESTART IDENTITY CASCADE");
  }

  // happy paths

  @Test
  void replace_failedFulfillment_createsLinkedPendingFulfillment_withFreshReservation() {
    Shipped s = shipOne("30.00", 3, 10);
    service.markFailed(s.org, s.fulfillmentId, "lost in transit");

    FulfillmentView replacement =
        service.replaceFailed(s.org, s.fulfillmentId, "Bosta", "TRK-2", null, actor);

    // New PENDING fulfillment, linked back to the failed one (AC 1).
    assertEquals("PENDING", replacement.fulfillment().getStatus().name());
    assertEquals(s.fulfillmentId, replacement.fulfillment().getReplacesFulfillmentId());
    assertEquals("REPLACED", resolution(s.fulfillmentId));
    // Exactly one ACTIVE reservation for the line again (the original is CONSUMED).
    assertEquals(1, activeReservationCount(s.lineId));
    // Re-reservation raises reserved without touching stock (still at its post-ship value).
    assertEquals(7, stockQty(s.org, s.product));
    assertEquals(3, reservedQty(s.org, s.product));
  }

  @Test
  void replacement_shipsAndDelivers_fundedBySurvivingPrepayment() {
    Shipped s = shipOne("30.00", 3, 10);
    service.markFailed(s.org, s.fulfillmentId, "refused");
    UUID replacementId =
        service
            .replaceFailed(s.org, s.fulfillmentId, null, null, null, actor)
            .fulfillment()
            .getId();

    service.ship(s.org, replacementId, actor);
    DeliveredView delivered = service.markDelivered(s.org, replacementId, actor);

    // The replacement's invoice is issued for the lines' value and PAID from the never-refunded
    // prepayment; the order closes (AC 2).
    assertEquals(0, new BigDecimal("30.00").compareTo(delivered.invoice().getGrandTotal()));
    assertEquals("PAID", delivered.invoice().getStatus().name());
    assertEquals(0, BigDecimal.ZERO.compareTo(paymentUnallocated(s.paymentId)));
    assertEquals("CLOSED", orderStatus(s.orderId));
  }

  @Test
  void replace_oneOfTwoFulfillments_leavesSiblingFundingIntact() {
    TwoLines t = seedTwoLineOrder(); // A = 3×10 = 30, B = 4×10 = 40, prepaid 70
    UUID fA = createFulfillment(t.org, t.orderId, t.lineA);
    UUID fB = createFulfillment(t.org, t.orderId, t.lineB);
    service.ship(t.org, fA, actor);
    service.ship(t.org, fB, actor);
    service.markFailed(t.org, fA, "lost");

    // Replace A, then ship + deliver the replacement: its invoice (30) is funded, leaving B's 40.
    UUID replacementA =
        service.replaceFailed(t.org, fA, null, null, null, actor).fulfillment().getId();
    service.ship(t.org, replacementA, actor);
    service.markDelivered(t.org, replacementA, actor);
    assertEquals(
        0,
        new BigDecimal("40.00").compareTo(paymentUnallocated(t.paymentId)),
        "sibling B's prepayment must remain intact after replacing A");

    // B still delivers and is paid from the surviving 40 (AC 3).
    DeliveredView deliveredB = service.markDelivered(t.org, fB, actor);
    assertEquals(0, new BigDecimal("40.00").compareTo(deliveredB.invoice().getGrandTotal()));
    assertEquals("PAID", deliveredB.invoice().getStatus().name());
    assertEquals("CLOSED", orderStatus(t.orderId));
  }

  // adversarial

  @Test
  void replace_withoutStock_isRejected_andRollsBack() {
    // Stock exactly covers the first ship; nothing returns, so re-reservation finds 0 available.
    Shipped s = shipOne("30.00", 3, 3);
    service.markFailed(s.org, s.fulfillmentId, "lost");
    assertEquals(0, stockQty(s.org, s.product)); // goods gone

    assertThrows(
        InsufficientStockException.class,
        () -> service.replaceFailed(s.org, s.fulfillmentId, null, null, null, actor));

    // Nothing was written: no replacement fulfillment, no new reservation, failure unresolved.
    assertEquals(1, dsl.fetchCount(FULFILLMENT));
    assertEquals(0, activeReservationCount(s.lineId));
    assertNull(resolution(s.fulfillmentId));
  }

  @Test
  void replace_whenOrderLeftFulfillable_isConflictNotValidation() {
    // The order moved out of PAID/FULFILLING between fail and replace (e.g. cancelled underneath).
    // That's a resource-state conflict (409 ConflictException), not a malformed request (400).
    Shipped s = shipOne("30.00", 3, 10);
    service.markFailed(s.org, s.fulfillmentId, "lost");
    dsl.update(SALES_ORDER)
        .set(SALES_ORDER.STATUS, OrderStatus.CANCELLED)
        .where(SALES_ORDER.ID.eq(s.orderId))
        .execute();

    assertThrows(
        ConflictException.class,
        () -> service.replaceFailed(s.org, s.fulfillmentId, null, null, null, actor));

    // Nothing written: no reservation, failure still unresolved.
    assertEquals(0, activeReservationCount(s.lineId));
    assertNull(resolution(s.fulfillmentId));
  }

  @Test
  void refundThenReplace_isRejected() {
    Shipped s = shipOne("30.00", 3, 10);
    service.markFailed(s.org, s.fulfillmentId, "lost");
    service.refundFailed(s.org, s.fulfillmentId, null, actorId, false); // resolution = REFUNDED

    assertThrows(
        ConflictException.class,
        () -> service.replaceFailed(s.org, s.fulfillmentId, null, null, null, actor));
  }

  @Test
  void replaceThenRefund_isRejected() {
    Shipped s = shipOne("30.00", 3, 10);
    service.markFailed(s.org, s.fulfillmentId, "lost");
    service.replaceFailed(s.org, s.fulfillmentId, null, null, null, actor); // resolution = REPLACED

    assertThrows(
        ConflictException.class,
        () -> service.refundFailed(s.org, s.fulfillmentId, null, actorId, false));
  }

  @Test
  void replace_twice_isRejected() {
    Shipped s = shipOne("30.00", 3, 10);
    service.markFailed(s.org, s.fulfillmentId, "lost");
    service.replaceFailed(s.org, s.fulfillmentId, null, null, null, actor);

    assertThrows(
        ConflictException.class,
        () -> service.replaceFailed(s.org, s.fulfillmentId, null, null, null, actor));
  }

  @Test
  void replace_nonFailedFulfillment_isRejected() {
    Shipped s = shipOne("30.00", 3, 10); // SHIPPED, not FAILED
    assertThrows(
        ConflictException.class,
        () -> service.replaceFailed(s.org, s.fulfillmentId, null, null, null, actor));
  }

  @Test
  void replace_pendingFulfillment_isRejected() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org);
    UUID product = createProduct(org, "SKU");
    createInventory(org, product, 10, 3);
    UUID orderId = UUID.randomUUID();
    seedOrder(orderId, org, customer, new BigDecimal("30.00"));
    UUID lineId = seedLine(orderId, org, product, 3);
    seedPayment(org, customer, orderId, "30.00");
    UUID pending = createFulfillment(org, orderId, lineId); // PENDING, never shipped
    assertThrows(
        ConflictException.class,
        () -> service.replaceFailed(org, pending, null, null, null, actor));
  }

  @Test
  void replace_deliveredFulfillment_isRejected() {
    Shipped s = shipOne("30.00", 3, 10);
    service.markDelivered(s.org, s.fulfillmentId, actor); // SHIPPED → DELIVERED (terminal)
    assertThrows(
        ConflictException.class,
        () -> service.replaceFailed(s.org, s.fulfillmentId, null, null, null, actor));
  }

  @Test
  void return_afterReplace_stillRestocks_orthogonalToResolution() {
    Shipped s = shipOne("30.00", 3, 10); // ship F1: stock 10 → 7
    service.markFailed(s.org, s.fulfillmentId, "lost");
    service.replaceFailed(
        s.org, s.fulfillmentId, null, null, null, actor); // re-reserve: reserved → 3

    // Recording the failed goods' physical return is NOT gated on resolution=REPLACED (AC 8).
    service.recordReturn(s.org, s.fulfillmentId, actor); // +3 back to on_hand
    assertEquals(10, stockQty(s.org, s.product));
    assertEquals(3, reservedQty(s.org, s.product)); // the replacement's reservation is intact
  }

  // fixtures

  private record Shipped(
      UUID org, UUID product, UUID orderId, UUID paymentId, UUID lineId, UUID fulfillmentId) {}

  private Shipped shipOne(String grandTotal, int qty, int stockOnHand) {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org);
    UUID product = createProduct(org, "SKU");
    createInventory(org, product, stockOnHand, qty);
    UUID orderId = UUID.randomUUID();
    seedOrder(orderId, org, customer, new BigDecimal(grandTotal));
    UUID lineId = seedLine(orderId, org, product, qty);
    UUID paymentId = seedPayment(org, customer, orderId, grandTotal);
    UUID f = createFulfillment(org, orderId, lineId);
    service.ship(org, f, actor);
    return new Shipped(org, product, orderId, paymentId, lineId, f);
  }

  private record TwoLines(UUID org, UUID orderId, UUID lineA, UUID lineB, UUID paymentId) {}

  private TwoLines seedTwoLineOrder() {
    UUID org = createOrg("acme");
    UUID customer = createCustomer(org);
    UUID prodA = createProduct(org, "A");
    UUID prodB = createProduct(org, "B");
    createInventory(org, prodA, 10, 3);
    createInventory(org, prodB, 10, 4);
    UUID orderId = UUID.randomUUID();
    seedOrder(orderId, org, customer, new BigDecimal("70.00"));
    UUID lineA = seedLine(orderId, org, prodA, 3);
    UUID lineB = seedLine(orderId, org, prodB, 4);
    UUID paymentId = seedPayment(org, customer, orderId, "70.00");
    return new TwoLines(org, orderId, lineA, lineB, paymentId);
  }

  private UUID createFulfillment(UUID org, UUID orderId, UUID lineId) {
    return service
        .create(org, orderId, List.of(new LineInput(lineId)), null, null, null, actor)
        .fulfillment()
        .getId();
  }

  private void seedOrder(UUID orderId, UUID org, UUID customer, BigDecimal grand) {
    String number = "SO-" + now().getYear() + "-" + String.format("%05d", seq.getAndIncrement());
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
  }

  private UUID seedLine(UUID orderId, UUID org, UUID product, int qty) {
    UUID lineId = UUID.randomUUID();
    BigDecimal lineTotal = new BigDecimal("10.00").multiply(BigDecimal.valueOf(qty));
    dsl.insertInto(SALES_ORDER_LINE)
        .set(SALES_ORDER_LINE.ID, lineId)
        .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
        .set(SALES_ORDER_LINE.PRODUCT_ID, product)
        .set(SALES_ORDER_LINE.DESCRIPTION, "line")
        .set(SALES_ORDER_LINE.QUANTITY, qty)
        .set(SALES_ORDER_LINE.UNIT_PRICE, new BigDecimal("10.00"))
        .set(SALES_ORDER_LINE.LINE_SUBTOTAL, lineTotal)
        .set(SALES_ORDER_LINE.LINE_TOTAL, lineTotal)
        .execute();
    dsl.insertInto(INVENTORY_RESERVATION)
        .set(INVENTORY_RESERVATION.ID, UUID.randomUUID())
        .set(INVENTORY_RESERVATION.ORG_ID, org)
        .set(INVENTORY_RESERVATION.PRODUCT_ID, product)
        .set(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID, lineId)
        .set(INVENTORY_RESERVATION.QUANTITY, qty)
        .set(INVENTORY_RESERVATION.STATUS, ReservationStatus.ACTIVE)
        .execute();
    return lineId;
  }

  private UUID seedPayment(UUID org, UUID customer, UUID orderId, String amount) {
    UUID txnId = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, txnId)
        .set(
            PAYMENT_TRANSACTION.PROVIDER,
            com.loai.inventory.repository.generated.enums.PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.ORG_ID, org)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "IPN-" + seq.getAndIncrement())
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal(amount))
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, now().minusHours(2))
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
        .set(PAYMENT.STATUS, PaymentStatus.RECEIVED)
        .set(PAYMENT.RECEIVED_AT, now().minusHours(2))
        .execute();
    return paymentId;
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

  private UUID createCustomer(UUID org) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, org)
        .set(CUSTOMER.NAME, "Nadia")
        .set(CUSTOMER.EMAIL, id + "@acme.test")
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

  // query helpers

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private String resolution(UUID fulfillmentId) {
    return dsl.select(FULFILLMENT.RESOLUTION)
        .from(FULFILLMENT)
        .where(FULFILLMENT.ID.eq(fulfillmentId))
        .fetchOne(FULFILLMENT.RESOLUTION);
  }

  private int activeReservationCount(UUID lineId) {
    return dsl.fetchCount(
        INVENTORY_RESERVATION,
        INVENTORY_RESERVATION
            .SALES_ORDER_LINE_ID
            .eq(lineId)
            .and(INVENTORY_RESERVATION.STATUS.eq(ReservationStatus.ACTIVE)));
  }

  private int stockQty(UUID org, UUID product) {
    return dsl.select(INVENTORY.STOCK_QTY)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(org).and(INVENTORY.PRODUCT_ID.eq(product)))
        .fetchOne(INVENTORY.STOCK_QTY);
  }

  private int reservedQty(UUID org, UUID product) {
    return dsl.select(INVENTORY.RESERVED_QTY)
        .from(INVENTORY)
        .where(INVENTORY.ORG_ID.eq(org).and(INVENTORY.PRODUCT_ID.eq(product)))
        .fetchOne(INVENTORY.RESERVED_QTY);
  }

  private String orderStatus(UUID orderId) {
    return dsl.select(SALES_ORDER.STATUS)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.STATUS)
        .getLiteral();
  }

  private BigDecimal paymentUnallocated(UUID id) {
    return dsl.select(PAYMENT.UNALLOCATED_AMOUNT)
        .from(PAYMENT)
        .where(PAYMENT.ID.eq(id))
        .fetchOne(PAYMENT.UNALLOCATED_AMOUNT);
  }
}
