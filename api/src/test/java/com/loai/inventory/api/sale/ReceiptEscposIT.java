package com.loai.inventory.api.sale;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.service.CounterReturnService;
import com.loai.inventory.service.CounterReturnService.LineInput;
import com.loai.inventory.service.CounterReturnService.ReturnCommand;
import com.loai.inventory.service.CounterReturnService.Returned;
import com.loai.inventory.service.CreditNoteService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.InvoiceAdminService;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.OrgService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.InStoreSale;
import com.loai.inventory.service.SalesOrderService.OrderLineInput;
import com.loai.inventory.service.SalesOrderService.PaymentInput;
import com.loai.inventory.service.document.DocumentRenderService;
import com.loai.inventory.service.document.DocumentRenderService.RenderedDocument;
import com.loai.inventory.service.document.Escpos;
import com.loai.inventory.service.document.SlipModel;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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
 * The ESC/POS slips over real data ({@code stories/escpos_receipt.md}): a counter sale placed
 * through the real services renders a banded raster receipt; the counter return that follows
 * renders the return slip naming how the cash went back; an id nothing was sold under is a 404. The
 * {@code CounterReturnIT} harness, headless.
 */
@Testcontainers
class ReceiptEscposIT {

  static {
    System.setProperty("java.awt.headless", "true");
  }

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SalesOrderService service;
  static CounterReturnService counterReturnService;
  static DocumentRenderService render;

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
            new com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
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
            new InventoryReservationRepositoryFactoryImpl(),
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    com.loai.inventory.service.MagicLinkService magicLink =
        com.loai.inventory.api.support.TestWiring.magicLinkService(dsl);
    service =
        new SalesOrderService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            reservationService,
            fulfillmentService,
            paymentService,
            invoiceService,
            refundService,
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            magicLink,
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            new com.loai.inventory.service.CouponService(
                dsl, new com.loai.inventory.repository.CouponRepositoryFactoryImpl()),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
    CreditNoteService creditNoteService =
        new CreditNoteService(
            dsl,
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl());
    counterReturnService =
        new CounterReturnService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new InventoryRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl(),
            creditNoteService,
            refundService);
    OrgService orgService =
        new OrgService(
            dsl,
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.UserRepositoryFactoryImpl(),
            com.loai.inventory.api.support.TestWiring.storage());
    InvoiceAdminService invoiceAdminService =
        new InvoiceAdminService(
            dsl,
            new SalesInvoiceRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CustomerRepositoryFactoryImpl(),
            invoiceService);
    render =
        new DocumentRenderService(
            orgService, invoiceAdminService, creditNoteService, paymentService);
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
        "TRUNCATE refund_allocation, refund, credit_note_line, credit_note,"
            + " credit_note_number_counter, payment_allocation, sales_invoice_line, sales_invoice,"
            + " payment,"
            + " payment_transaction, fulfillment_line, fulfillment, inventory_reservation,"
            + " inventory_log, inventory, sales_order_line, sales_order, customer, product,"
            + " user_org_role, app_user, org, order_number_counter, invoice_number_counter"
            + " RESTART IDENTITY CASCADE");
  }

  /** A cash sale of an Arabic-titled product prints a banded 80 mm slip, change and all. */
  @Test
  void cashSale_rendersABandedReceiptSlip() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "دفتر", new BigDecimal("10.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 3, new BigDecimal("50.00"));

    RenderedDocument doc = render.renderReceiptEscpos(org, sale.order().getId(), Escpos.WIDTH_80MM);

    assertEquals(sale.order().getOrderNumber() + ".escpos", doc.filename());
    byte[] b = doc.bytes();
    assertArrayEquals(new byte[] {0x1B, 0x40}, Arrays.copyOfRange(b, 0, 2));
    assertArrayEquals(
        new byte[] {0x1B, 0x64, 0x04, 0x1D, 0x56, 0x42, 0x00},
        Arrays.copyOfRange(b, b.length - 7, b.length));
    assertEquals(72, b[6] & 0xFF, "first band: 576/8 bytes per row");
    assertEquals(0, b[7] & 0xFF);
    assertEquals(64, b[8] & 0xFF, "first band: 64 rows");
    assertTrue(b.length > 10_000, "a real slip is tens of KB, got " + b.length);

    SlipModel m = render.receiptModel(org, sale.order().getId());
    assertEquals("دفتر widget", m.lines().get(0).title());
    assertEquals("3 x 10.00", m.lines().get(0).qtyByPrice());
    assertEquals(sale.order().getOrderNumber(), m.barcode());
    assertEquals(sale.invoice().getInvoiceNumber(), m.meta().get(1).value());
    assertEquals(new SlipModel.Row("Tendered", "50.00 EGP"), m.tendered());
    assertEquals(new SlipModel.Row("Change", "20.00 EGP"), m.change());
    assertTrue(m.tenders().isEmpty(), "a single tender prints no per-provider rows");
  }

  /** The return slip: the note, the original order as barcode, and the cash that went back. */
  @Test
  void counterReturn_rendersAReturnSlipNamingTheRefund() {
    UUID org = createOrg("acme");
    UUID manager = createUser("manager@acme.test");
    UUID product = createProduct(org, "قلم", new BigDecimal("10.00"));
    createInventory(org, product, 10, 0);
    InStoreSale sale = sell(org, manager, product, 3, null);
    Returned ret =
        counterReturnService.returnFromReceipt(
            org,
            sale.order().getId(),
            new ReturnCommand(List.of(new LineInput(product, 1)), true, null),
            UUID.randomUUID().toString(),
            actor(manager),
            manager,
            true);
    assertNotNull(ret.refund());

    RenderedDocument doc =
        render.renderReturnSlipEscpos(org, ret.creditNote().getId(), Escpos.WIDTH_58MM);

    assertEquals(ret.creditNote().getCreditNoteNumber() + ".escpos", doc.filename());
    byte[] b = doc.bytes();
    assertArrayEquals(new byte[] {0x1B, 0x40}, Arrays.copyOfRange(b, 0, 2));
    assertEquals(48, b[6] & 0xFF, "58 mm: 384/8 bytes per row");

    SlipModel m = render.returnSlipModel(org, ret.creditNote().getId());
    assertEquals("RETURN", m.title());
    assertEquals(sale.order().getOrderNumber(), m.barcode());
    assertEquals(ret.creditNote().getCreditNoteNumber(), m.meta().get(0).value());
    assertEquals(sale.order().getOrderNumber(), m.meta().get(1).value());
    assertEquals("قلم widget", m.lines().get(0).title());
    assertEquals("1 x 10.00", m.lines().get(0).qtyByPrice());
    assertEquals(new SlipModel.Row("REFUND TOTAL", "10.00 EGP"), m.total());
    assertEquals(new SlipModel.Row("Refunded", "Cash handed back"), m.settlement());
    assertNull(m.tendered());
  }

  @Test
  void nothingSoldUnderTheId_is404_forBothSlips() {
    UUID org = createOrg("acme");
    assertThrows(
        NotFoundException.class, () -> render.renderReceiptEscpos(org, UUID.randomUUID(), 576));
    assertThrows(
        NotFoundException.class, () -> render.renderReturnSlipEscpos(org, UUID.randomUUID(), 576));
  }

  // helpers

  private InStoreSale sell(UUID org, UUID manager, UUID product, int qty, BigDecimal cash) {
    return service.placeInStoreSale(
        org,
        null,
        List.of(new OrderLineInput(product, qty)),
        List.of(new PaymentInput(PaymentProvider.CASH, null, cash)),
        null,
        null,
        actor(manager),
        UUID.randomUUID().toString(),
        manager,
        true);
  }

  private static ActorContext actor(UUID userId) {
    return ActorContext.user(userId.toString());
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

  private UUID createUser(String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "-" + email)
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .execute();
    return id;
  }

  private UUID createProduct(UUID org, String sku, BigDecimal basePrice) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, sku + " widget")
        .set(PRODUCT.SKU, sku + "-" + id)
        .set(PRODUCT.BASE_PRICE, basePrice)
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
}
