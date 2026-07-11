package com.loai.inventory.api.orghealth;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.USER_ORG_ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.mapper.PaymentMapper;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.OrgHealth;
import com.loai.inventory.domain.model.OrgMember;
import com.loai.inventory.repository.OrgHealthRepositoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.repository.generated.enums.OrgRole;
import com.loai.inventory.repository.generated.enums.PaymentDirection;
import com.loai.inventory.repository.generated.enums.PaymentProvider;
import com.loai.inventory.repository.generated.enums.PaymentStatus;
import com.loai.inventory.repository.generated.enums.PaymentVerificationStatus;
import com.loai.inventory.service.OrgHealthService;
import com.loai.inventory.service.PaymentDisputeService;
import com.loai.inventory.service.PaymentDisputeService.PaymentPage;
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
 * Integration coverage for the org-scoped dashboard reads ({@code stories/org_health_rollup.md}):
 * the health rollup (G1), the payments worklist that backs the disputes / unallocated previews
 * (G2/G3), and the paginated members roster (G4). The headline property the story turns on: the
 * list totals equal the health rollup's counts, because both reduce to the same predicates — {@code
 * status=DISPUTED} ↔ {@code open_disputes}, {@code unallocated_amount > 0} ↔ {@code
 * unallocated_payments}, distinct members ↔ {@code member_count}.
 */
