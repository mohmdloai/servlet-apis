package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.ORG_PAYMOB_CONFIG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_INTENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static com.loai.inventory.repository.generated.Tables.SALES_ORDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.PaymentIntent;
import com.loai.inventory.domain.model.PaymentProvider;
import com.loai.inventory.domain.model.PaymentReconciliationStatus;
import com.loai.inventory.domain.model.PaymentTransaction;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.service.PaymentService;
import com.loai.inventory.service.PaymentService.CurrencyMismatch;
import com.loai.inventory.service.PaymentService.OrderRef;
import com.loai.inventory.service.PaymentTransactionService.ClaimCommand;
import com.loai.inventory.service.PaymentTransactionService.VerifyCommand;
import com.loai.inventory.service.PaymobWebhookService.Outcome;
import com.loai.inventory.service.PaymobWebhookService.Outcome.Kind;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * Epic slice 2, the webhook ({@code stories/paymob_card_checkout.md}): every acceptance criterion
 * that involves Paymob's callback, driven through {@code PaymobWebhookService} with bodies signed
 * the way Paymob signs them. The three traps the slice exists to get right are each pinned: the
 * pinned HMAC list (a forged/tampered body is a 400 and writes nothing), no write on a pending
 * callback (asserted as zero rows, not assumed), and the redirect never being authoritative (there
 * is no redirect path in this class to test — the webhook is the only writer).
 */
