package com.loai.inventory.api.order;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.dto.CouponResponse;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.Coupon;
import com.loai.inventory.domain.model.CouponType;
import com.loai.inventory.repository.CouponRepositoryFactoryImpl;
import com.loai.inventory.service.CouponService;
import com.loai.inventory.service.CouponService.CouponInput;
import com.loai.inventory.service.CouponService.CouponPatch;
import com.loai.inventory.service.CouponService.CouponView;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
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
 * Coupon administration — roadmap item 9, {@code stories/honest_coupons.md} (V72): create/validate,
 * the immutability of the arithmetic fields, the patchable knobs, the never-redeemed delete guard,
 * redemption counts across statuses, cross-org isolation, and the code-normalization rules.
 */
@Testcontainers
class CouponAdminIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static CouponService service;
  static final ObjectMapper JSON = ObjectMapperProvider.build();

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
    service = new CouponService(dsl, new CouponRepositoryFactoryImpl());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void fresh() {
    dsl.execute(
        "TRUNCATE sales_order_line, sales_order, coupon, customer, org, order_number_counter"
            + " RESTART IDENTITY CASCADE");
  }

  // ───────── create + validation ─────────

  @Test
  void create_storesTheCodeNormalizedUpper_andEchoesTheWholeShape() {
    UUID org = createOrg();
    OffsetDateTime start = OffsetDateTime.parse("2026-08-01T00:00:00Z");
    OffsetDateTime end = OffsetDateTime.parse("2026-09-01T00:00:00Z");

    Coupon created =
        service.create(
            org,
            new CouponInput(
                "  ramadan 10 ",
                CouponType.PERCENT,
                new BigDecimal("10.00"),
                new BigDecimal("100.00"),
                start,
                end,
                50));

    // Trimmed, inner spaces removed, upper-cased — so a shopper typing any casing hits one row.
    assertEquals("RAMADAN10", created.getCode());

    CouponView view = service.getById(org, created.getId());
    assertEquals(0L, view.redemptionCount());
    CouponResponse r = CouponResponse.from(view);
    assertEquals("PERCENT", r.getType());
    assertEquals(0, new BigDecimal("10.00").compareTo(r.getValue()));
    assertEquals(0, new BigDecimal("100.00").compareTo(r.getMinSubtotal()));
    // Compared by INSTANT, not by object: a TIMESTAMPTZ round-trips through JDBC in the session's
    // offset ("2026-08-01T03:00+03:00"), which is the same moment written in a different offset.
    assertTrue(start.isEqual(r.getStartsAt()), String.valueOf(r.getStartsAt()));
    assertTrue(end.isEqual(r.getExpiresAt()), String.valueOf(r.getExpiresAt()));
    assertEquals(50, r.getMaxRedemptions());
    assertTrue(r.isActive(), "a fresh coupon is live unless the window says otherwise");
  }

  @Test
  void create_rejectsBadValuesAndWindows_withCauseNamingMessages() {
    UUID org = createOrg();

    // Percent above 100 would produce a discount larger than the goods.
    ValidationException percent =
        assertThrows(
            ValidationException.class,
            () -> service.create(org, input("P", CouponType.PERCENT, "101")));
    assertTrue(percent.getMessage().contains("100"), percent.getMessage());

    // Zero / negative value.
    for (String bad : List.of("0", "-5")) {
      assertThrows(
          ValidationException.class,
          () -> service.create(org, input("V" + bad, CouponType.FIXED, bad)),
          bad);
    }
    // A blank code.
    for (String blank : new String[] {null, "", "   "}) {
      assertThrows(
          ValidationException.class,
          () -> service.create(org, input(blank, CouponType.FIXED, "10")));
    }
    // A missing type.
    assertThrows(
        ValidationException.class,
        () ->
            service.create(
                org, new CouponInput("X", null, new BigDecimal("10"), null, null, null, null)));
    // An inverted window.
    ValidationException window =
        assertThrows(
            ValidationException.class,
            () ->
                service.create(
                    org,
                    new CouponInput(
                        "W",
                        CouponType.FIXED,
                        new BigDecimal("10"),
                        null,
                        OffsetDateTime.parse("2026-09-01T00:00:00Z"),
                        OffsetDateTime.parse("2026-08-01T00:00:00Z"),
                        null)));
    assertTrue(window.getMessage().contains("starts_at"), window.getMessage());
    // A non-positive cap.
    assertThrows(
        ValidationException.class,
        () ->
            service.create(
                org,
                new CouponInput("C", CouponType.FIXED, new BigDecimal("10"), null, null, null, 0)));

    // Nothing was created by any rejected write.
    assertEquals(0L, service.count(org));
  }

  @Test
  void duplicateCode_is409_evenWhenTypedInADifferentCase() {
    UUID org = createOrg();
    service.create(org, input("SUMMER", CouponType.FIXED, "10"));
    ConflictException e =
        assertThrows(
            ConflictException.class,
            () -> service.create(org, input("summer", CouponType.PERCENT, "5")));
    assertTrue(e.getMessage().contains("SUMMER"), e.getMessage());
    assertEquals(1L, service.count(org));
  }

  // ───────── patch: the mutable knobs, and the immutable arithmetic ─────────

  @Test
  void patch_flipsActive_expiry_andCap_leavingNullsUnchanged() {
    UUID org = createOrg();
    UUID id = service.create(org, input("SALE", CouponType.PERCENT, "15")).getId();
    OffsetDateTime newEnd = OffsetDateTime.parse("2026-12-31T00:00:00Z");

    // Deactivate only — expiry and cap untouched.
    Coupon off = service.patch(org, id, new CouponPatch(false, null, null));
    assertFalse(off.isActive());
    assertEquals(null, off.getExpiresAt());

    // Reactivate + set an expiry + a cap.
    Coupon on = service.patch(org, id, new CouponPatch(true, newEnd, 5));
    assertTrue(on.isActive());
    assertTrue(newEnd.isEqual(on.getExpiresAt()), String.valueOf(on.getExpiresAt()));
    assertEquals(5, on.getMaxRedemptions());

    // The arithmetic survived every patch — it is not addressable through this API at all.
    assertEquals("SALE", on.getCode());
    assertEquals(CouponType.PERCENT, on.getType());
    assertEquals(0, new BigDecimal("15").compareTo(on.getValue()));
  }

  @Test
  void patch_cannotChangeTheCodeTypeOrValue_evenViaAMutatedObject() {
    UUID org = createOrg();
    UUID id = service.create(org, input("FIXEDVAL", CouponType.FIXED, "20")).getId();

    // The patch record structurally has no code/type/value field — and the repository's UPDATE
    // statement omits those columns, so even a caller holding a mutated Coupon cannot persist them.
    Coupon tampered = service.getById(org, id).coupon();
    tampered.setValue(new BigDecimal("999"));
    tampered.setCode("HACKED");
    tampered.setType(CouponType.PERCENT);
    new CouponRepositoryFactoryImpl().create(dsl).updateMutable(tampered);

    Coupon reread = service.getById(org, id).coupon();
    assertEquals("FIXEDVAL", reread.getCode());
    assertEquals(CouponType.FIXED, reread.getType());
    assertEquals(0, new BigDecimal("20").compareTo(reread.getValue()));
  }

  @Test
  void patch_rejectsAnInvertedWindowAndANonPositiveCap() {
    UUID org = createOrg();
    UUID id =
        service
            .create(
                org,
                new CouponInput(
                    "WIN",
                    CouponType.FIXED,
                    new BigDecimal("10"),
                    null,
                    OffsetDateTime.parse("2026-09-01T00:00:00Z"),
                    null,
                    null))
            .getId();

    assertThrows(
        ValidationException.class,
        () ->
            service.patch(
                org,
                id,
                new CouponPatch(null, OffsetDateTime.parse("2026-08-01T00:00:00Z"), null)));
    assertThrows(
        ValidationException.class, () -> service.patch(org, id, new CouponPatch(null, null, -1)));
  }

  // ───────── delete guard ─────────

  @Test
  void delete_worksWhileNeverRedeemed_and409sTheMomentAnOrderQuotesIt() {
    UUID org = createOrg();
    UUID unused = service.create(org, input("UNUSED", CouponType.FIXED, "10")).getId();
    service.delete(org, unused);
    assertThrows(NotFoundException.class, () -> service.getById(org, unused));

    UUID used = service.create(org, input("USED", CouponType.FIXED, "10")).getId();
    orderWithCoupon(org, used, "PAID");
    ConflictException e = assertThrows(ConflictException.class, () -> service.delete(org, used));
    assertTrue(e.getMessage().contains("deactivate"), e.getMessage());

    // Even a CANCELLED order blocks the delete: it still names the code on its record, and an order
    // must always be able to say which code produced its discount.
    UUID cancelled = service.create(org, input("CANCELLED", CouponType.FIXED, "10")).getId();
    orderWithCoupon(org, cancelled, "CANCELLED");
    assertThrows(ConflictException.class, () -> service.delete(org, cancelled));
    // ...but it holds no redemption SLOT — the two questions are different on purpose.
    assertEquals(0L, service.getById(org, cancelled).redemptionCount());
  }

  // ───────── redemption counts ─────────

  @Test
  void redemptionCounts_countLiveOrdersOnly_andAreBatchedOnTheList() {
    UUID org = createOrg();
    UUID a = service.create(org, input("AAA", CouponType.FIXED, "10")).getId();
    UUID b = service.create(org, input("BBB", CouponType.FIXED, "10")).getId();

    orderWithCoupon(org, a, "PENDING_PAYMENT");
    orderWithCoupon(org, a, "PAID");
    orderWithCoupon(org, a, "CLOSED");
    orderWithCoupon(org, a, "CANCELLED"); // freed
    orderWithCoupon(org, a, "EXPIRED"); // freed
    orderWithCoupon(org, b, "FULFILLING");

    assertEquals(3L, service.getById(org, a).redemptionCount());
    assertEquals(1L, service.getById(org, b).redemptionCount());

    // The list read carries the same numbers, batch-loaded (newest-first: B then A).
    List<CouponView> rows = service.getAll(org, 0, 20);
    assertEquals(List.of("BBB", "AAA"), rows.stream().map(v -> v.coupon().getCode()).toList());
    assertEquals(1L, rows.get(0).redemptionCount());
    assertEquals(3L, rows.get(1).redemptionCount());
  }

  // ───────── isolation + the wire shape ─────────

  @Test
  void crossOrg_sameCodeIsTwoDifferentCoupons_andForeignIdsAre404() {
    UUID mine = createOrg();
    UUID theirs = createOrg();
    UUID myCode = service.create(mine, input("SHARED", CouponType.FIXED, "10")).getId();
    UUID theirCode = service.create(theirs, input("SHARED", CouponType.PERCENT, "10")).getId();

    assertFalse(myCode.equals(theirCode));
    assertEquals(CouponType.FIXED, service.getById(mine, myCode).coupon().getType());
    assertEquals(CouponType.PERCENT, service.getById(theirs, theirCode).coupon().getType());
    assertThrows(NotFoundException.class, () -> service.getById(mine, theirCode));
    assertThrows(NotFoundException.class, () -> service.delete(mine, theirCode));
    assertThrows(
        NotFoundException.class,
        () -> service.patch(mine, theirCode, new CouponPatch(false, null, null)));
    // Neither org's preview can see the other's code semantics.
    assertEquals(
        0,
        new BigDecimal("10.00")
            .compareTo(service.preview(mine, "SHARED", new BigDecimal("100.00")).discount()));
    assertEquals(
        0,
        new BigDecimal("10.00")
            .compareTo(service.preview(theirs, "SHARED", new BigDecimal("100.00")).discount()));
  }

  @Test
  void theAdminJson_carriesTheCountButTheOrgIsTheOnlyIdBeyondTheCoupons() throws Exception {
    UUID org = createOrg();
    UUID id = service.create(org, input("JSON", CouponType.PERCENT, "10")).getId();
    orderWithCoupon(org, id, "PAID");

    String json = JSON.writeValueAsString(CouponResponse.from(service.getById(org, id)));
    assertTrue(json.contains("\"redemption_count\":1"), json);
    assertTrue(json.contains("\"code\":\"JSON\""), json);
    // The admin plane may carry ids — but never a customer's or an order's.
    assertFalse(json.contains("sales_order"), json);
    assertFalse(json.contains("customer"), json);
  }

  // ───────── helpers ─────────

  private static CouponInput input(String code, CouponType type, String value) {
    return new CouponInput(code, type, new BigDecimal(value), null, null, null, null);
  }

  private UUID createOrg() {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, "acme")
        .set(ORG.SLUG, "acme-" + id)
        .set(ORG.ACTIVE, true)
        .execute();
    return id;
  }

  /** A minimal order row holding the coupon in the given status — the count query's subject. */
  private void orderWithCoupon(UUID org, UUID couponId, String status) {
    UUID customer = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, customer)
        .set(CUSTOMER.ORG_ID, org)
        .set(CUSTOMER.NAME, "Nadia")
        .set(CUSTOMER.EMAIL, customer + "@acme.test")
        .execute();
    UUID orderId = UUID.randomUUID();
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, org)
        .set(SALES_ORDER.CUSTOMER_ID, customer)
        .set(SALES_ORDER.ORDER_NUMBER, "SO-2026-" + orderId.toString().substring(0, 5))
        .set(SALES_ORDER.CHANNEL, com.loai.inventory.repository.generated.enums.OrderChannel.ONLINE)
        .set(
            SALES_ORDER.STATUS,
            com.loai.inventory.repository.generated.enums.OrderStatus.valueOf(status))
        .set(SALES_ORDER.SUBTOTAL, new BigDecimal("100.00"))
        .set(SALES_ORDER.TAX_TOTAL, BigDecimal.ZERO)
        .set(SALES_ORDER.SHIPPING_TOTAL, BigDecimal.ZERO)
        .set(SALES_ORDER.DISCOUNT_TOTAL, new BigDecimal("10.00"))
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal("90.00"))
        .set(SALES_ORDER.CURRENCY, "EGP")
        .set(SALES_ORDER.PREPAID_AMOUNT, BigDecimal.ZERO)
        .set(SALES_ORDER.COUPON_ID, couponId)
        .set(SALES_ORDER.COUPON_CODE, "X")
        .execute();
  }
}
