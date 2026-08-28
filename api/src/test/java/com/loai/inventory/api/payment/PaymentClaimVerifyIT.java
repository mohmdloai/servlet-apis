package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.INVENTORY;
import static com.loai.inventory.repository.generated.Tables.INVENTORY_RESERVATION;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.PRODUCT;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.api.support.TestWiring;
import com.loai.inventory.common.exception.ClaimPendingException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.domain.model.PaymentVerificationStatus;
import com.loai.inventory.domain.repository.PaymentTransactionRepository.ListFilter;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryLogRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryRepositoryFactoryImpl;
import com.loai.inventory.repository.InventoryReservationRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgHealthRepositoryImpl;
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
import com.loai.inventory.repository.generated.enums.ReservationStatus;
import com.loai.inventory.service.OrderCancellationService;
import com.loai.inventory.service.OrderExpiryService;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.ClaimCommand;
import com.loai.inventory.service.PaymentTransactionService.ClaimResult;
import com.loai.inventory.service.PaymentTransactionService.TransactionPage;
import com.loai.inventory.service.PaymentTransactionService.VerifyClaimCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
import com.loai.inventory.service.RefundService;
import com.loai.inventory.service.ReservationService;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Integration coverage for "verify the claim, don't re-record it" ({@code
 * stories/payment_claim_verify.md}, phase 1): a shopper claim stores the order it names and extends
 * its hold; a manager verifies that row by id and it reconciles exactly as the record path does;
 * the record path refuses to mint a phantom beside an open claim; the queue is ordered by the
 * order's clock and enriched; expiry and cancellation close open claims.
 */
