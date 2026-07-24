package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.storage.ObjectStorage;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.repository.CreditNoteRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentRepositoryFactoryImpl;
import com.loai.inventory.repository.PaymentTransactionRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundAllocationRepositoryFactoryImpl;
import com.loai.inventory.repository.RefundRepositoryFactoryImpl;
import com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl;
import com.loai.inventory.repository.generated.enums.OrderChannel;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentTransactionService;
import com.loai.inventory.service.PaymentTransactionService.ClaimCommand;
import com.loai.inventory.service.PaymentTransactionService.ClaimResult;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyResult;
import com.loai.inventory.service.RefundService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
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
 * Integration coverage for the shopper payment-proof claim slice (roadmap item 2, {@code
 * stories/shopper_payment_proof_claim.md}). Drives {@link PaymentTransactionService#claim} against
 * the real jOOQ repositories: a claim records an UNVERIFIED CREDIT transaction at the order's
 * outstanding, idempotent on the reference, prefix-guarding the proof key — and the recorded row
 * flows through the unchanged staff {@code verify} path.
 */
@Testcontainers
class ShopperPaymentClaimIT {

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
            com.loai.inventory.api.support.TestWiring.notificationService(dsl),
            com.loai.inventory.api.support.TestWiring.magicLinkService(dsl));
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
            refundService);
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
        "TRUNCATE payment, payment_transaction, sales_order_line, sales_order, customer, app_user,"
            + " org RESTART IDENTITY CASCADE");
  }

  // scenarios

  @Test
  void claim_recordsUnverifiedCreditAtOutstanding_withProofKey() {
    UUID orgId = createOrg("acme");
    UUID customer = createCustomer(orgId);
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00");
    String reference = nextRef();
    String proofKey = ObjectStorage.paymentProofKeyPrefix(orgId, order.id()) + "receipt.png";

    ClaimResult result =
        service.claim(
            orgId, new ClaimCommand(order.id(), customer, reference, proofKey, "sent 8am"));

    assertTrue(result.inserted());
    var txn = result.transaction();
    assertEquals("UNVERIFIED", txn.getVerificationStatus().name());
    assertEquals("CREDIT", txn.getDirection().name());
    assertEquals(0, new BigDecimal("250.00").compareTo(txn.getAmount()));
    assertEquals(reference, txn.getProviderRef());
    assertEquals(customer, txn.getClaimedByCustomerId());
    assertEquals(proofKey, txn.getProofObjectKey());
    // Surfaces in the staff UNVERIFIED queue and persisted the proof key.
    assertEquals(1, unverifiedCount(orgId));
    assertEquals(proofKey, storedProofKey(txn.getId()));
  }

  @Test
  void claim_isIdempotentOnReference() {
    UUID orgId = createOrg("acme");
    UUID customer = createCustomer(orgId);
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00");
    String reference = nextRef();

    ClaimResult first =
        service.claim(orgId, new ClaimCommand(order.id(), customer, reference, null, null));
    ClaimResult replay =
        service.claim(orgId, new ClaimCommand(order.id(), customer, reference, null, null));

    assertTrue(first.inserted());
    assertTrue(!replay.inserted());
    assertEquals(first.transaction().getId(), replay.transaction().getId());
    assertEquals(1, txnCount(orgId)); // no second row
  }

  @Test
  void claim_fullyPaidOrder_is409() {
    UUID orgId = createOrg("acme");
    UUID customer = createCustomer(orgId);
    Order order = seedPendingOrder(orgId, customer, "250.00", "250.00"); // outstanding 0

    assertThrows(
        ConflictException.class,
        () -> service.claim(orgId, new ClaimCommand(order.id(), customer, nextRef(), null, null)));
    assertEquals(0, txnCount(orgId));
  }

  @Test
  void claim_foreignProofKey_is400() {
    UUID orgId = createOrg("acme");
    UUID customer = createCustomer(orgId);
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00");
    // A key scoped to a different order — must not attach.
    String foreign = ObjectStorage.paymentProofKeyPrefix(orgId, UUID.randomUUID()) + "x.png";

    assertThrows(
        ValidationException.class,
        () ->
            service.claim(orgId, new ClaimCommand(order.id(), customer, nextRef(), foreign, null)));
    assertEquals(0, txnCount(orgId));
  }

  @Test
  void claim_thenStaffVerify_reconcilesTheShoppersRow() {
    UUID orgId = createOrg("acme");
    UUID admin = createUser("admin@acme.test");
    UUID customer = createCustomer(orgId);
    Order order = seedPendingOrder(orgId, customer, "250.00", "0.00");
    String reference = nextRef();

    // Shopper files the claim (UNVERIFIED)...
    ClaimResult claim =
        service.claim(orgId, new ClaimCommand(order.id(), customer, reference, null, null));
    assertEquals("UNVERIFIED", claim.transaction().getVerificationStatus().name());

    // ...staff verify the SAME reference against the order: the existing row flows through the
    // unchanged verify path to MATCHED, and the order flips PAID — no second transaction.
    VerifyResult verified =
        service.verify(
            orgId,
            new VerifyCommand(
                PaymentProvider.INSTAPAY_MANUAL,
                reference,
                new BigDecimal("250.00"),
                "EGP",
                null,
                order.number(),
                customer,
                null,
                null,
                null),
            admin);

    assertEquals("MATCHED", verified.reconciliationStatus().name());
    assertEquals("PAID", orderStatus(order.id()));
    assertEquals(1, txnCount(orgId));
  }

  // helpers

  private String nextRef() {
    return "IPN-" + refSeq.getAndIncrement();
  }

  private record Order(UUID id, String number) {}

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
    dsl.insertInto(com.loai.inventory.repository.generated.Tables.APP_USER)
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.ID, id)
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.EMAIL, id + "-" + email)
        .set(com.loai.inventory.repository.generated.Tables.APP_USER.PASSWORD_HASH, "x")
        .set(
            com.loai.inventory.repository.generated.Tables.APP_USER.ACTOR_TYPE,
            com.loai.inventory.repository.generated.enums.ActorType.USER)
        .execute();
    return id;
  }

  private UUID createCustomer(UUID orgId) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId)
        .set(CUSTOMER.NAME, "Shopper")
        .set(CUSTOMER.EMAIL, id + "@shop.test")
        .execute();
    return id;
  }

  private Order seedPendingOrder(UUID orgId, UUID customer, String grandTotal, String prepaid) {
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

  private int txnCount(UUID orgId) {
    return dsl.fetchCount(
        dsl.selectFrom(PAYMENT_TRANSACTION).where(PAYMENT_TRANSACTION.ORG_ID.eq(orgId)));
  }

  private int unverifiedCount(UUID orgId) {
    return dsl.fetchCount(
        dsl.selectFrom(PAYMENT_TRANSACTION)
            .where(
                PAYMENT_TRANSACTION
                    .ORG_ID
                    .eq(orgId)
                    .and(
                        PAYMENT_TRANSACTION.VERIFICATION_STATUS.eq(
                            com.loai.inventory.repository.generated.enums.PaymentVerificationStatus
                                .UNVERIFIED))));
  }

  private String storedProofKey(UUID txnId) {
    return dsl.select(PAYMENT_TRANSACTION.PROOF_OBJECT_KEY)
        .from(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ID.eq(txnId))
        .fetchOne(PAYMENT_TRANSACTION.PROOF_OBJECT_KEY);
  }
}