@Testcontainers
class PaymobWebhookIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static PaymobFixture fx;

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
    fx = new PaymobFixture(dsl);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void fresh() {
    fx.truncate();
  }

  /** One connected org with one customer and one 250.00 order awaiting payment, intent minted. */
  private record Scene(UUID org, UUID customer, PaymobFixture.Order order, PaymentIntent intent) {}

  private Scene scene() {
    UUID org = fx.createOrg("acme");
    fx.connect(org);
    UUID customer = fx.createCustomer(org, "Nadia Hassan", "nadia@example.test", "+201001234567");
    PaymobFixture.Order order = fx.seedPendingOrder(org, customer, "250.00");
    PaymentIntent intent = fx.mintIntent(org, order, customer);
    return new Scene(org, customer, order, intent);
  }

  @Test
  void signedSuccess_settlesTheOrder_oneTxnOnePaymentIntentSettled() throws Exception {
    Scene s = scene();
    ObjectNode cb = fx.callback(s.intent(), 987654321L);
    String body = fx.body(cb);

    Outcome out = fx.webhookService.handle(s.org(), body, fx.sign(body));

    assertEquals(Kind.SETTLED, out.kind());
    assertEquals("MATCHED", out.detail());
    assertEquals("PAID", fx.orderStatus(s.order().id()));
    assertEquals(0, new BigDecimal("250.00").compareTo(fx.prepaid(s.order().id())));

    assertEquals(1, fx.txnCount(s.org()));
    org.jooq.Record txn = fx.txnByRef("987654321");
    assertNotNull(txn);
    assertEquals("paymob_card", txn.get(PAYMENT_TRANSACTION.PROVIDER).getLiteral());
    assertEquals("VERIFIED", txn.get(PAYMENT_TRANSACTION.VERIFICATION_STATUS).getLiteral());
    assertNull(txn.get(PAYMENT_TRANSACTION.VERIFIED_BY), "the gateway did it — no user");
    assertNotNull(txn.get(PAYMENT_TRANSACTION.VERIFIED_AT));
    assertEquals("MATCHED", txn.get(PAYMENT_TRANSACTION.RECONCILIATION_STATUS).getLiteral());
    assertEquals(s.order().id(), txn.get(PAYMENT_TRANSACTION.CLAIMED_SALES_ORDER_ID));
    assertEquals(s.customer(), txn.get(PAYMENT_TRANSACTION.CLAIMED_BY_CUSTOMER_ID));
    assertEquals(
        fx.mapper.readTree(body),
        fx.mapper.readTree(txn.get(PAYMENT_TRANSACTION.RAW_PAYLOAD).data()),
        "raw_payload is the delivered body");

    assertEquals(1, fx.paymentCount(s.order().id()));
    assertEquals(
        "RECEIVED",
        dsl.select(PAYMENT.STATUS)
            .from(PAYMENT)
            .where(PAYMENT.SALES_ORDER_ID.eq(s.order().id()))
            .fetchOne(PAYMENT.STATUS)
            .getLiteral());

    org.jooq.Record intent = fx.intentRow(s.intent().getId());
    assertEquals("SETTLED", intent.get(PAYMENT_INTENT.STATUS));
    assertEquals("987654321", intent.get(PAYMENT_INTENT.SETTLED_TXN_REF));
  }

  /**
   * {@code stories/paymob_portal_pay.md}: an intent minted from the signed-in door settles through
   * exactly the same path — the door is not recorded on the intent and the webhook never reads it.
   */
  @Test
  void anIntentMintedFromThePortalDoor_settlesExactlyAsAPublicOne() {
    UUID org = fx.createOrg("acme");
    fx.connect(org);
    UUID customer = fx.createCustomer(org, "Nadia Hassan", "nadia@example.test", "+201001234567");
    PaymobFixture.Order order = fx.seedPendingOrder(org, customer, "250.00");
    PaymentIntent intent = fx.mintPortalIntent(org, order, customer);

    Outcome out = fx.deliver(org, fx.callback(intent, 987654322L));

    assertEquals(Kind.SETTLED, out.kind());
    assertEquals("MATCHED", out.detail());
    assertEquals("PAID", fx.orderStatus(order.id()));
    assertEquals(1, fx.txnCount(org));
    assertEquals(1, fx.paymentCount(order.id()));
    assertEquals("SETTLED", fx.intentStatus(intent.getId()));
    assertEquals(
        customer, fx.txnByRef("987654322").get(PAYMENT_TRANSACTION.CLAIMED_BY_CUSTOMER_ID));
  }

  @Test
  void sameCallbackFiveTimes_oneTxnOnePaymentOnePaid_every200() {
    Scene s = scene();
    ObjectNode cb = fx.callback(s.intent(), 111L);

    Outcome first = fx.deliver(s.org(), cb);
    assertEquals(Kind.SETTLED, first.kind());
    for (int i = 0; i < 4; i++) {
      Outcome replay = fx.deliver(s.org(), cb);
      assertEquals(Kind.REPLAYED, replay.kind());
      assertTrue(!replay.rejected(), "a replay is a 200");
    }

    assertEquals(1, fx.txnCount(s.org()));
    assertEquals(1, fx.paymentCount(s.order().id()));
    assertEquals("PAID", fx.orderStatus(s.order().id()));
    assertEquals(0, new BigDecimal("250.00").compareTo(fx.prepaid(s.order().id())));
  }

  @Test
  void tamperedAmount_signedWithTheWrongKey_is400_nothingWritten() {
    Scene s = scene();
    ObjectNode cb = fx.callback(s.intent(), 222L);
    ((ObjectNode) cb.get("obj")).put("amount_cents", 1);
    String body = fx.body(cb);

    Outcome out = fx.webhookService.handle(s.org(), body, fx.sign(body, "not-the-merchants-key"));

    assertEquals(Kind.REJECTED, out.kind());
    assertTrue(out.rejected());
    assertNothingWritten(s);
  }

  @Test
  void genuineBody_withTamperedAmountButOriginalSignature_is400() {
    Scene s = scene();
    ObjectNode cb = fx.callback(s.intent(), 223L);
    String genuineSignature = fx.sign(fx.body(cb));
    ((ObjectNode) cb.get("obj")).put("amount_cents", 1);

    Outcome out = fx.webhookService.handle(s.org(), fx.body(cb), genuineSignature);

    assertEquals(Kind.REJECTED, out.kind());
    assertNothingWritten(s);
  }

  @Test
  void absentSignature_is400_nothingWritten() {
    Scene s = scene();
    Outcome out = fx.webhookService.handle(s.org(), fx.body(fx.callback(s.intent(), 224L)), null);
    assertEquals(Kind.REJECTED, out.kind());
    assertNothingWritten(s);
  }

  @Test
  void unknownOrg_orOrgWithoutConfig_is400() {
    Scene s = scene();
    ObjectNode cb = fx.callback(s.intent(), 333L);

    Outcome unknown = fx.deliver(UUID.randomUUID(), cb);
    assertEquals(Kind.REJECTED, unknown.kind());

    UUID unconnected = fx.createOrg("other");
    Outcome noConfig = fx.deliver(unconnected, cb);
    assertEquals(Kind.REJECTED, noConfig.kind());

    assertNothingWritten(s);
  }

  @Test
  void disabledConfig_is400_nothingWritten() {
    Scene s = scene();
    dsl.update(ORG_PAYMOB_CONFIG)
        .set(ORG_PAYMOB_CONFIG.STATUS, "DISABLED")
        .where(ORG_PAYMOB_CONFIG.ORG_ID.eq(s.org()))
        .execute();

    Outcome out = fx.deliver(s.org(), fx.callback(s.intent(), 444L));

    assertEquals(Kind.REJECTED, out.kind());
    assertNothingWritten(s);
  }

  @Test
  void malformedBody_is400() {
    Scene s = scene();
    assertEquals(Kind.REJECTED, fx.webhookService.handle(s.org(), "not json", "abc").kind());
    assertEquals(
        Kind.REJECTED,
        fx.webhookService.handle(s.org(), "{\"type\":\"TRANSACTION\"}", "abc").kind());
    assertNothingWritten(s);
  }

  @Test
  void pendingCallback_is200_andWritesZeroRows() {
    // The 3DS-in-flight callback shares obj.id with the final one. Recording it would burn the
    // provider_ref: the final callback's insertIfAbsent would find the pending row and the order
    // would never settle. So: 200, and ZERO rows — asserted, not assumed.
    Scene s = scene();
    ObjectNode pending = fx.callback(s.intent(), 555L);
    ((ObjectNode) pending.get("obj")).put("pending", true);
    ((ObjectNode) pending.get("obj")).put("success", false);

    Outcome out = fx.deliver(s.org(), pending);

    assertEquals(Kind.IGNORED, out.kind());
    assertNothingWritten(s);

    // …and the final callback with the SAME obj.id then settles normally.
    Outcome finalOut = fx.deliver(s.org(), fx.callback(s.intent(), 555L));
    assertEquals(Kind.SETTLED, finalOut.kind());
    assertEquals("PAID", fx.orderStatus(s.order().id()));
  }

  @Test
  void tokenCallback_is200_andWritesZeroRows() {
    Scene s = scene();
    String body =
        "{\"type\":\"TOKEN\",\"obj\":{\"id\":1,\"token\":\"tok\",\"masked_pan\":\"xxxx\"}}";

    // A TOKEN callback signs a different field list — acknowledged without our signature check.
    Outcome out = fx.webhookService.handle(s.org(), body, "irrelevant");

    assertEquals(Kind.IGNORED, out.kind());
    assertNothingWritten(s);
  }

  @Test
  void authOnly_is200_andWritesNothing() {
    Scene s = scene();
    ObjectNode authOnly = fx.callback(s.intent(), 666L);
    ((ObjectNode) authOnly.get("obj")).put("is_auth", true).put("is_capture", false);
    assertEquals(Kind.IGNORED, fx.deliver(s.org(), authOnly).kind());
    assertNothingWritten(s);
  }

  /**
   * Slice 3 ({@code stories/paymob_card_reliability.md}): a refund child callback is money leaving
   * — a gateway-verified DEBIT under the child's own id, the original's payment reduced the way an
   * executed refund reduces it, and the order NOT un-paid.
   */
  @Test
  void refundChildCallback_recordsADebit_reducesThePayment_orderStaysPaid() {
    Scene s = scene();
    assertEquals(Kind.SETTLED, fx.deliver(s.org(), fx.callback(s.intent(), 801L)).kind());

    ObjectNode refund = fx.callback(s.intent(), 8011L);
    ((ObjectNode) refund.get("obj"))
        .put("has_parent_transaction", true)
        .put("is_refund", true)
        .put("parent_transaction", 801L);
    Outcome out = fx.deliver(s.org(), refund);

    assertEquals(Kind.REVERSED, out.kind());
    org.jooq.Record debit = fx.txnByRef("8011");
    assertNotNull(debit);
    assertEquals("DEBIT", debit.get(PAYMENT_TRANSACTION.DIRECTION).getLiteral());
    assertEquals("VERIFIED", debit.get(PAYMENT_TRANSACTION.VERIFICATION_STATUS).getLiteral());
    assertNull(debit.get(PAYMENT_TRANSACTION.VERIFIED_BY));
    assertNull(debit.get(PAYMENT_TRANSACTION.RECONCILIATION_STATUS));
    assertEquals(0, new BigDecimal("250.00").compareTo(debit.get(PAYMENT_TRANSACTION.AMOUNT)));
    assertTrue(
        debit.get(PAYMENT_TRANSACTION.RAW_PAYLOAD).data().contains("\"parent_transaction\": 801"),
        "linked to the original through raw_payload");

    org.jooq.Record payment =
        dsl.selectFrom(PAYMENT).where(PAYMENT.SALES_ORDER_ID.eq(s.order().id())).fetchOne();
    assertEquals("REFUNDED", payment.get(PAYMENT.STATUS).getLiteral());
    assertEquals(0, new BigDecimal("250.00").compareTo(payment.get(PAYMENT.REFUNDED_AMOUNT)));
    assertEquals(0, BigDecimal.ZERO.compareTo(payment.get(PAYMENT.UNALLOCATED_AMOUNT)));
    assertEquals("PAID", fx.orderStatus(s.order().id()), "never un-paid by a callback");

    assertEquals(Kind.REPLAYED, fx.deliver(s.org(), refund).kind(), "same child id → replay");
    assertEquals(2, fx.txnCount(s.org()));
  }

  @Test
  void voidThePaymentCannotAbsorb_recordsTheDebit_andLeavesThePaymentToAHuman() {
    Scene s = scene();
    assertEquals(Kind.SETTLED, fx.deliver(s.org(), fx.callback(s.intent(), 802L)).kind());
    // Most of the payment is already allocated to an invoice: only 10.00 is left unallocated.
    dsl.update(PAYMENT)
        .set(PAYMENT.UNALLOCATED_AMOUNT, new BigDecimal("10.00"))
        .where(PAYMENT.SALES_ORDER_ID.eq(s.order().id()))
        .execute();

    ObjectNode voided = fx.callback(s.intent(), 8021L);
    ((ObjectNode) voided.get("obj"))
        .put("has_parent_transaction", true)
        .put("is_void", true)
        .put("parent_transaction", 802L);
    Outcome out = fx.deliver(s.org(), voided);

    assertEquals(Kind.REVERSED, out.kind());
    assertTrue(out.detail().contains("credit note"), out.detail());
    assertNotNull(fx.txnByRef("8021"), "the money left; the DEBIT says so");
    org.jooq.Record payment =
        dsl.selectFrom(PAYMENT).where(PAYMENT.SALES_ORDER_ID.eq(s.order().id())).fetchOne();
    assertEquals(
        "RECEIVED", payment.get(PAYMENT.STATUS).getLiteral(), "untouched — a human decides");
    assertEquals(0, BigDecimal.ZERO.compareTo(payment.get(PAYMENT.REFUNDED_AMOUNT)));
    assertEquals("PAID", fx.orderStatus(s.order().id()));
  }

  @Test
  void reversalOfAnUnknownParent_recordsTheDebitOnly_andAFailedReversalWritesNothing() {
    Scene s = scene();
    ObjectNode orphanRefund = fx.callback(s.intent(), 8031L);
    ((ObjectNode) orphanRefund.get("obj"))
        .put("has_parent_transaction", true)
        .put("is_refund", true)
        .put("parent_transaction", 999999L);
    assertEquals(Kind.REVERSED, fx.deliver(s.org(), orphanRefund).kind());
    assertEquals("DEBIT", fx.txnByRef("8031").get(PAYMENT_TRANSACTION.DIRECTION).getLiteral());

    ObjectNode failedRefund = fx.callback(s.intent(), 8032L);
    ((ObjectNode) failedRefund.get("obj"))
        .put("has_parent_transaction", true)
        .put("is_refund", true)
        .put("success", false)
        .put("parent_transaction", 999999L);
    assertEquals(Kind.IGNORED, fx.deliver(s.org(), failedRefund).kind());
    assertNull(fx.txnByRef("8032"));
  }

  @Test
  void amountDisagreesWithTheIntent_recordedOrphan_orderStillPending_intentNotSettled() {
    Scene s = scene();
    ObjectNode cb = fx.callback(s.intent(), 777L);
    ((ObjectNode) cb.get("obj")).put("amount_cents", 20000); // 200.00 against a 250.00 intent
    ((ObjectNode) cb.get("obj").get("order")).put("amount_cents", 20000);

    Outcome out = fx.deliver(s.org(), cb);

    assertEquals(Kind.ORPHAN, out.kind());
    org.jooq.Record txn = fx.txnByRef("777");
    assertNotNull(txn, "money that arrived must always leave a row");
    assertEquals("VERIFIED", txn.get(PAYMENT_TRANSACTION.VERIFICATION_STATUS).getLiteral());
    assertEquals("ORPHAN", txn.get(PAYMENT_TRANSACTION.RECONCILIATION_STATUS).getLiteral());
    assertEquals(0, new BigDecimal("200.00").compareTo(txn.get(PAYMENT_TRANSACTION.AMOUNT)));
    assertEquals("PENDING_PAYMENT", fx.orderStatus(s.order().id()));
    assertEquals(0, fx.paymentCount(s.order().id()));
    assertEquals("PENDING", fx.intentStatus(s.intent().getId()));
  }

  @Test
  void signedPaymobOrderDisagreesWithTheIntent_recordedOrphan() {
    // The unsigned merchant_order_id says "intent X"; the SIGNED order.id says a different Paymob
    // order. The signed value wins: this is not X's money.
    Scene s = scene();
    ObjectNode cb = fx.callback(s.intent(), 778L);
    ((ObjectNode) cb.get("obj").get("order")).put("id", 999999999L);

    Outcome out = fx.deliver(s.org(), cb);

    assertEquals(Kind.ORPHAN, out.kind());
    assertEquals(
        "ORPHAN", fx.txnByRef("778").get(PAYMENT_TRANSACTION.RECONCILIATION_STATUS).getLiteral());
    assertEquals("PENDING_PAYMENT", fx.orderStatus(s.order().id()));
    assertEquals("PENDING", fx.intentStatus(s.intent().getId()));
  }

  @Test
  void callbackMatchingNoIntent_recordedOrphan_notLost() {
    Scene s = scene();
    ObjectNode cb = fx.callback(s.intent(), 779L);
    ((ObjectNode) cb.get("obj").get("order"))
        .put("merchant_order_id", UUID.randomUUID().toString());
    ((ObjectNode) cb.get("obj").get("payment_key_claims").get("extra"))
        .put("intent_id", UUID.randomUUID().toString());

    Outcome out = fx.deliver(s.org(), cb);

    assertEquals(Kind.ORPHAN, out.kind());
    org.jooq.Record txn = fx.txnByRef("779");
    assertEquals("ORPHAN", txn.get(PAYMENT_TRANSACTION.RECONCILIATION_STATUS).getLiteral());
    assertNull(txn.get(PAYMENT_TRANSACTION.CLAIMED_SALES_ORDER_ID));
    assertEquals("PENDING_PAYMENT", fx.orderStatus(s.order().id()));
  }

  @Test
  void secondSuccessfulTransaction_onANowPaidOrder_isOrphan_bothInTheLedger() {
    Scene s = scene();
    assertEquals(Kind.SETTLED, fx.deliver(s.org(), fx.callback(s.intent(), 801L)).kind());

    // A genuine double charge: a different Paymob transaction, same intent, same amount.
    Outcome second = fx.deliver(s.org(), fx.callback(s.intent(), 802L));

    assertEquals(Kind.ORPHAN, second.kind());
    assertEquals("PAID", fx.orderStatus(s.order().id()));
    assertEquals(0, new BigDecimal("250.00").compareTo(fx.prepaid(s.order().id())), "unchanged");
    assertEquals(2, fx.txnCount(s.org()));
    assertEquals(1, fx.paymentCount(s.order().id()));
    assertEquals(
        "MATCHED", fx.txnByRef("801").get(PAYMENT_TRANSACTION.RECONCILIATION_STATUS).getLiteral());
    assertEquals(
        "ORPHAN", fx.txnByRef("802").get(PAYMENT_TRANSACTION.RECONCILIATION_STATUS).getLiteral());
    org.jooq.Record intent = fx.intentRow(s.intent().getId());
    assertEquals("SETTLED", intent.get(PAYMENT_INTENT.STATUS));
    assertEquals("801", intent.get(PAYMENT_INTENT.SETTLED_TXN_REF), "the first attribution stays");
  }

  @Test
  void cardSettlingAnOrderWithAnOpenInstapayClaim_abandonsTheClaim_inTheSameTxn() {
    Scene s = scene();
    PaymentTransaction claim =
        fx.paymentTransactionService
            .claim(
                s.org(),
                new ClaimCommand(s.order().id(), s.customer(), "IPN-12345", null, "paid by app"))
            .transaction();
    assertEquals("UNVERIFIED", verification(claim.getId()));

    assertEquals(Kind.SETTLED, fx.deliver(s.org(), fx.callback(s.intent(), 901L)).kind());

    assertEquals("PAID", fx.orderStatus(s.order().id()));
    assertEquals("ABANDONED", verification(claim.getId()));
    assertEquals(1, fx.paymentCount(s.order().id()));
  }

  @Test
  void declinedCard_recordsTheAttemptClosed_intentFailed_orderUntouched_thenARetrySettles() {
    Scene s = scene();
    ObjectNode declined = fx.callback(s.intent(), 1001L);
    ((ObjectNode) declined.get("obj")).put("success", false).put("error_occured", true);

    Outcome out = fx.deliver(s.org(), declined);

    assertEquals(Kind.FAILED, out.kind());
    org.jooq.Record txn = fx.txnByRef("1001");
    assertNotNull(txn, "the attempt is in the ledger");
    assertEquals("ABANDONED", txn.get(PAYMENT_TRANSACTION.VERIFICATION_STATUS).getLiteral());
    assertNull(txn.get(PAYMENT_TRANSACTION.RECONCILIATION_STATUS));
    assertNull(
        txn.get(PAYMENT_TRANSACTION.CLAIMED_SALES_ORDER_ID),
        "a closed attempt must not become the order's 'latest claim'");
    assertEquals("FAILED", fx.intentStatus(s.intent().getId()));
    assertEquals("PENDING_PAYMENT", fx.orderStatus(s.order().id()));
    assertEquals(0, fx.paymentCount(s.order().id()));

    // The same declined callback again: a replay, nothing more.
    assertEquals(Kind.REPLAYED, fx.deliver(s.org(), declined).kind());

    // The shopper retries on Paymob's hosted page — same intention, a new transaction.
    Outcome retry = fx.deliver(s.org(), fx.callback(s.intent(), 1002L));
    assertEquals(Kind.SETTLED, retry.kind());
    assertEquals("PAID", fx.orderStatus(s.order().id()));
    org.jooq.Record intent = fx.intentRow(s.intent().getId());
    assertEquals("SETTLED", intent.get(PAYMENT_INTENT.STATUS));
    assertEquals("1002", intent.get(PAYMENT_INTENT.SETTLED_TXN_REF));
  }

  @Test
  void adminRecordPath_currencyMismatch_isStill400_andRollsBack() {
    // The webhook-mode ORPHAN outcome must not have altered the admin path (bit-identical).
    Scene s = scene();
    UUID admin = createUser("admin@acme.test");

    ValidationException e =
        assertThrows(
            ValidationException.class,
            () ->
                fx.paymentTransactionService.verify(
                    s.org(),
                    new VerifyCommand(
                        PaymentProvider.INSTAPAY_MANUAL,
                        "IPN-USD-1",
                        new BigDecimal("250.00"),
                        "USD",
                        null,
                        s.order().number(),
                        null,
                        null,
                        "proof",
                        null),
                    admin));
    assertTrue(e.getMessage().contains("currency mismatch"));
    assertEquals(0, fx.txnCount(s.org()), "rolled back — nothing persisted");
    assertEquals("PENDING_PAYMENT", fx.orderStatus(s.order().id()));
  }

  @Test
  void reconcile_webhookMode_currencyMismatch_isARecordedOrphan_notAThrow() {
    Scene s = scene();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    PaymentTransaction usd =
        PaymentTransaction.createClaimed(
            UUID.randomUUID(),
            s.org(),
            PaymentProvider.PAYMOB_CARD,
            "usd-1",
            new BigDecimal("250.00"),
            "USD",
            null,
            null,
            null,
            null,
            null,
            now,
            now);
    usd.verifyByGateway("test", now);

    PaymentService.Reconciliation rec =
        dsl.transactionResult(
            cfg ->
                fx.paymentService.reconcileAndCreate(
                    DSL.using(cfg),
                    s.org(),
                    usd,
                    new OrderRef(s.order().id(), null),
                    CurrencyMismatch.ORPHAN));

    assertEquals(PaymentReconciliationStatus.ORPHAN, rec.status());
    assertNull(rec.payment());
    assertEquals("PENDING_PAYMENT", fx.orderStatus(s.order().id()));
  }

  /**
   * The sweeper and a settling webhook race by construction. reconcileAndCreate locks the order FOR
   * UPDATE before reading its status, so whichever commits first wins and the loser sees committed
   * state: never both EXPIRED and PAID.
   */
  @Test
  void expiryAndWebhook_concurrent_neverBothExpiredAndPaid() throws Exception {
    // Direction 1: expiry holds the row lock with EXPIRED uncommitted; the webhook must block, then
    // observe EXPIRED → ORPHAN.
    Scene s = scene();
    CountDownLatch lockHeld = new CountDownLatch(1);
    CountDownLatch proceed = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> holder =
          pool.submit(
              () ->
                  dsl.transaction(
                      cfg -> {
                        DSLContext tx = DSL.using(cfg);
                        tx.selectFrom(SALES_ORDER)
                            .where(SALES_ORDER.ID.eq(s.order().id()))
                            .forUpdate()
                            .fetch();
                        tx.update(SALES_ORDER)
                            .set(SALES_ORDER.STATUS, OrderStatus.EXPIRED)
                            .set(SALES_ORDER.EXPIRED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                            .where(SALES_ORDER.ID.eq(s.order().id()))
                            .execute();
                        lockHeld.countDown();
                        proceed.await();
                      }));
      lockHeld.await(5, TimeUnit.SECONDS);

      Future<Outcome> webhook =
          pool.submit(() -> fx.deliver(s.org(), fx.callback(s.intent(), 2001L)));
      assertThrows(TimeoutException.class, () -> webhook.get(500, TimeUnit.MILLISECONDS));

      proceed.countDown();
      holder.get(5, TimeUnit.SECONDS);
      Outcome out = webhook.get(5, TimeUnit.SECONDS);

      assertEquals(Kind.ORPHAN, out.kind());
      assertEquals("EXPIRED", fx.orderStatus(s.order().id()));
      assertEquals(0, fx.paymentCount(s.order().id()));
      assertEquals(
          "ORPHAN",
          fx.txnByRef("2001").get(PAYMENT_TRANSACTION.RECONCILIATION_STATUS).getLiteral());
    } finally {
      pool.shutdownNow();
    }

    // Direction 2: the webhook settles first; the real sweeper then finds nothing to flip.
    Scene t = scene();
    assertEquals(Kind.SETTLED, fx.deliver(t.org(), fx.callback(t.intent(), 2002L)).kind());
    assertEquals(0, fx.orderExpiryService.expireOnePending(t.order().id()));
    assertEquals("PAID", fx.orderStatus(t.order().id()));
  }

  // helpers

  private void assertNothingWritten(Scene s) {
    assertEquals(0, fx.txnCount(s.org()), "no payment_transaction");
    assertEquals(0, fx.paymentCount(s.order().id()), "no payment");
    assertEquals("PENDING_PAYMENT", fx.orderStatus(s.order().id()));
    assertEquals("PENDING", fx.intentStatus(s.intent().getId()));
  }

  private String verification(UUID txnId) {
    return dsl.select(PAYMENT_TRANSACTION.VERIFICATION_STATUS)
        .from(PAYMENT_TRANSACTION)
        .where(PAYMENT_TRANSACTION.ID.eq(txnId))
        .fetchOne(PAYMENT_TRANSACTION.VERIFICATION_STATUS)
        .getLiteral();
  }

  private UUID createUser(String email) {
    UUID id = UUID.randomUUID();
    dsl.execute(
        "insert into app_user (id, email, password_hash, actor_type) values (?, ?, 'x', 'USER')",
        id,
        id + "-" + email);
    return id;
  }
}