@Testcontainers
class OrgHealthReadsIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static OrgHealthService orgHealthService;
  static PaymentDisputeService paymentDisputeService;
  static UserRepositoryImpl userRepo;

  private final AtomicInteger seq = new AtomicInteger(1);

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

    orgHealthService = new OrgHealthService(new OrgHealthRepositoryImpl(dsl));
    paymentDisputeService =
        new PaymentDisputeService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new RefundAllocationRepositoryFactoryImpl(),
            new PaymentAllocationRepositoryFactoryImpl(),
            new SalesInvoiceRepositoryFactoryImpl());
    userRepo = new UserRepositoryImpl(dsl);
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
        "TRUNCATE payment, payment_transaction, sales_order, user_org_role, app_user, customer,"
            + " org RESTART IDENTITY CASCADE");
  }

  /** The four rollup figures match the seeded state exactly. */
  @Test
  void health_countsMembersPendingDisputesUnallocated() {
    UUID org = createOrg("acme");
    // 3 distinct members; the first holds two roles → still one member.
    UUID u1 = createUser();
    grantRole(org, u1, OrgRole.OWNER);
    grantRole(org, u1, OrgRole.MANAGER);
    grantRole(org, createUser(), OrgRole.STAFF);
    grantRole(org, createUser(), OrgRole.VIEWER);
    // 2 pending-payment orders (+ one PAID that must not count).
    createOrder(org, OrderStatus.PENDING_PAYMENT);
    createOrder(org, OrderStatus.PENDING_PAYMENT);
    createOrder(org, OrderStatus.PAID);
    // 1 disputed payment (fully allocated) + 2 unallocated + 1 fully-allocated (neither).
    createPayment(org, PaymentStatus.DISPUTED, "0.00");
    createPayment(org, PaymentStatus.RECEIVED, "100.00");
    createPayment(org, PaymentStatus.PARTIALLY_ALLOCATED, "40.00");
    createPayment(org, PaymentStatus.ALLOCATED, "0.00");

    OrgHealth h = orgHealthService.health(org);
    assertEquals(3, h.memberCount());
    assertEquals(2, h.pendingPaymentOrders());
    assertEquals(1, h.openDisputes());
    assertEquals(2, h.unallocatedPayments());
  }

  /** {@code status=DISPUTED} returns only disputed payments; its total equals open_disputes. */
  @Test
  void paymentsList_disputedFilter_totalEqualsHealthOpenDisputes() {
    UUID org = createOrg("acme");
    createPayment(org, PaymentStatus.DISPUTED, "0.00");
    createPayment(org, PaymentStatus.DISPUTED, "0.00");
    createPayment(org, PaymentStatus.RECEIVED, "100.00");

    PaymentPage disputed =
        paymentDisputeService.list(
            org, com.loai.inventory.domain.model.PaymentStatus.DISPUTED, false, 0, 20);
    assertEquals(2, disputed.total());
    assertEquals(2, disputed.items().size());
    assertTrue(
        disputed.items().stream()
            .allMatch(
                p -> p.getStatus() == com.loai.inventory.domain.model.PaymentStatus.DISPUTED));
    assertEquals(disputed.total(), orgHealthService.health(org).openDisputes());
  }

  /** {@code unallocated=true} returns only unallocated_amount>0; its total equals the rollup. */
  @Test
  void paymentsList_unallocatedFilter_totalEqualsHealthUnallocated() {
    UUID org = createOrg("acme");
    createPayment(org, PaymentStatus.RECEIVED, "100.00");
    createPayment(org, PaymentStatus.PARTIALLY_ALLOCATED, "30.00");
    createPayment(org, PaymentStatus.ALLOCATED, "0.00"); // no unallocated balance
    createPayment(org, PaymentStatus.DISPUTED, "0.00");

    PaymentPage unallocated = paymentDisputeService.list(org, null, true, 0, 20);
    assertEquals(2, unallocated.total());
    assertTrue(unallocated.items().stream().allMatch(p -> p.getUnallocatedAmount().signum() > 0));
    assertEquals(unallocated.total(), orgHealthService.health(org).unallocatedPayments());

    // The bare list is the whole ledger — all four payments.
    assertEquals(4, paymentDisputeService.list(org, null, false, 0, 20).total());
  }

  /** Unknown status is a 400 at the filter parse; known/blank parse as expected. */
  @Test
  void paymentsList_unknownStatus_is400() {
    assertThrows(ValidationException.class, () -> PaymentMapper.toStatusFilter("NOPE"));
    assertEquals(
        com.loai.inventory.domain.model.PaymentStatus.DISPUTED,
        PaymentMapper.toStatusFilter("disputed"));
    assertNull(PaymentMapper.toStatusFilter(null));
    assertNull(PaymentMapper.toStatusFilter("  "));
  }

  /** The roster paginates over distinct users; total equals health.member_count. */
  @Test
  void members_paginated_totalEqualsHealthMemberCount() {
    UUID org = createOrg("acme");
    // 3 distinct members (email order is stable); the first holds two roles.
    UUID u1 = createUser("a@acme.test");
    grantRole(org, u1, OrgRole.OWNER);
    grantRole(org, u1, OrgRole.MANAGER);
    grantRole(org, createUser("b@acme.test"), OrgRole.STAFF);
    grantRole(org, createUser("c@acme.test"), OrgRole.VIEWER);

    assertEquals(3, userRepo.countMembers(org));
    assertEquals(3, orgHealthService.health(org).memberCount());

    // page 0 (size 2) → first two by email; page 1 → the remaining one; page 2 → empty.
    List<OrgMember> page0 = userRepo.findMembers(org, 0, 2);
    List<OrgMember> page1 = userRepo.findMembers(org, 2, 2);
    assertEquals(2, page0.size());
    assertEquals(1, page1.size());
    assertTrue(userRepo.findMembers(org, 4, 2).isEmpty());
    // The multi-role member is one roster entry carrying both roles.
    OrgMember first = page0.get(0);
    assertEquals("a@acme.test", first.email());
    assertEquals(2, first.roles().size());
  }

  /** Every figure and list is scoped to its own org — a sibling org's rows never leak. */
  @Test
  void reads_areOrgScoped() {
    UUID a = createOrg("acme");
    UUID b = createOrg("beta");
    grantRole(a, createUser(), OrgRole.OWNER);
    createOrder(a, OrderStatus.PENDING_PAYMENT);
    createPayment(a, PaymentStatus.DISPUTED, "0.00");
    // Org B has its own noise.
    grantRole(b, createUser(), OrgRole.OWNER);
    grantRole(b, createUser(), OrgRole.STAFF);
    createOrder(b, OrderStatus.PENDING_PAYMENT);
    createOrder(b, OrderStatus.PENDING_PAYMENT);
    createPayment(b, PaymentStatus.DISPUTED, "0.00");
    createPayment(b, PaymentStatus.RECEIVED, "50.00");

    OrgHealth h = orgHealthService.health(a);
    assertEquals(1, h.memberCount());
    assertEquals(1, h.pendingPaymentOrders());
    assertEquals(1, h.openDisputes());
    assertEquals(0, h.unallocatedPayments());
    assertEquals(
        1,
        paymentDisputeService
            .list(a, com.loai.inventory.domain.model.PaymentStatus.DISPUTED, false, 0, 20)
            .total());
    assertEquals(1, userRepo.countMembers(a));
  }

  // seed helpers

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

  private UUID createUser() {
    return createUser("user-" + seq.getAndIncrement() + "@acme.test");
  }

  private UUID createUser(String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, email)
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .execute();
    return id;
  }

  private void grantRole(UUID org, UUID userId, OrgRole role) {
    dsl.insertInto(USER_ORG_ROLE)
        .set(USER_ORG_ROLE.USER_ID, userId)
        .set(USER_ORG_ROLE.ORG_ID, org)
        .set(USER_ORG_ROLE.ROLE, role)
        .execute();
  }

  private void createOrder(UUID org, OrderStatus status) {
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, UUID.randomUUID())
        .set(SALES_ORDER.ORG_ID, org)
        .set(SALES_ORDER.ORDER_NUMBER, "SO-" + seq.getAndIncrement())
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, status)
        .execute();
  }

  private void createPayment(UUID org, PaymentStatus status, String unallocated) {
    UUID txnId = UUID.randomUUID();
    dsl.insertInto(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.ID, txnId)
        .set(PAYMENT_TRANSACTION.ORG_ID, org)
        .set(PAYMENT_TRANSACTION.PROVIDER, PaymentProvider.instapay_manual)
        .set(PAYMENT_TRANSACTION.PROVIDER_REF, "IPN-" + seq.getAndIncrement())
        .set(PAYMENT_TRANSACTION.DIRECTION, PaymentDirection.CREDIT)
        .set(PAYMENT_TRANSACTION.AMOUNT, new BigDecimal("100.00"))
        .set(PAYMENT_TRANSACTION.VERIFICATION_STATUS, PaymentVerificationStatus.VERIFIED)
        .set(PAYMENT_TRANSACTION.OCCURRED_AT, now())
        .execute();
    dsl.insertInto(PAYMENT)
        .set(PAYMENT.ID, UUID.randomUUID())
        .set(PAYMENT.ORG_ID, org)
        .set(PAYMENT.PAYMENT_TRANSACTION_ID, txnId)
        .set(PAYMENT.AMOUNT, new BigDecimal("100.00"))
        .set(PAYMENT.UNALLOCATED_AMOUNT, new BigDecimal(unallocated))
        .set(PAYMENT.STATUS, status)
        .set(PAYMENT.RECEIVED_AT, now())
        .execute();
  }
}
