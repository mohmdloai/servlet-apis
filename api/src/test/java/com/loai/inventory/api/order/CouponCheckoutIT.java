package com.loai.inventory.api.order;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.COUPON;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorContext;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.SalesInvoice;
import com.loai.inventory.repository.CouponRepositoryFactoryImpl;
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
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.service.CouponService;
import com.loai.inventory.service.FulfillmentService;
import com.loai.inventory.service.FulfillmentService.LineInput;
import com.loai.inventory.service.InvoiceService;
import com.loai.inventory.service.MagicLinkService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.SalesOrderService;
import com.loai.inventory.service.SalesOrderService.CustomerInput;
import com.loai.inventory.service.SalesOrderService.StorefrontLineInput;
import com.loai.inventory.service.SalesOrderService.StorefrontPlaced;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Honest coupons at checkout — roadmap item 9, {@code stories/honest_coupons.md} (V72). The money
 * core: percent + fixed happy paths on both placement planes, the <b>invoice-proration identity</b>
 * across a partial delivery (exact piastres, and Σ live invoice grands == order grand), the
 * eligibility wall and its deliberately uniform message, the redemption-cap race under {@code FOR
 * UPDATE} plus the slot freeing on cancel, the advisory-preview property, and the no-coupon
 * regression rider.
 *
 * <p>Drives the real placement / payment / delivery services — no Tomcat — modelled on {@code
 * TaxShippingConfigIT}, which is the other suite that asserts money to the piastre.
 */