@Testcontainers
class PaymentClaimVerifyIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static PaymentTransactionService service;
  static OrderExpiryService expiryService;
  static OrderCancellationService cancellationService;

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
    ReservationService reservationService =
        new ReservationService(
            new InventoryRepositoryFactoryImpl(),
            new InventoryReservationRepositoryFactoryImpl(),
            new InventoryLogRepositoryFactoryImpl());
    expiryService =
        new OrderExpiryService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            reservationService,
            new PaymentTransactionRepositoryFactoryImpl());
    cancellationService =
        new OrderCancellationService(
            dsl,
            new SalesOrderRepositoryFactoryImpl(),
            new PaymentRepositoryFactoryImpl(),
            new FulfillmentRepositoryFactoryImpl(),
            reservationService,
            refundService,
            TestWiring.notificationService(dsl),
            TestWiring.magicLinkService(dsl),
            new PaymentTransactionRepositoryFactoryImpl());
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
        "TRUNCATE notification, payment, payment_transaction, inventory_reservation,"
            + " sales_order_line, sales_order, inventory, product, customer, app_user, org"
            + " RESTART IDENTITY CASCADE");
  }

  // the claim keeps the order open

  @Test
  void claim_storesTheClaimedOrder_andExtendsTheHoldOnce() {
    UUID orgId = createOrg("acme");
    UUID customer = createCustomer(orgId, "Mona Adel");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", now.plusHours(1));
    UUID reservation = seedLineWithReservation(orgId, order.id(), now.plusHours(1));
    String reference = nextRef();

    ClaimResult first =
        service.claim(orgId, new ClaimCommand(order.id(), customer, reference, null, "sent 8am"));

    assertTrue(first.inserted());
    assertEquals(order.id(), first.transaction().getClaimedSalesOrderId(), "the order is stored");
    OffsetDateTime held = orderExpiresAt(order.id());
    assertNotNull(held);
    assertTrue(
        Duration.between(now.plus(PaymentTransactionService.CLAIM_HOLD), held).abs().toMinutes()
            < 2,
        "hold moved to now + 48h, was " + held);
    assertEquals(held, reservationExpiresAt(reservation), "the V19 mirror moved with the order");

    // A re-filed identical reference is the same claim: nothing stacks.
    ClaimResult replay =
        service.claim(orgId, new ClaimCommand(order.id(), customer, reference, null, null));
    assertFalse(replay.inserted());
    assertEquals(held, orderExpiresAt(order.id()), "no second 48h on a replay");
  }

  @Test
  void claim_neverShortensALongerHold() {
    UUID orgId = createOrg("acme");
    UUID customer = createCustomer(orgId, "Mona Adel");
    OffsetDateTime far = OffsetDateTime.now(ZoneOffset.UTC).plusHours(72).withNano(0);
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", far);

    service.claim(orgId, new ClaimCommand(order.id(), customer, nextRef(), null, null));

    assertEquals(far.toInstant(), orderExpiresAt(order.id()).toInstant(), "max(current, +48h)");
  }

  @Test
  void claim_onAnExpiredOrder_isRecordedButExtendsNothing() {
    UUID orgId = createOrg("acme");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    dsl.update(SALES_ORDER)
        .set(SALES_ORDER.STATUS, OrderStatus.EXPIRED)
        .where(SALES_ORDER.ID.eq(order.id()))
        .execute();

    ClaimResult result =
        service.claim(orgId, new ClaimCommand(order.id(), customer, nextRef(), null, null));

    assertTrue(result.inserted());
    assertEquals("EXPIRED", orderStatus(order.id()), "an expired order has no hold to extend");
    assertNull(orderExpiresAt(order.id()));
  }

  // verify by id

  @Test
  void verifyClaim_exactCover_matchesAndFlipsOrderPaid() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID claimId = fileClaim(orgId, order, customer);

    VerifyResult result =
        service.verifyClaim(orgId, claimId, new VerifyClaimCommand(null, null, null), manager);

    assertFalse(result.replay());
    assertEquals("MATCHED", result.reconciliationStatus().name());
    assertEquals("VERIFIED", result.transaction().getVerificationStatus().name());
    assertEquals(manager, result.transaction().getVerifiedBy());
    assertNotNull(result.payment());
    assertEquals(order.id(), result.payment().getSalesOrderId());
    assertEquals("PAID", orderStatus(order.id()));
    assertNull(orderExpiresAt(order.id()), "a paid order has no hold");
    assertEquals(1, txnCount(orgId), "one claim, one transaction — nothing minted beside it");
    // The verify response names the verifier.
    assertEquals("Sara", service.contextOf(orgId, result.transaction()).verifiedByName());
  }

  @Test
  void verifyClaim_bankAmountOverride_reconcilesUnderpaidOrOverpaid() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");

    Order under = seedPendingOrder(orgId, customer, "500.00", "0.00", null);
    UUID underClaim = fileClaim(orgId, under, customer);
    VerifyResult underpaid =
        service.verifyClaim(
            orgId,
            underClaim,
            new VerifyClaimCommand(new BigDecimal("300.00"), null, null),
            manager);
    assertEquals("UNDERPAID", underpaid.reconciliationStatus().name());
    assertEquals(0, new BigDecimal("300.00").compareTo(underpaid.transaction().getAmount()));
    assertEquals(0, new BigDecimal("300.00").compareTo(storedAmount(underClaim)), "persisted");
    assertEquals("PENDING_PAYMENT", orderStatus(under.id()));

    Order over = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID overClaim = fileClaim(orgId, over, customer);
    VerifyResult overpaid =
        service.verifyClaim(
            orgId,
            overClaim,
            new VerifyClaimCommand(new BigDecimal("300.00"), null, null),
            manager);
    assertEquals("OVERPAID", overpaid.reconciliationStatus().name());
    assertEquals("PAID", orderStatus(over.id()));
    // The payment is created for the full bank figure, fully unallocated — invoice issuance at
    // delivery allocates only up to the invoice total, leaving the 50.00 excess for a refund.
    assertEquals(0, new BigDecimal("300.00").compareTo(overpaid.payment().getUnallocatedAmount()));
    assertEquals(0, new BigDecimal("300.00").compareTo(storedAmount(overClaim)));
  }

  @Test
  void verifyClaim_replay_returnsThePriorPaymentAndOrder() {
    UUID orgId = createOrg("acme");
    UUID sara = createUser("sara@acme.test", "Sara");
    UUID omar = createUser("omar@acme.test", "Omar");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID claimId = fileClaim(orgId, order, customer);

    VerifyResult first = service.verifyClaim(orgId, claimId, null, sara);
    VerifyResult second = service.verifyClaim(orgId, claimId, null, omar);

    assertTrue(second.replay(), "two managers, one claim: the second lands on a replay");
    assertEquals(first.payment().getId(), second.payment().getId());
    assertEquals(order.id(), second.order().getId());
    assertEquals(sara, second.transaction().getVerifiedBy(), "the first verifier stands");
    assertEquals(1, txnCount(orgId));
  }

  @Test
  void verifyClaim_abandoned_is409() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID claimId = fileClaim(orgId, order, customer);
    setStatus(claimId, PaymentVerificationStatus.ABANDONED);

    assertThrows(ConflictException.class, () -> service.verifyClaim(orgId, claimId, null, manager));
    assertEquals("PENDING_PAYMENT", orderStatus(order.id()));
  }

  @Test
  void verifyClaim_notFound_canStillBeVerified() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID claimId = fileClaim(orgId, order, customer);
    setStatus(claimId, PaymentVerificationStatus.NOT_FOUND);
    dsl.update(PAYMENT_TRANSACTION)
        .set(PAYMENT_TRANSACTION.NOT_FOUND_REASON, "NO_TRANSFER")
        .where(PAYMENT_TRANSACTION.ID.eq(claimId))
        .execute();

    VerifyResult result = service.verifyClaim(orgId, claimId, null, manager);

    assertEquals("MATCHED", result.reconciliationStatus().name());
    assertNull(result.transaction().getNotFoundReason(), "cleared: the money is there after all");
    assertEquals("PAID", orderStatus(order.id()));
  }

  @Test
  void verifyClaim_whenOrderPaid_abandonsTheOtherOpenClaims() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID first = fileClaim(orgId, order, customer);
    UUID duplicate = fileClaim(orgId, order, customer); // re-filed with a mistyped reference
    UUID notFound = fileClaim(orgId, order, customer);
    setStatus(notFound, PaymentVerificationStatus.NOT_FOUND);

    service.verifyClaim(orgId, first, null, manager);

    assertEquals(PaymentVerificationStatus.VERIFIED, status(first));
    assertEquals(PaymentVerificationStatus.ABANDONED, status(duplicate));
    assertEquals(PaymentVerificationStatus.ABANDONED, status(notFound));
  }

  @Test
  void verifyClaim_underpaid_keepsSiblingsOpen() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "500.00", "0.00", null);
    UUID first = fileClaim(orgId, order, customer);
    UUID topUp = fileClaim(orgId, order, customer);

    service.verifyClaim(
        orgId, first, new VerifyClaimCommand(new BigDecimal("200.00"), null, null), manager);

    assertEquals("PENDING_PAYMENT", orderStatus(order.id()));
    assertEquals(
        PaymentVerificationStatus.UNVERIFIED,
        status(topUp),
        "the order is still owed money — the second claim may be the top-up");
  }

  @Test
  void verifyClaim_orderNoLongerPending_isOrphanWithTheOrderNamed() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID claimId = fileClaim(orgId, order, customer);
    dsl.update(SALES_ORDER)
        .set(SALES_ORDER.STATUS, OrderStatus.EXPIRED)
        .where(SALES_ORDER.ID.eq(order.id()))
        .execute();

    VerifyResult result = service.verifyClaim(orgId, claimId, null, manager);

    assertEquals("ORPHAN", result.reconciliationStatus().name());
    assertEquals("VERIFIED", result.transaction().getVerificationStatus().name());
    assertNotNull(result.order(), "the order rides along so the client can say why");
    assertEquals("EXPIRED", result.order().getStatus().name());
    assertNull(result.payment());
  }

  // the record path's guard

  @Test
  void record_newReferenceAgainstOrderWithOpenClaim_is409ClaimPending_andMintsNothing() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    String proofKey = ObjectStorage.paymentProofKeyPrefix(orgId, order.id()) + "receipt.png";
    UUID claimId =
        service
            .claim(orgId, new ClaimCommand(order.id(), customer, nextRef(), proofKey, null))
            .transaction()
            .getId();

    ClaimPendingException ex =
        assertThrows(
            ClaimPendingException.class,
            () ->
                service.verify(
                    orgId, recordCommand(nextRef(), "250.00", order, Set.of()), manager));

    assertEquals(409, ex.getStatusCode());
    assertEquals(1, ex.getClaims().size());
    assertEquals(claimId, ex.getClaims().get(0).id());
    assertTrue(ex.getClaims().get(0).hasProof());
    assertEquals(1, txnCount(orgId), "the phantom row rolled back with the transaction");
    assertEquals("PENDING_PAYMENT", orderStatus(order.id()));
    assertEquals(PaymentVerificationStatus.UNVERIFIED, status(claimId), "the claim is untouched");
  }

  @Test
  void record_withEveryClaimAcknowledged_proceeds_andAuditsTheIds() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID claimId = fileClaim(orgId, order, customer);
    String separateRef = nextRef();

    VerifyResult result =
        service.verify(
            orgId, recordCommand(separateRef, "250.00", order, Set.of(claimId)), manager);

    assertEquals("MATCHED", result.reconciliationStatus().name());
    assertEquals("PAID", orderStatus(order.id()));
    assertEquals(2, txnCount(orgId));
    String audit = rawPayload(result.transaction().getId());
    assertNotNull(audit);
    assertTrue(audit.contains(claimId.toString()), audit);
    assertEquals(
        PaymentVerificationStatus.ABANDONED,
        status(claimId),
        "the order is paid through the separate transfer — the claim can no longer be true");
  }

  @Test
  void record_acknowledgingOnlySomeClaims_isStill409() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID a = fileClaim(orgId, order, customer);
    fileClaim(orgId, order, customer);

    ClaimPendingException ex =
        assertThrows(
            ClaimPendingException.class,
            () ->
                service.verify(
                    orgId, recordCommand(nextRef(), "250.00", order, Set.of(a)), manager));
    assertEquals(2, ex.getClaims().size(), "every open claim is listed, acknowledged or not");
    assertEquals(2, txnCount(orgId));
  }

  @Test
  void record_sameReferenceAsTheClaim_verifiesTheClaim_noGuard() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    String reference = nextRef();
    UUID claimId =
        service
            .claim(orgId, new ClaimCommand(order.id(), customer, reference, null, null))
            .transaction()
            .getId();

    VerifyResult result =
        service.verify(orgId, recordCommand(reference, "250.00", order, Set.of()), manager);

    assertEquals(claimId, result.transaction().getId(), "the claim's own row, verified");
    assertEquals(order.id(), result.transaction().getClaimedSalesOrderId());
    assertEquals("MATCHED", result.reconciliationStatus().name());
    assertEquals(1, txnCount(orgId));
  }

  @Test
  void record_notFoundClaimsDoNotBlock_andOrphanPathIsUntouched() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID claimId = fileClaim(orgId, order, customer);
    setStatus(claimId, PaymentVerificationStatus.NOT_FOUND);

    // The manager already looked for the NOT_FOUND claim's reference and found nothing — a
    // different transfer against the order is exactly what "can't find it" anticipates.
    VerifyResult matched =
        service.verify(orgId, recordCommand(nextRef(), "250.00", order, Set.of()), manager);
    assertEquals("MATCHED", matched.reconciliationStatus().name());

    // No order named → the deliberate orphan record, guard never consulted.
    Order other = seedPendingOrder(orgId, customer, "100.00", "0.00", null);
    fileClaim(orgId, other, customer);
    VerifyResult orphan =
        service.verify(
            orgId,
            new VerifyCommand(
                PaymentProvider.INSTAPAY_MANUAL,
                nextRef(),
                new BigDecimal("100.00"),
                "EGP",
                null,
                null,
                null,
                null,
                null,
                null),
            manager);
    assertEquals("ORPHAN", orphan.reconciliationStatus().name());
  }

  @Test
  void record_referenceOfAnAbandonedClaim_is409() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    String reference = nextRef();
    UUID claimId =
        service
            .claim(orgId, new ClaimCommand(order.id(), customer, reference, null, null))
            .transaction()
            .getId();
    setStatus(claimId, PaymentVerificationStatus.ABANDONED);

    assertThrows(
        ConflictException.class,
        () -> service.verify(orgId, recordCommand(reference, "250.00", order, Set.of()), manager));
  }

  // reads

  @Test
  void list_unverified_isOrderedByTheClaimedOrdersClock_andEnriched() {
    UUID orgId = createOrg("acme");
    UUID customer = createCustomer(orgId, "Mona Adel");
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    // Claims are filed in the order late → soon → none; the queue must come back soon → late →
    // none (nulls last), regardless of filing order.
    Order late = seedPendingOrder(orgId, customer, "100.00", "0.00", now.plusHours(60));
    Order soon = seedPendingOrder(orgId, customer, "200.00", "0.00", now.plusHours(50));
    Order never = seedPendingOrder(orgId, customer, "300.00", "0.00", now.plusHours(70));
    UUID lateClaim = fileClaim(orgId, late, customer);
    UUID soonClaim = fileClaim(orgId, soon, customer);
    UUID neverClaim = fileClaim(orgId, never, customer);
    dsl.update(SALES_ORDER)
        .setNull(SALES_ORDER.EXPIRES_AT)
        .where(SALES_ORDER.ID.eq(never.id()))
        .execute();

    TransactionPage page =
        service.list(
            orgId,
            new ListFilter(PaymentVerificationStatus.UNVERIFIED, null, null, null, null),
            0,
            20);

    assertEquals(3, page.total());
    assertEquals(
        List.of(soonClaim, lateClaim, neverClaim),
        page.items().stream().map(PaymentTransaction::getId).toList());
    assertEquals("Mona Adel", page.customers().get(customer).getName());
    assertEquals(soon.number(), page.claimedOrders().get(soon.id()).getOrderNumber());
    assertNotNull(page.claimedOrders().get(soon.id()).getExpiresAt());
  }

  @Test
  void list_bySalesOrderId_returnsThatOrdersClaims_andComposesWithStatus() {
    UUID orgId = createOrg("acme");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order a = seedPendingOrder(orgId, customer, "100.00", "0.00", null);
    Order b = seedPendingOrder(orgId, customer, "100.00", "0.00", null);
    UUID aOpen = fileClaim(orgId, a, customer);
    UUID aNotFound = fileClaim(orgId, a, customer);
    setStatus(aNotFound, PaymentVerificationStatus.NOT_FOUND);
    fileClaim(orgId, b, customer);

    TransactionPage all =
        service.list(orgId, new ListFilter(null, null, null, null, null, a.id()), 0, 20);
    assertEquals(2, all.total());

    TransactionPage open =
        service.list(
            orgId,
            new ListFilter(PaymentVerificationStatus.UNVERIFIED, null, null, null, null, a.id()),
            0,
            20);
    assertEquals(1, open.total());
    assertEquals(aOpen, open.items().get(0).getId());
  }

  @Test
  void get_carriesTheShopper_theClaimedOrder_andTheVerifiersName() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID claimId =
        service
            .claim(
                orgId, new ClaimCommand(order.id(), customer, nextRef(), null, "from my brother"))
            .transaction()
            .getId();

    var before = service.get(orgId, claimId);
    assertEquals("Mona Adel", before.customer().getName());
    assertEquals("+201001234471", before.customer().getPhoneE164());
    assertEquals(order.number(), before.claimedOrder().getOrderNumber());
    assertEquals("from my brother", before.transaction().getCustomerNote());
    assertNull(before.verifiedByName());
    assertNull(before.order(), "no payment yet, so no disposition order");

    service.verifyClaim(orgId, claimId, null, manager);
    var after = service.get(orgId, claimId);
    assertEquals("Sara", after.verifiedByName());
    assertEquals(order.id(), after.order().getId(), "where the money went");
    assertEquals(order.id(), after.claimedOrder().getId(), "what the shopper said");
  }

  // the system closes what can no longer be verified

  @Test
  void expiry_abandonsTheOrdersOpenClaims() {
    UUID orgId = createOrg("acme");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID open = fileClaim(orgId, order, customer);
    UUID notFound = fileClaim(orgId, order, customer);
    setStatus(notFound, PaymentVerificationStatus.NOT_FOUND);

    assertEquals(1, expiryService.expireOnePending(order.id()));

    assertEquals("EXPIRED", orderStatus(order.id()));
    assertEquals(PaymentVerificationStatus.ABANDONED, status(open));
    assertEquals(PaymentVerificationStatus.ABANDONED, status(notFound));
  }

  @Test
  void cancel_abandonsTheOrdersOpenClaims() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID open = fileClaim(orgId, order, customer);

    cancellationService.cancel(orgId, order.id(), "changed mind", null, manager, true);

    assertEquals("CANCELLED", orderStatus(order.id()));
    assertEquals(PaymentVerificationStatus.ABANDONED, status(open));
  }

  @Test
  void health_countsClaimsToVerify() {
    UUID orgId = createOrg("acme");
    UUID manager = createUser("sara@acme.test", "Sara");
    UUID customer = createCustomer(orgId, "Mona Adel");
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00", null);
    UUID claimId = fileClaim(orgId, order, customer);
    var health = new OrgHealthRepositoryImpl(dsl);

    assertEquals(1, health.health(orgId).claimsToVerify());
    service.verifyClaim(orgId, claimId, null, manager);
    assertEquals(0, health.health(orgId).claimsToVerify());
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

  private static VerifyCommand recordCommand(
      String reference, String amount, Order order, Set<UUID> acknowledged) {
    return new VerifyCommand(
        PaymentProvider.INSTAPAY_MANUAL,
        reference,
        new BigDecimal(amount),
        "EGP",
        null,
        order.number(),
        null,
        null,
        null,
        null,
        acknowledged);
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

  private UUID createUser(String email, String displayName) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "-" + email)
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.DISPLAY_NAME, displayName)
        .set(APP_USER.ACTOR_TYPE, com.loai.inventory.repository.generated.enums.ActorType.USER)
        .execute();
    return id;
  }

  private UUID createCustomer(UUID orgId, String name) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.NAME, name)
        .set(CUSTOMER.EMAIL, id + "@shop.test")
        .set(CUSTOMER.PHONE, "01001234471")
        .set(CUSTOMER.PHONE_E164, "+201001234471")
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

  /** One product + one line + one ACTIVE reservation carrying the V19 expiry mirror. */
  private UUID seedLineWithReservation(UUID orgId, UUID orderId, OffsetDateTime expiresAt) {
    UUID productId = UUID.randomUUID();
    dsl.insertInto(PRODUCT)
        .set(PRODUCT.ID, productId)
        .set(PRODUCT.ORG_ID, orgId)
        .set(PRODUCT.NAME, "widget")
        .set(PRODUCT.SKU, "W-" + productId)
        .set(PRODUCT.BASE_PRICE, new BigDecimal("10.00"))
        .execute();
    dsl.insertInto(INVENTORY)
        .set(INVENTORY.ORG_ID, orgId)
        .set(INVENTORY.PRODUCT_ID, productId)
        .set(INVENTORY.STOCK_QTY, 5)
        .set(INVENTORY.RESERVED_QTY, 1)
        .set(INVENTORY.VERSION, 0L)
        .execute();
    UUID lineId = UUID.randomUUID();
    dsl.insertInto(SALES_ORDER_LINE)
        .set(SALES_ORDER_LINE.ID, lineId)
        .set(SALES_ORDER_LINE.SALES_ORDER_ID, orderId)
        .set(SALES_ORDER_LINE.PRODUCT_ID, productId)
        .set(SALES_ORDER_LINE.DESCRIPTION, "line")
        .set(SALES_ORDER_LINE.QUANTITY, 1)
        .set(SALES_ORDER_LINE.UNIT_PRICE, new BigDecimal("10.00"))
        .set(SALES_ORDER_LINE.LINE_SUBTOTAL, new BigDecimal("10.00"))
        .set(SALES_ORDER_LINE.LINE_TOTAL, new BigDecimal("10.00"))
        .execute();
    UUID reservationId = UUID.randomUUID();
    dsl.insertInto(INVENTORY_RESERVATION)
        .set(INVENTORY_RESERVATION.ID, reservationId)
        .set(INVENTORY_RESERVATION.ORG_ID, orgId)
        .set(INVENTORY_RESERVATION.PRODUCT_ID, productId)
        .set(INVENTORY_RESERVATION.SALES_ORDER_LINE_ID, lineId)
        .set(INVENTORY_RESERVATION.QUANTITY, 1)
        .set(INVENTORY_RESERVATION.STATUS, ReservationStatus.ACTIVE)
        .set(INVENTORY_RESERVATION.EXPIRES_AT, expiresAt)
        .execute();
    return reservationId;
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

  private OffsetDateTime reservationExpiresAt(UUID reservationId) {
    return dsl.select(INVENTORY_RESERVATION.EXPIRES_AT)
        .from(INVENTORY_RESERVATION)
        .where(INVENTORY_RESERVATION.ID.eq(reservationId))
        .fetchOne(INVENTORY_RESERVATION.EXPIRES_AT);
  }

  private PaymentVerificationStatus status(UUID txnId) {
    return PaymentVerificationStatus.valueOf(
        dsl.select(PAYMENT_TRANSACTION.VERIFICATION_STATUS)
            .from(PAYMENT_TRANSACTION)
            .where(PAYMENT_TRANSACTION.ID.eq(txnId))
            .fetchOne(PAYMENT_TRANSACTION.VERIFICATION_STATUS)
            .name());
  }

  private void setStatus(UUID txnId, PaymentVerificationStatus status) {
    dsl.update(PAYMENT_TRANSACTION)
        .set(
            PAYMENT_TRANSACTION.VERIFICATION_STATUS,
            com.loai.inventory.repository.generated.enums.PaymentVerificationStatus.valueOf(
                status.name()))
        .where(PAYMENT_TRANSACTION.ID.eq(txnId))
        .execute();
  }

  private BigDecimal storedAmount(UUID txnId) {
    return dsl.select(PAYMENT_TRANSACTION.AMOUNT)
        .from(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ID.eq(txnId))
        .fetchOne(PAYMENT_TRANSACTION.AMOUNT);
  }

  private String rawPayload(UUID txnId) {
    var jsonb =
        dsl.select(PAYMENT_TRANSACTION.RAW_PAYLOAD)
            .from(PAYMENT_TRANSACTION)
            .where(PAYMENT_TRANSACTION.ID.eq(txnId))
            .fetchOne(PAYMENT_TRANSACTION.RAW_PAYLOAD);
    return jsonb == null ? null : jsonb.data();
  }

  private int txnCount(UUID orgId) {
    return dsl.fetchCount(
        dsl.selectFrom(PAYMENT_TRANSACTION).where(PAYMENT_TRANSACTION.ORG_ID.eq(orgId)));
  }
}
