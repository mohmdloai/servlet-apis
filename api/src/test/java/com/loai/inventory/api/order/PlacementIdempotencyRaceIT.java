package com.loai.inventory.api.order;

import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.repository.SalesOrderRepository;
import com.loai.inventory.domain.repository.SalesOrderRepositoryFactory;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.service.CouponService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.Placed;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * D5 — two genuinely-concurrent submits carrying the same {@code Idempotency-Key}.
 *
 * <p>The short-circuit that makes a <em>sequential</em> retry a replay is a read, and reads do not
 * serialize: both submits see "absent" and the loser meets the {@code (org_id, idempotency_key)}
 * UNIQUE on insert. That used to escape as a 500 — the one outcome the header exists to prevent.
 * The race is forced deterministically here by holding both callers at a barrier <em>after</em>
 * their idempotency read (via a proxied repository), so this is not a timing-hope test.
 */
@Testcontainers
class PlacementIdempotencyRaceIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SalesOrderService salesOrders;

  /**
   * Opens once both placements have passed their idempotency read, and stays open — a latch rather
   * than a barrier, because the losing placement reads the key a second time (to resolve the
   * winner's order) and must not wait on anybody for that.
   */
  static volatile CountDownLatch bothRead;

  private static final ActorContext ACTOR = ActorContext.user(UUID.randomUUID().toString());

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

    ReservationService reservationService =
        new ReservationService(
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl());
    InvoiceService invoiceService =
        new InvoiceService(
            new SalesInvoiceRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl());
    RefundService refundService =
        new RefundService(
            dsl,
            new RefundRepositoryFactoryImpl(),
            new RefundAllocationRepositoryFactoryImpl(),
            new CreditNoteRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl());
    FulfillmentService fulfillmentService =
        new FulfillmentService(
            dsl,
            new FulfillmentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            invoiceService,
            refundService,
            reservationService,
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl));
    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));

    salesOrders =
        new SalesOrderService(
            dsl,
            barrieredOrderRepositories(),
            new OrgRepositoryFactoryImpl(),
            reservationService,
            fulfillmentService,
            paymentService,
            invoiceService,
            refundService,
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl),
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            new CouponService(dsl, new com.loai.inventory.repository.CouponRepositoryFactoryImpl()),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
  }

  /**
   * The real repository, wrapped so that {@code findByIdempotencyKey} waits on {@link #bothRead}
   * <em>after</em> answering. That is the whole race: both placements learn "no such order" before
   * either has inserted one. A dynamic proxy keeps this to one method — the interface is wide and
   * every other call must pass straight through.
   */
  private static SalesOrderRepositoryFactory barrieredOrderRepositories() {
    SalesOrderRepositoryFactoryImpl real = new SalesOrderRepositoryFactoryImpl();
    return ctx -> {
      SalesOrderRepository delegate = real.create(ctx);
      InvocationHandler handler =
          (proxy, method, args) -> {
            Object result;
            try {
              result = method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
              throw e.getCause();
            }
            CountDownLatch latch = bothRead;
            if (latch != null && "findByIdempotencyKey".equals(method.getName())) {
              latch.countDown();
              // Timeout, not an indefinite wait: if the race can't be staged the test should fail
              // on its assertions, not hang the suite.
              latch.await(20, TimeUnit.SECONDS);
            }
            return result;
          };
      return (SalesOrderRepository)
          Proxy.newProxyInstance(
              SalesOrderRepository.class.getClassLoader(),
              new Class<?>[] {SalesOrderRepository.class},
              handler);
    };
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void fresh() {
    bothRead = null;
    dsl.execute(
        "TRUNCATE payment_allocation, sales_invoice_line, sales_invoice, refund, payment,"
            + " payment_transaction, fulfillment_line, fulfillment, inventory_reservation,"
            + " inventory_log, inventory, notification, customer_magic_token, sales_order_line,"
            + " sales_order, product, customer, org, order_number_counter RESTART IDENTITY"
            + " CASCADE");
  }

  @Test
  void concurrentSubmitsWithOneKey_bothResolveToOneOrder_neverA500() throws Exception {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1", new BigDecimal("10.00"));
    createInventory(org, product, 50);
    String key = "checkout-" + UUID.randomUUID();

    bothRead = new CountDownLatch(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Placed> submit = () -> place(org, product, key);
      Future<Placed> a = pool.submit(submit);
      Future<Placed> b = pool.submit(submit);

      // Neither may fail: the loser's unique violation is converted into the same replay a
      // sequential retry gets.
      Placed first = a.get(60, TimeUnit.SECONDS);
      Placed second = b.get(60, TimeUnit.SECONDS);

      assertNotNull(first.order());
      assertNotNull(second.order());
      assertEquals(
          first.order().getId(),
          second.order().getId(),
          "both callers must resolve to the same order");
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1, orderCount(org), "exactly one order exists for the key");
    assertEquals(
        1,
        reservationRowCount(org),
        "the loser must not have reserved stock a second time for the replayed order");
  }

  @Test
  void sequentialRetryStillReplays() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1", new BigDecimal("10.00"));
    createInventory(org, product, 50);
    String key = "checkout-" + UUID.randomUUID();

    Placed first = place(org, product, key);
    Placed replay = place(org, product, key);

    assertEquals(first.order().getId(), replay.order().getId());
    assertEquals(1, orderCount(org));
  }

  /** A different key is a different order — the race fix must not collapse distinct submits. */
  @Test
  void distinctKeysStillPlaceDistinctOrders() {
    UUID org = createOrg("acme");
    UUID product = createProduct(org, "SKU1", new BigDecimal("10.00"));
    createInventory(org, product, 50);

    Placed one = place(org, product, "key-a");
    Placed two = place(org, product, "key-b");

    assertTrue(!one.order().getId().equals(two.order().getId()));
    assertEquals(2, orderCount(org));
  }

  // helpers

  private Placed place(UUID org, UUID product, String idempotencyKey) {
    return salesOrders.placeOnlineOrder(
        org,
        new CustomerInput("Nadia", "nadia@acme.test", null, "12 Nile St"),
        List.of(new OrderLineInput(product, 1)),
        idempotencyKey,
        null,
        ACTOR);
  }

  private int orderCount(UUID org) {
    return dsl.fetchCount(SALES_ORDER, SALES_ORDER.ORG_ID.eq(org));
  }

  private int reservationRowCount(UUID org) {
    return dsl.fetchCount(
        com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION,
        com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION.ORG_ID.eq(org));
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

  private UUID createProduct(UUID org, String sku, BigDecimal price) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku)
        .set(PRODUCT.SKU, sku)
        .set(PRODUCT.BASE_PRICE, price)
        .execute();
    return id;
  }

  private void createInventory(UUID org, UUID product, int stock) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, product)
        .set(INVENTORY.STOCK_QTY, stock)
        .set(INVENTORY.RESERVED_QTY, 0)
        .execute();
  }
}