@Testcontainers
class CouponCheckoutIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static SalesOrderService salesOrderService;
  static CouponService couponService;
  static PaymentTransactionService txnService;
  static FulfillmentService fulfillmentService;

  private final AtomicInteger refSeq = new AtomicInteger(1);
  private final ActorContext actor = ActorContext.system("storefront");

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

    MagicLinkService magicLink = TestWiring.magicLinkService(dsl);
    var notificationService = TestWiring.notificationService(dsl);
    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            notificationService,
            magicLink,
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
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
    txnService =
        new PaymentTransactionService(
            dsl,
            new PaymentTransactionRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            paymentService,
            refundService,
            TestWiring.storage());
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
    fulfillmentService =
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
            notificationService,
            magicLink);
    couponService = new CouponService(dsl, new CouponRepositoryFactoryImpl());
    salesOrderService =
        new SalesOrderService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            reservationService,
            fulfillmentService,
            paymentService,
            invoiceService,
            refundService,
            notificationService,
            magicLink,
            TestWiring.permissiveEmailGate(),
            couponService,
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
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
        "TRUNCATE notification_preference, notification_delivery_email,"
            + " notification_delivery_in_app, notification_delivery, notification,"
            + " customer_magic_token, payment_allocation, sales_invoice_line, sales_invoice,"
            + " refund, payment, payment_transaction, fulfillment_line, fulfillment,"
            + " inventory_reservation, inventory_log, inventory, sales_order_line, sales_order,"
            + " coupon, customer, product, user_org_role, app_user, org, order_number_counter,"
            + " invoice_number_counter RESTART IDENTITY CASCADE");
  }

  // ───────── group 1: percent + fixed happy paths, both planes ─────────

  @Test
  void percentCoupon_reducesTheRealGrandTotal_andReconcilesAtTheDiscountedAmount() {
    UUID org = createOrg("0.1400", "25.00");
    UUID product = createProduct(org, "10.00");
    createInventory(org, product, 50);
    coupon(org, "RAMADAN10", CouponType.PERCENT, "10.00");

    // 20 × 10.00 = 200.00 goods, 14% tax = 28.00, shipping 25.00; 10% of the GOODS = 20.00.
    StorefrontPlaced placed = placeStorefront(org, product, 20, "10.00", "RAMADAN10");

    assertEquals(0, new BigDecimal("200.00").compareTo(placed.order().getSubtotal()));
    assertEquals(0, new BigDecimal("28.00").compareTo(placed.order().getTaxTotal()));
    assertEquals(0, new BigDecimal("25.00").compareTo(placed.order().getShippingTotal()));
    assertEquals(0, new BigDecimal("20.00").compareTo(placed.order().getDiscountTotal()));
    // grand = subtotal + tax + shipping − discount. The discount comes off the goods, NOT the tax
    // the merchant owes nor the courier's fee.
    assertEquals(0, new BigDecimal("233.00").compareTo(placed.order().getGrandTotal()));
    // The code is frozen on the order for display.
    assertEquals("RAMADAN10", placed.order().getCouponCode());
    assertNotNull(placed.order().getCouponId());

    // An exact cover at the DISCOUNTED grand is what reconciles — the discount is real money.
    assertEquals("MATCHED", reconcile(org, placed, "233.00"));
    assertEquals(
        "PAID",
        dsl.fetchOne("SELECT status FROM sales_order WHERE id = ?", placed.order().getId())
            .get(0, String.class));
  }

  @Test
  void fixedCoupon_onThePortalPlane_freezesTheCodeAndReducesTheTotal() {
    UUID org = createOrg(null, null); // no tax, no shipping — isolate the discount
    UUID product = createProduct(org, "10.00");
    createInventory(org, product, 50);
    UUID customer = createCustomer(org, "nadia@acme.test");
    coupon(org, "EGP50", CouponType.FIXED, "50.00");

    // The authenticated plane's placement core — the same buildDraftOrder path the portal checkout
    // funnels into (slug resolution itself is PortalCheckoutIT's subject, not this one's).
    StorefrontPlaced placed =
        dsl.transactionResult(
            cfg ->
                salesOrderService.placeStorefrontOrderForCustomer(
                    DSL.using(cfg),
                    org,
                    customer,
                    new SalesOrderService.DeliveryInput("Nadia", "+20100", "1 Nile St"),
                    List.of(
                        new StorefrontLineInput(product, 10, new BigDecimal("10.00"), "Widget")),
                    UUID.randomUUID().toString(),
                    null,
                    "egp50", // lower-case on the wire — normalization is the service's job
                    actor));

    assertEquals(0, new BigDecimal("100.00").compareTo(placed.order().getSubtotal()));
    assertEquals(0, new BigDecimal("50.00").compareTo(placed.order().getDiscountTotal()));
    assertEquals(0, new BigDecimal("50.00").compareTo(placed.order().getGrandTotal()));
    assertEquals("EGP50", placed.order().getCouponCode(), "stored + echoed normalized UPPER");
  }

  @Test
  void fixedCoupon_largerThanTheBasket_isCappedAtTheSubtotal_notNegative() {
    UUID org = createOrg(null, "5.00");
    UUID product = createProduct(org, "10.00");
    createInventory(org, product, 50);
    coupon(org, "BIG", CouponType.FIXED, "500.00");

    // Goods 20.00, a 500 code: capped to 20.00, so the grand is the shipping alone — still > 0,
    // which is exactly why the cap exists rather than a negative subtotal.
    StorefrontPlaced placed = placeStorefront(org, product, 2, "10.00", "BIG");
    assertEquals(0, new BigDecimal("20.00").compareTo(placed.order().getDiscountTotal()));
    assertEquals(0, new BigDecimal("5.00").compareTo(placed.order().getGrandTotal()));
  }

  // ───────── group 2: the proration identity ─────────

  /**
   * Two lines of unequal value, an <b>odd-piastre</b> fixed discount, delivered separately. Each
   * invoice carries its prorated share; the completing invoice takes the exact remainder, so the
   * sum lands penny-perfect and the order CLOSES.
   *
   * <p>The arithmetic, spelled out because it is the load-bearing claim: goods 10.00 + 20.00 =
   * 30.00, discount 10.01. Invoice A → 10.01 × 10/30 = 3.336̅ → 3.34 (HALF_EVEN). Invoice B
   * completes the goods → the remainder 10.01 − 3.34 = 6.67, NOT its own rounded share. 3.34 + 6.67
   * = 10.01 exactly.
   */
  @Test
  void partialDelivery_proratesTheDiscount_pennyExact_andInvoicesSumToTheOrderGrand() {
    UUID org = createOrg(null, null); // no tax/shipping: the proration is the only arithmetic here
    UUID cheap = createProduct(org, "10.00");
    UUID dear = createProduct(org, "20.00");
    createInventory(org, cheap, 5);
    createInventory(org, dear, 5);
    coupon(org, "ODD", CouponType.FIXED, "10.01");

    StorefrontPlaced placed =
        placeStorefront(
            org,
            List.of(
                new StorefrontLineInput(cheap, 1, new BigDecimal("10.00"), "Cheap"),
                new StorefrontLineInput(dear, 1, new BigDecimal("20.00"), "Dear")),
            "ODD");
    assertEquals(0, new BigDecimal("30.00").compareTo(placed.order().getSubtotal()));
    assertEquals(0, new BigDecimal("10.01").compareTo(placed.order().getDiscountTotal()));
    assertEquals(0, new BigDecimal("19.99").compareTo(placed.order().getGrandTotal()));

    reconcile(org, placed, "19.99");

    // Deliver the cheap line first → its share of the discount, rounded.
    SalesInvoice invA = deliverLine(org, placed, 0);
    assertEquals(0, new BigDecimal("10.00").compareTo(invA.getSubtotal()));
    assertEquals(0, new BigDecimal("3.34").compareTo(invA.getDiscountTotal()));
    assertEquals(0, new BigDecimal("6.66").compareTo(invA.getGrandTotal()));

    // Deliver the dear line → the completing invoice takes the EXACT remainder (6.67, not 6.68).
    SalesInvoice invB = deliverLine(org, placed, 1);
    assertEquals(0, new BigDecimal("20.00").compareTo(invB.getSubtotal()));
    assertEquals(0, new BigDecimal("6.67").compareTo(invB.getDiscountTotal()));
    assertEquals(0, new BigDecimal("13.33").compareTo(invB.getGrandTotal()));

    // The identity the CLOSED roll-up and the FIFO allocator both depend on.
    assertEquals(0, new BigDecimal("10.01").compareTo(liveInvoiceSum(placed, "discount_total")));
    assertEquals(
        0, placed.order().getGrandTotal().compareTo(liveInvoiceSum(placed, "grand_total")));
    assertEquals(
        "CLOSED",
        dsl.fetchOne("SELECT status FROM sales_order WHERE id = ?", placed.order().getId())
            .get(0, String.class));
  }

  @Test
  void singleDelivery_carriesTheWholeDiscount_andEveryInvoiceStaysPositive() {
    UUID org = createOrg("0.1000", "15.00");
    UUID product = createProduct(org, "10.00");
    createInventory(org, product, 10);
    coupon(org, "HALF", CouponType.PERCENT, "50.00");

    // Goods 40.00, tax 4.00, shipping 15.00, discount 20.00 → grand 39.00.
    StorefrontPlaced placed = placeStorefront(org, product, 4, "10.00", "HALF");
    assertEquals(0, new BigDecimal("39.00").compareTo(placed.order().getGrandTotal()));
    reconcile(org, placed, "39.00");

    // One fulfillment covers the whole order → it is the completing invoice: full shipping AND the
    // full discount, and it still sums to the order grand.
    SalesInvoice inv = deliverLine(org, placed, 0);
    assertEquals(0, new BigDecimal("20.00").compareTo(inv.getDiscountTotal()));
    assertEquals(0, new BigDecimal("15.00").compareTo(inv.getShippingTotal()));
    assertEquals(0, new BigDecimal("39.00").compareTo(inv.getGrandTotal()));
    assertTrue(inv.getGrandTotal().signum() > 0);
    assertEquals(
        0, placed.order().getGrandTotal().compareTo(liveInvoiceSum(placed, "grand_total")));
  }

  // ───────── group 3: the eligibility wall ─────────

  @Test
  void unknownInactiveExpiredNotStartedAndExhausted_allCollapseToOneUniformMessage() {
    UUID org = createOrg(null, null);
    UUID product = createProduct(org, "10.00");
    createInventory(org, product, 50);
    OffsetDateTime now = OffsetDateTime.now();

    UUID inactive = coupon(org, "OFF", CouponType.PERCENT, "10.00");
    dsl.update(COUPON).set(COUPON.ACTIVE, false).where(COUPON.ID.eq(inactive)).execute();
    UUID expired = coupon(org, "GONE", CouponType.PERCENT, "10.00");
    dsl.update(COUPON)
        .set(COUPON.EXPIRES_AT, now.minusDays(1))
        .where(COUPON.ID.eq(expired))
        .execute();
    UUID future = coupon(org, "SOON", CouponType.PERCENT, "10.00");
    dsl.update(COUPON).set(COUPON.STARTS_AT, now.plusDays(1)).where(COUPON.ID.eq(future)).execute();

    // Every one of them — including a code that never existed — reads identically. A shopper learns
    // nothing about which strings exist, which is the point.
    for (String code : List.of("NEVER-EXISTED", "OFF", "GONE", "SOON")) {
      ValidationException e =
          assertThrows(
              ValidationException.class,
              () -> placeStorefront(org, product, 1, "10.00", code),
              code);
      assertEquals(CouponService.INVALID_MESSAGE, e.getMessage(), code);
    }
    // Nothing was placed by any of them.
    assertEquals(0, dsl.fetchCount(dsl.selectFrom("sales_order")));
  }

  @Test
  void belowMinimum_getsTheOneHelpfulSpecificMessage_namingTheThreshold() {
    UUID org = createOrg(null, null);
    UUID product = createProduct(org, "10.00");
    createInventory(org, product, 50);
    UUID id = coupon(org, "SPEND100", CouponType.FIXED, "10.00");
    dsl.update(COUPON)
        .set(COUPON.MIN_SUBTOTAL, new BigDecimal("100.00"))
        .where(COUPON.ID.eq(id))
        .execute();

    // 50.00 of goods against a 100 minimum: the code IS valid, so telling the shopper how to
    // qualify is the honest move — this is the single case that does not collapse.
    ValidationException e =
        assertThrows(
            ValidationException.class, () -> placeStorefront(org, product, 5, "10.00", "SPEND100"));
    assertTrue(e.getMessage().contains("100"), e.getMessage());
    assertFalse(e.getMessage().equals(CouponService.INVALID_MESSAGE));

    // At the threshold it applies.
    StorefrontPlaced ok = placeStorefront(org, product, 10, "10.00", "SPEND100");
    assertEquals(0, new BigDecimal("10.00").compareTo(ok.order().getDiscountTotal()));
  }

  @Test
  void aCouponThatWouldZeroTheOrder_isRefusedNamingTheCause_andNothingIsPlaced() {
    UUID org = createOrg(null, null); // no tax, no shipping → 100% off means grand 0
    UUID product = createProduct(org, "10.00");
    createInventory(org, product, 50);
    coupon(org, "FREE", CouponType.PERCENT, "100.00");

    ValidationException e =
        assertThrows(
            ValidationException.class, () -> placeStorefront(org, product, 2, "10.00", "FREE"));
    assertTrue(e.getMessage().contains("exceeds the order total"), e.getMessage());
    assertEquals(0, dsl.fetchCount(dsl.selectFrom("sales_order")));

    // The same code on an order that still owes shipping is fine — a positive grand is the rule,
    // not "no full-value codes".
    UUID shipping = createOrg(null, "12.00");
    UUID p2 = createProduct(shipping, "10.00");
    createInventory(shipping, p2, 50);
    coupon(shipping, "FREE", CouponType.PERCENT, "100.00");
    StorefrontPlaced placed = placeStorefront(shipping, p2, 2, "10.00", "FREE");
    assertEquals(0, new BigDecimal("12.00").compareTo(placed.order().getGrandTotal()));
  }

  // ───────── group 4: the redemption cap — race + freeing ─────────

  @Test
  void capOfOne_underTwoConcurrentPlacements_admitsExactlyOne() throws Exception {
    UUID org = createOrg(null, null);
    UUID product = createProduct(org, "10.00");
    createInventory(org, product, 100);
    UUID id = coupon(org, "ONLYONE", CouponType.FIXED, "5.00");
    dsl.update(COUPON).set(COUPON.MAX_REDEMPTIONS, 1).where(COUPON.ID.eq(id)).execute();

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Boolean>> results = new ArrayList<>(2);
      for (int i = 0; i < 2; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await(5, TimeUnit.SECONDS);
                  try {
                    placeStorefront(org, product, 1, "10.00", "ONLYONE");
                    return true;
                  } catch (ValidationException e) {
                    // The loser gets the uniform message — it must not reveal "cap reached".
                    assertEquals(CouponService.INVALID_MESSAGE, e.getMessage());
                    return false;
                  }
                }));
      }
      start.countDown();
      int wins = 0;
      for (Future<Boolean> r : results) {
        if (r.get(30, TimeUnit.SECONDS)) {
          wins++;
        }
      }
      // FOR UPDATE on the coupon row serializes the two placements: the second sees the first's
      // order in the live count and is refused. Without the lock both would read 0 and both pass.
      assertEquals(1, wins, "exactly one placement may take the last slot");
      assertEquals(1, liveRedemptions(org, id));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void cancellingTheWinner_freesTheSlot_becauseRedemptionsAreCountedNotIncremented() {
    UUID org = createOrg(null, null);
    UUID product = createProduct(org, "10.00");
    createInventory(org, product, 100);
    UUID id = coupon(org, "ONLYONE", CouponType.FIXED, "5.00");
    dsl.update(COUPON).set(COUPON.MAX_REDEMPTIONS, 1).where(COUPON.ID.eq(id)).execute();

    StorefrontPlaced first = placeStorefront(org, product, 1, "10.00", "ONLYONE");
    assertEquals(1, liveRedemptions(org, id));
    // The slot is taken.
    assertThrows(
        ValidationException.class, () -> placeStorefront(org, product, 1, "10.00", "ONLYONE"));

    // Cancel it. The status flip is applied directly here on purpose: what is under test is the
    // COUNTING semantics (CANCELLED/EXPIRED orders hold no slot), and the cancellation machinery
    // itself has its own suite. This is exactly why redemptions are a query and not a counter —
    // there is no compensating decrement anywhere to forget.
    dsl.execute("UPDATE sales_order SET status = 'CANCELLED' WHERE id = ?", first.order().getId());
    assertEquals(0, liveRedemptions(org, id));

    StorefrontPlaced second = placeStorefront(org, product, 1, "10.00", "ONLYONE");
    assertEquals(0, new BigDecimal("5.00").compareTo(second.order().getDiscountTotal()));
    assertEquals(1, liveRedemptions(org, id));

    // An EXPIRED order frees its slot the same way.
    dsl.execute("UPDATE sales_order SET status = 'EXPIRED' WHERE id = ?", second.order().getId());
    assertEquals(0, liveRedemptions(org, id));
  }

  // ───────── group 5: the preview is advisory, and agrees with placement ─────────

  @Test
  void preview_matchesPlacementMath_exactly_andSharesItsMessages() {
    UUID org = createOrg("0.1400", "25.00");
    UUID product = createProduct(org, "10.00");
    createInventory(org, product, 50);
    coupon(org, "RAMADAN10", CouponType.PERCENT, "10.00");

    // The preview is quoted against the GOODS subtotal, and its answer is the number placement
    // computes — one implementation of the arithmetic, on the model.
    CouponService.Applied preview =
        couponService.preview(org, "ramadan10", new BigDecimal("200.00"));
    assertEquals("RAMADAN10", preview.code());
    assertEquals(0, new BigDecimal("20.00").compareTo(preview.discount()));

    StorefrontPlaced placed = placeStorefront(org, product, 20, "10.00", "RAMADAN10");
    assertEquals(0, preview.discount().compareTo(placed.order().getDiscountTotal()));

    // The refusals read the same on both sides — no message drift between preview and place.
    ValidationException e =
        assertThrows(
            ValidationException.class,
            () -> couponService.preview(org, "NOPE", new BigDecimal("200.00")));
    assertEquals(CouponService.INVALID_MESSAGE, e.getMessage());
  }

  @Test
  void aSlotLostBetweenPreviewAndPlacement_failsCleanlyAtPlacement_withNoOrderCreated() {
    UUID org = createOrg(null, null);
    UUID product = createProduct(org, "10.00");
    createInventory(org, product, 100);
    UUID id = coupon(org, "LAST", CouponType.FIXED, "5.00");
    dsl.update(COUPON).set(COUPON.MAX_REDEMPTIONS, 1).where(COUPON.ID.eq(id)).execute();

    // The preview says 5.00 off — true at the time it was asked.
    assertEquals(
        0,
        new BigDecimal("5.00")
            .compareTo(couponService.preview(org, "LAST", new BigDecimal("10.00")).discount()));

    // Someone else takes the slot.
    placeStorefront(org, product, 1, "10.00", "LAST");
    long ordersBefore = dsl.fetchCount(dsl.selectFrom("sales_order"));

    // The advisory answer is now stale, and placement — the authority — refuses cleanly.
    ValidationException e =
        assertThrows(
            ValidationException.class, () -> placeStorefront(org, product, 1, "10.00", "LAST"));
    assertEquals(CouponService.INVALID_MESSAGE, e.getMessage());
    assertEquals(ordersBefore, dsl.fetchCount(dsl.selectFrom("sales_order")), "nothing placed");
  }

  // ───────── group 7: no-coupon regression rider ─────────

  @Test
  void aCheckoutWithoutACoupon_isUnchanged_downToTheNullSnapshot() {
    UUID org = createOrg("0.1400", "25.00");
    UUID product = createProduct(org, "10.00");
    createInventory(org, product, 50);
    // A live coupon exists in the org — merely having one must change nothing for a shopper who
    // does not type it.
    coupon(org, "RAMADAN10", CouponType.PERCENT, "10.00");

    StorefrontPlaced placed = placeStorefront(org, product, 20, "10.00", null);

    assertEquals(0, BigDecimal.ZERO.compareTo(placed.order().getDiscountTotal()));
    assertEquals(0, new BigDecimal("253.00").compareTo(placed.order().getGrandTotal()));
    assertNull(placed.order().getCouponId());
    assertNull(placed.order().getCouponCode());

    reconcile(org, placed, "253.00");
    SalesInvoice inv = deliverLine(org, placed, 0);
    // The invoice's discount stays zero — the pre-V72 shape, byte for byte.
    assertEquals(0, BigDecimal.ZERO.compareTo(inv.getDiscountTotal()));
    assertEquals(0, new BigDecimal("253.00").compareTo(inv.getGrandTotal()));
  }

  @Test
  void aBlankCodeIsTreatedAsNoCoupon_notAsAnError() {
    UUID org = createOrg(null, null);
    UUID product = createProduct(org, "10.00");
    createInventory(org, product, 50);

    for (String blank : new String[] {null, "", "   "}) {
      StorefrontPlaced placed = placeStorefront(org, product, 1, "10.00", blank);
      assertNull(placed.order().getCouponCode());
      assertEquals(0, BigDecimal.ZERO.compareTo(placed.order().getDiscountTotal()));
    }
  }

  // ───────── flow helpers ─────────

  private StorefrontPlaced placeStorefront(
      UUID org, UUID product, int qty, String unitPrice, String couponCode) {
    return placeStorefront(
        org,
        List.of(new StorefrontLineInput(product, qty, new BigDecimal(unitPrice), "Widget")),
        couponCode);
  }

  private StorefrontPlaced placeStorefront(
      UUID org, List<StorefrontLineInput> lines, String couponCode) {
    return salesOrderService.placeStorefrontOrder(
        org,
        new CustomerInput("Nadia", "nadia@acme.test", null, null),
        lines,
        UUID.randomUUID().toString(),
        null,
        couponCode,
        null,
        actor);
  }

  private String reconcile(UUID org, StorefrontPlaced placed, String amount) {
    return txnService
        .verify(
            org,
            new VerifyCommand(
                PaymentProvider.INSTAPAY_MANUAL,
                "IPN-" + refSeq.getAndIncrement(),
                new BigDecimal(amount),
                "EGP",
                null,
                placed.order().getOrderNumber(),
                null,
                null,
                null,
                null),
            createUser(org))
        .reconciliationStatus()
        .name();
  }

  /** Create + ship + deliver a single-line fulfillment for the order's {@code lineIdx} line. */
  private SalesInvoice deliverLine(UUID org, StorefrontPlaced placed, int lineIdx) {
    UUID f =
        fulfillmentService
            .create(
                org,
                placed.order().getId(),
                List.of(new LineInput(placed.lines().get(lineIdx).getId())),
                null,
                null,
                null,
                actor)
            .fulfillment()
            .getId();
    fulfillmentService.ship(org, f, actor);
    return fulfillmentService.markDelivered(org, f, actor).invoice();
  }

  private BigDecimal liveInvoiceSum(StorefrontPlaced placed, String column) {
    return dsl.fetchOne(
            "SELECT COALESCE(SUM("
                + column
                + "),0) FROM sales_invoice WHERE sales_order_id = ? AND status <> 'VOID'",
            placed.order().getId())
        .get(0, BigDecimal.class);
  }

  private long liveRedemptions(UUID org, UUID couponId) {
    return new CouponRepositoryFactoryImpl().create(dsl).countLiveRedemptions(org, couponId);
  }

  // ───────── entity helpers ─────────

  private UUID coupon(UUID org, String code, CouponType type, String value) {
    return couponService
        .create(
            org,
            new CouponService.CouponInput(
                code, type, new BigDecimal(value), null, null, null, null))
        .getId();
  }

  private UUID createOrg(String taxRate, String shippingFee) {
    UUID id = UUID.randomUUID();
    var insert =
        dsl.insertInto(ORG)
            .set(ORG.ID, id)
            .set(ORG.NAME, "acme")
            .set(ORG.SLUG, "acme-" + id)
            .set(ORG.ACTIVE, true);
    if (taxRate != null) {
      insert = insert.set(ORG.TAX_RATE, new BigDecimal(taxRate));
    }
    if (shippingFee != null) {
      insert = insert.set(ORG.SHIPPING_FEE, new BigDecimal(shippingFee));
    }
    insert.execute();
    return id;
  }

  private UUID createUser(UUID org) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "-admin@acme.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .execute();
    return id;
  }

  private UUID createCustomer(UUID org, String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, org)
        .set(CUSTOMER.NAME, "Nadia")
        .set(CUSTOMER.EMAIL, email)
        .execute();
    return id;
  }

  private UUID createProduct(UUID org, String basePrice) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, id)
        .set(PRODUCT.ORG_ID, org)
        .set(PRODUCT.NAME, "widget")
        .set(PRODUCT.SKU, "SKU-" + id)
        .set(PRODUCT.BASE_PRICE, new BigDecimal(basePrice))
        .execute();
    return id;
  }

  private void createInventory(UUID org, UUID product, int stockQty) {
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, org)
        .set(INVENTORY.PRODUCT_ID, product)
        .set(INVENTORY.STOCK_QTY, stockQty)
        .set(INVENTORY.RESERVED_QTY, 0)
        .execute();
  }
}
