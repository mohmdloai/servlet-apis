package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.NOTIFICATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.dto.PaymentClaimStatusResponse;
import com.loai.inventory.api.dto.PublicOrderResponse;
import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PaymentVerificationStatus;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.ClaimCommand;
import com.loai.inventory.service.PaymentTransactionService.ClaimResult;
import com.loai.inventory.service.PaymentTransactionService.NotFoundResult;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Duration;
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
 * Integration coverage for phase 2 of the claims work ({@code stories/payment_claim_not_found.md}):
 * "can't find it" marks the claim NOT_FOUND with a reason + note, re-arms the order's hold by 6 h
 * and tells the shopper; re-filing the same reference re-opens the claim without stacking a hold;
 * the customer-facing order read carries the claim's state.
 */
@Testcontainers
class PaymentClaimNotFoundIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static PaymentTransactionService service;

  private final AtomicInteger orderSeq = new AtomicInteger(1);
  private final AtomicInteger refSeq = new AtomicInteger(1);

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

    PaymentService paymentService =
        new PaymentService(
            dsl,
            new PaymentRepositoryFactoryImpl(),
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentTransactionRepositoryFactoryImpl(),
            new RefundRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            TestWiring.notificationService(dsl),
            TestWiring.magicLinkService(dsl),
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
    service =
        new PaymentTransactionService(
            dsl,
            new PaymentTransactionRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            paymentService,
            refundService,
            TestWiring.storage(),
            new CustomerRepositoryFactoryImpl(),
            new UserRepositoryFactoryImpl());
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
        "TRUNCATE notification_delivery, notification, customer_magic_token, payment,"
            + " payment_transaction, sales_order_line, sales_order, customer, app_user, org"
            + " RESTART IDENTITY CASCADE");
  }

  @Test
  void notFound_marksTheClaim_reArmsTheHoldSixHours_andTellsTheShopper() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test");
    UUID customer = createCustomer(orgId);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    // A hold about to lapse — the claim moved it to +48h at filing; simulate time passing by
    // pulling it back to +1h before the manager answers.
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", now.plusHours(1));
    UUID claimId = fileClaim(orgId, order, customer);
    dsl.update(SALES_ORDER)
        .set(SALES_ORDER.EXPIRES_AT, now.plusHours(1))
        .where(SALES_ORDER.ID.eq(order.id()))
        .execute();

    NotFoundResult result =
        service.markClaimNotFound(
            orgId, claimId, "no_transfer", "  nothing between 11 and 13 Jul ", manager);

    assertFalse(result.replay());
    assertEquals(PaymentVerificationStatus.NOT_FOUND, result.transaction().getVerificationStatus());
    assertEquals("NO_TRANSFER", result.transaction().getNotFoundReason());
    assertEquals("nothing between 11 and 13 Jul", result.transaction().getNotFoundNote());
    assertEquals("NOT_FOUND", storedStatus(claimId));
    assertEquals("nothing between 11 and 13 Jul", storedNote(claimId));
    // The hold is re-armed to now + 6h (it was +1h).
    OffsetDateTime held = orderExpiresAt(order.id());
    assertTrue(
        Duration.between(now.plus(PaymentTransactionService.NOT_FOUND_GRACE), held)
                .abs()
                .toMinutes()
            < 2,
        "re-armed to now + 6h, was " + held);
    assertEquals(held.toInstant(), result.heldUntil().toInstant());
    // The shopper is told, inside the same transaction.
    var notification =
        dsl.selectFrom(NOTIFICATION).where(NOTIFICATION.TYPE.eq("PAYMENT_NOT_FOUND")).fetchOne();
    assertNotNull(notification, "PAYMENT_NOT_FOUND raised");
    assertEquals(customer, notification.getRecipientCustomerId());
    assertTrue(
        notification.getBody().contains("nothing between 11 and 13 Jul"), notification.getBody());
    assertTrue(notification.getBody().contains(result.transaction().getProviderRef()));
    assertTrue(notification.getTitle().contains(order.number()));
  }

  @Test
  void notFound_neverShortensALongerHold() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test");
    UUID customer = createCustomer(orgId);
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID claimId = fileClaim(orgId, order, customer); // hold → +48h
    OffsetDateTime before = orderExpiresAt(order.id());

    service.markClaimNotFound(orgId, claimId, "DIFFERENT_ACCOUNT", null, manager);

    assertEquals(
        before.toInstant(),
        orderExpiresAt(order.id()).toInstant(),
        "max(current, +6h) keeps the 48h");
  }

  @Test
  void notFound_replaysOnAnAnsweredClaim_andRefusesVerifiedOrAbandoned() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test");
    UUID customer = createCustomer(orgId);
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID claimId = fileClaim(orgId, order, customer);

    service.markClaimNotFound(orgId, claimId, "OTHER", "first look", manager);
    NotFoundResult again =
        service.markClaimNotFound(orgId, claimId, "NO_TRANSFER", "second", manager);
    assertTrue(again.replay(), "the answer on file stands");
    assertEquals("OTHER", again.transaction().getNotFoundReason());
    assertEquals(1, notificationCount(), "no second notification on a replay");

    // Bad reason → 400 before anything is touched.
    UUID other = fileClaim(orgId, order, customer);
    assertThrows(
        ValidationException.class,
        () -> service.markClaimNotFound(orgId, other, "MAYBE", null, manager));
    assertEquals("UNVERIFIED", storedStatus(other));

    // VERIFIED / ABANDONED → 409.
    VerifyResult verified = service.verifyClaim(orgId, other, null, manager);
    assertEquals("MATCHED", verified.reconciliationStatus().name());
    assertThrows(
        ConflictException.class,
        () -> service.markClaimNotFound(orgId, other, "NO_TRANSFER", null, manager));
    // `claimId` was NOT_FOUND and the order is now PAID by `other` → it closed ABANDONED.
    assertEquals("ABANDONED", storedStatus(claimId));
    assertThrows(
        ConflictException.class,
        () -> service.markClaimNotFound(orgId, claimId, "NO_TRANSFER", null, manager));
  }

  @Test
  void refilingTheSameReference_reopensANotFoundClaim_withoutStackingTheHold() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test");
    UUID customer = createCustomer(orgId);
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    String reference = nextRef();
    ClaimResult first =
        service.claim(orgId, new ClaimCommand(order.id(), customer, reference, null, null));
    service.markClaimNotFound(orgId, first.transaction().getId(), "NO_TRANSFER", "typo?", manager);
    OffsetDateTime heldBefore = orderExpiresAt(order.id());

    ClaimResult refiled =
        service.claim(orgId, new ClaimCommand(order.id(), customer, reference, null, "re-sent"));

    assertFalse(refiled.inserted());
    assertTrue(refiled.reopened(), "same reference → the same claim, pending again");
    assertEquals(first.transaction().getId(), refiled.transaction().getId());
    assertEquals(
        PaymentVerificationStatus.UNVERIFIED, refiled.transaction().getVerificationStatus());
    assertNull(refiled.transaction().getNotFoundReason());
    assertNull(refiled.transaction().getNotFoundNote());
    assertEquals("UNVERIFIED", storedStatus(first.transaction().getId()));
    assertEquals(
        heldBefore.toInstant(),
        orderExpiresAt(order.id()).toInstant(),
        "re-opening stacks no hold");
    assertEquals(1, txnCount(orgId));

    // A pending claim re-filed is a plain replay — nothing re-opened.
    ClaimResult replay =
        service.claim(orgId, new ClaimCommand(order.id(), customer, reference, null, null));
    assertFalse(replay.inserted());
    assertFalse(replay.reopened());
  }

  @Test
  void theCustomerFacingOrderRead_carriesTheClaimsState() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test");
    UUID customer = createCustomer(orgId);
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);

    assertTrue(service.latestClaimFor(order.id()).isEmpty(), "no claim yet");
    assertNull(
        PublicOrderResponse.forOrderView(orderOf(orgId, order), List.of(), null).getPaymentClaim());

    UUID claimId = fileClaim(orgId, order, customer);
    var pending = PaymentClaimStatusResponse.from(service.latestClaimFor(order.id()).orElse(null));
    assertTrue(pending.isPresent());
    assertEquals("PENDING", pending.get().getStatus());
    assertEquals(0, new BigDecimal("250.00").compareTo(pending.get().getAmount()));
    assertNull(pending.get().getReason());

    service.markClaimNotFound(orgId, claimId, "DIFFERENT_ACCOUNT", "check the account", manager);
    var notFound = PaymentClaimStatusResponse.from(service.latestClaimFor(order.id()).orElse(null));
    assertEquals("NOT_FOUND", notFound.get().getStatus());
    assertEquals("DIFFERENT_ACCOUNT", notFound.get().getReason());
    assertEquals("check the account", notFound.get().getNote());

    service.verifyClaim(orgId, claimId, null, manager);
    var confirmed =
        PaymentClaimStatusResponse.from(service.latestClaimFor(order.id()).orElse(null));
    assertEquals("CONFIRMED", confirmed.get().getStatus());
    assertNull(confirmed.get().getReason(), "cleared on verify");

    // The latest claim wins; an ABANDONED latest claim is simply absent.
    Order other = seedPendingOrder(orgId, customer, "100.00", "0.00", null);
    UUID a = fileClaim(orgId, other, customer);
    UUID b = fileClaim(orgId, other, customer);
    service.verifyClaim(orgId, a, null, manager); // b → ABANDONED, and b is the latest
    assertEquals("ABANDONED", storedStatus(b));
    assertTrue(
        PaymentClaimStatusResponse.from(service.latestClaimFor(other.id()).orElse(null)).isEmpty());
  }

  @Test
  void recordSupersedingAClaim_carriesShopperAndProof_answersTheClaim_andPaysTheOrder() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test");
    UUID customer = createCustomer(orgId);
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    String proofKey =
        com.loai.inventory.common.storage.ObjectStorage.paymentProofKeyPrefix(orgId, order.id())
            + "receipt.png";
    UUID claimId =
        service
            .claim(orgId, new ClaimCommand(order.id(), customer, "770099887766", proofKey, "typo?"))
            .transaction()
            .getId();

    // The screenshot showed 7700 9988 7767 — one digit off what the shopper typed. The manager
    // records the real reference FROM the claim: no guard (the claim is the one being answered),
    // the shopper + screenshot travel to the new row, the claim is answered "reference differs".
    VerifyResult result =
        service.verify(
            orgId,
            new PaymentTransactionService.VerifyCommand(
                com.loai.inventory.domain.model.PaymentProvider.INSTAPAY_MANUAL,
                "770099887767",
                new BigDecimal("250.00"),
                "EGP",
                order.id(),
                null,
                null,
                null,
                null,
                null,
                java.util.Set.of(),
                claimId),
            manager);

    assertEquals("MATCHED", result.reconciliationStatus().name());
    assertEquals(customer, result.transaction().getClaimedByCustomerId(), "the shopper travels");
    assertEquals(proofKey, result.transaction().getProofObjectKey(), "the screenshot travels");
    assertNull(result.transaction().getClaimedSalesOrderId(), "the record is not a shopper claim");
    assertEquals("PAID", orderStatus(order.id()));
    // The superseded claim: answered (reference differs), then closed by the PAID flip.
    assertEquals("ABANDONED", storedStatus(claimId));
    assertEquals(
        com.loai.inventory.domain.model.PaymentTransaction.REASON_REFERENCE_DIFFERS,
        dsl.select(PAYMENT_TRANSACTION.NOT_FOUND_REASON)
            .from(PAYMENT_TRANSACTION)
            .where(PAYMENT_TRANSACTION.ID.eq(claimId))
            .fetchOne(PAYMENT_TRANSACTION.NOT_FOUND_REASON));
    assertEquals(0, notificationCount(), "no not-found mail — the shopper's order is paid");
    assertEquals(2, txnCount(orgId));

    // A VERIFIED claim cannot be superseded.
    Order other = seedPendingOrder(orgId, customer, "100.00", "0.00", null);
    UUID verified = fileClaim(orgId, other, customer);
    service.verifyClaim(orgId, verified, null, manager);
    assertThrows(
        ConflictException.class,
        () ->
            service.verify(
                orgId,
                new PaymentTransactionService.VerifyCommand(
                    com.loai.inventory.domain.model.PaymentProvider.INSTAPAY_MANUAL,
                    nextRef(),
                    new BigDecimal("100.00"),
                    "EGP",
                    other.id(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    java.util.Set.of(),
                    verified),
                manager));
  }

  // helpers

  private String nextRef() {
    return "IPN-" + refSeq.getAndIncrement();
  }

  private record Order(UUID id, String number) {}

  private UUID fileClaim(UUID orgId, Order order, UUID customer) {
    return service
        .claim(orgId, new ClaimCommand(order.id(), customer, nextRef(), null, null))
        .transaction()
        .getId();
  }

  private com.loai.inventory.domain.model.SalesOrder orderOf(UUID orgId, Order order) {
    return new SalesOrderRepositoryFactoryImpl()
        .create(dsl)
        .findById(orgId, order.id())
        .orElseThrow();
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
        .set(APP_USER.DISPLAY_NAME, "Sara")
        .set(APP_USER.ACTOR_TYPE, com.loai.inventory.repository.generated.enums.ActorType.USER)
        .execute();
    return id;
  }

  private UUID createCustomer(UUID orgId) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.NAME, "Mona Adel")
        .set(CUSTOMER.EMAIL, id + "@shop.test")
        .execute();
    return id;
  }

  private Order seedPendingOrder(
      UUID orgId, UUID customer, String grandTotal, String prepaid, OffsetDateTime expiresAt) {
    UUID orderId = UUID.randomUUID();
    String number = "SO-2026-" + String.format("%05d", orderSeq.getAndIncrement());
    dsl.insertInto(SALES_ORDER)
        .set(SALES_ORDER.ID, orderId)
        .set(SALES_ORDER.ORG_ID, orgId)
        .set(SALES_ORDER.CUSTOMER_ID, customer)
        .set(SALES_ORDER.ORDER_NUMBER, number)
        .set(SALES_ORDER.CHANNEL, OrderChannel.ONLINE)
        .set(SALES_ORDER.STATUS, OrderStatus.PENDING_PAYMENT)
        .set(SALES_ORDER.SUBTOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.GRAND_TOTAL, new BigDecimal(grandTotal))
        .set(SALES_ORDER.PREPAID_AMOUNT, new BigDecimal(prepaid))
        .set(SALES_ORDER.CURRENCY, "EGP")
        .set(SALES_ORDER.EXPIRES_AT, expiresAt)
        .execute();
    return new Order(orderId, number);
  }

  private String orderStatus(UUID orderId) {
    return dsl.select(SALES_ORDER.STATUS)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.STATUS)
        .getLiteral();
  }

  private OffsetDateTime orderExpiresAt(UUID orderId) {
    return dsl.select(SALES_ORDER.EXPIRES_AT)
        .from(SALES_ORDER)
        .where(SALES_ORDER.ID.eq(orderId))
        .fetchOne(SALES_ORDER.EXPIRES_AT);
  }

  private String storedStatus(UUID txnId) {
    return dsl.select(PAYMENT_TRANSACTION.VERIFICATION_STATUS)
        .from(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ID.eq(txnId))
        .fetchOne(PAYMENT_TRANSACTION.VERIFICATION_STATUS)
        .name();
  }

  private String storedNote(UUID txnId) {
    return dsl.select(PAYMENT_TRANSACTION.NOT_FOUND_NOTE)
        .from(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ID.eq(txnId))
        .fetchOne(PAYMENT_TRANSACTION.NOT_FOUND_NOTE);
  }

  private int notificationCount() {
    return dsl.fetchCount(
        dsl.selectFrom(NOTIFICATION).where(NOTIFICATION.TYPE.eq("PAYMENT_NOT_FOUND")));
  }

  private int txnCount(UUID orgId) {
    return dsl.fetchCount(
        dsl.selectFrom(PAYMENT_TRANSACTION).where(PAYMENT_TRANSACTION.ORG_ID.eq(orgId)));
  }
}
