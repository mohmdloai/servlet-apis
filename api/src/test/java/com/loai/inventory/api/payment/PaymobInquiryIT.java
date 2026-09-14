package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.ORG_PAYMOB_CONFIG;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_INTENT;
import static com.loai.inventory.repository.generated.Tables.PAYMENT_TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loai.inventory.common.crypto.PaymobSignature;
import com.loai.inventory.common.exception.UpstreamFailureException;
import com.loai.inventory.domain.model.OrgPaymobConfig;
import com.loai.inventory.domain.model.PaymentIntent;
import com.loai.inventory.repository.OrgPaymobConfigRepositoryFactoryImpl;
import com.loai.inventory.service.PaymobInquiryService;
import com.loai.inventory.service.PaymobInquiryService.Summary;
import com.loai.inventory.service.PaymobWebhookService.Outcome;
import com.loai.inventory.service.PaymobWebhookService.Outcome.Kind;
import com.loai.inventory.service.paymob.JdkPaymobClient;
import com.loai.inventory.service.paymob.PaymobCallback;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 * Epic slice 3 ({@code stories/paymob_card_reliability.md}): the inquiry poller. The crux under
 * test is that a webhook and a poll describing one transaction are indistinguishable downstream —
 * same rows, same dedupe, same outcome whoever got there first — plus the grace window, the expiry
 * of intents Paymob knows nothing about, and the two failure grades (one bad org or intent
 * continues the batch; Paymob unreachable ends it).
 */
@Testcontainers
class PaymobInquiryIT {

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
    fx.fakePaymob.inquiryByPaymobOrder.clear();
    fx.fakePaymob.inquiries.clear();
    fx.fakePaymob.unreachable = false;
  }

  private record Scene(UUID org, UUID customer, PaymobFixture.Order order, PaymentIntent intent) {}

  private Scene scene() {
    UUID org = fx.createOrg("acme");
    fx.connect(org);
    UUID customer = fx.createCustomer(org, "Nadia Hassan", "nadia@example.test", "+201001234567");
    PaymobFixture.Order order = fx.seedPendingOrder(org, customer, "250.00");
    return new Scene(org, customer, order, fx.mintIntent(org, order, customer));
  }

  /** Paymob "has" a transaction for the intent — what an inquiry would return. */
  private ObjectNode paymobHas(PaymentIntent intent, long txnId) {
    ObjectNode cb = fx.callback(intent, txnId);
    fx.fakePaymob.inquiryByPaymobOrder.put(intent.getPaymobOrderId(), fx.bareTransaction(cb));
    return cb;
  }

  @Test
  void webhookNeverDelivered_theSweeperSettles_withRowsIdenticalToTheWebhookPath() {
    Scene s = scene();
    paymobHas(s.intent(), 3001L);

    Summary summary = fx.inquiryService.sweep(100);

    assertEquals(1, summary.scanned());
    assertEquals(1, summary.settled());
    assertEquals(PaymobFixture.API_KEY, fx.fakePaymob.lastApiKey, "decrypted at use");
    assertEquals(List.of(s.intent().getPaymobOrderId()), fx.fakePaymob.inquiries);

    assertEquals("PAID", fx.orderStatus(s.order().id()));
    org.jooq.Record txn = fx.txnByRef("3001");
    assertNotNull(txn);
    assertEquals("paymob_card", txn.get(PAYMENT_TRANSACTION.PROVIDER).getLiteral());
    assertEquals("VERIFIED", txn.get(PAYMENT_TRANSACTION.VERIFICATION_STATUS).getLiteral());
    assertNull(txn.get(PAYMENT_TRANSACTION.VERIFIED_BY));
    assertEquals("MATCHED", txn.get(PAYMENT_TRANSACTION.RECONCILIATION_STATUS).getLiteral());
    assertEquals(s.order().id(), txn.get(PAYMENT_TRANSACTION.CLAIMED_SALES_ORDER_ID));
    assertTrue(
        txn.get(PAYMENT_TRANSACTION.RAW_PAYLOAD).data().contains("\"source\": \"inquiry\""),
        "the ledger says which door it came through");
    assertEquals(1, fx.paymentCount(s.order().id()));
    org.jooq.Record intent = fx.intentRow(s.intent().getId());
    assertEquals("SETTLED", intent.get(PAYMENT_INTENT.STATUS));
    assertEquals("3001", intent.get(PAYMENT_INTENT.SETTLED_TXN_REF));
  }

  @Test
  void webhookArrivingAfterTheSweeper_isAnIdempotentReplay() {
    Scene s = scene();
    ObjectNode cb = paymobHas(s.intent(), 3002L);
    assertEquals(1, fx.inquiryService.sweep(100).settled());

    Outcome late = fx.deliver(s.org(), cb);

    assertEquals(Kind.REPLAYED, late.kind());
    assertEquals(1, fx.txnCount(s.org()));
    assertEquals(1, fx.paymentCount(s.order().id()));
    assertEquals("PAID", fx.orderStatus(s.order().id()));
  }

  /** The insert race on (provider, provider_ref) decides — never the ordering. */
  @Test
  void sweeperAndWebhook_onTheSameIntentAtOnce_exactlyOneSettlement() throws Exception {
    Scene s = scene();
    ObjectNode cb = paymobHas(s.intent(), 3003L);
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Summary> sweep =
          pool.submit(
              () -> {
                go.await();
                return fx.inquiryService.sweep(100);
              });
      Future<Outcome> webhook =
          pool.submit(
              () -> {
                go.await();
                return fx.deliver(s.org(), cb);
              });
      go.countDown();
      Summary summary = sweep.get(15, TimeUnit.SECONDS);
      Outcome out = webhook.get(15, TimeUnit.SECONDS);

      int settledByWebhook = out.kind() == Kind.SETTLED ? 1 : 0;
      assertTrue(out.kind() == Kind.SETTLED || out.kind() == Kind.REPLAYED, out.toString());
      assertEquals(1, summary.settled() + settledByWebhook, "exactly one of them settled");
      assertEquals(1, summary.replayed() + (out.kind() == Kind.REPLAYED ? 1 : 0));
    } finally {
      pool.shutdownNow();
    }
    assertEquals(1, fx.txnCount(s.org()));
    assertEquals(1, fx.paymentCount(s.order().id()));
    assertEquals("PAID", fx.orderStatus(s.order().id()));
    assertEquals("SETTLED", fx.intentStatus(s.intent().getId()));
  }

  @Test
  void intentYoungerThanTheGraceWindow_isNotInquiredAbout() {
    Scene s = scene();
    paymobHas(s.intent(), 3004L);
    PaymobInquiryService patient = fx.inquiryServiceWithGrace(Duration.ofMinutes(3));

    Summary summary = patient.sweep(100);

    assertEquals(0, summary.scanned());
    assertTrue(fx.fakePaymob.inquiries.isEmpty(), "Paymob was not asked");
    assertEquals("PENDING", fx.intentStatus(s.intent().getId()));
    assertEquals("PENDING_PAYMENT", fx.orderStatus(s.order().id()));
  }

  @Test
  void pastItsDeadline_withNoPaymobTransaction_expires_orderUntouched() {
    Scene s = scene();
    dsl.update(PAYMENT_INTENT)
        .set(PAYMENT_INTENT.EXPIRES_AT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
        .where(PAYMENT_INTENT.ID.eq(s.intent().getId()))
        .execute();

    Summary summary = fx.inquiryService.sweep(100);

    assertEquals(1, summary.expired());
    assertEquals("EXPIRED", fx.intentStatus(s.intent().getId()));
    assertEquals("PENDING_PAYMENT", fx.orderStatus(s.order().id()));
    assertEquals(0, fx.txnCount(s.org()));
  }

  @Test
  void beforeItsDeadline_withNoPaymobTransaction_staysPending() {
    Scene s = scene();

    Summary summary = fx.inquiryService.sweep(100);

    assertEquals(1, summary.leftPending());
    assertEquals("PENDING", fx.intentStatus(s.intent().getId()));
    assertEquals(List.of(s.intent().getPaymobOrderId()), fx.fakePaymob.inquiries);
  }

  @Test
  void pendingThreeDsFromInquiry_staysPending_noRowWritten() {
    Scene s = scene();
    ObjectNode cb = paymobHas(s.intent(), 3005L);
    ((ObjectNode) cb.get("obj")).put("pending", true).put("success", false);
    fx.fakePaymob.inquiryByPaymobOrder.put(s.intent().getPaymobOrderId(), fx.bareTransaction(cb));

    Summary summary = fx.inquiryService.sweep(100);

    assertEquals(1, summary.leftPending());
    assertEquals(0, fx.txnCount(s.org()));
    assertEquals("PENDING", fx.intentStatus(s.intent().getId()));
  }

  @Test
  void failedTransactionFromInquiry_failsTheIntent_orderUntouched() {
    Scene s = scene();
    ObjectNode cb = paymobHas(s.intent(), 3006L);
    ((ObjectNode) cb.get("obj")).put("success", false).put("error_occured", true);
    fx.fakePaymob.inquiryByPaymobOrder.put(s.intent().getPaymobOrderId(), fx.bareTransaction(cb));

    Summary summary = fx.inquiryService.sweep(100);

    assertEquals(1, summary.failed());
    assertEquals("FAILED", fx.intentStatus(s.intent().getId()));
    assertEquals(
        "ABANDONED", fx.txnByRef("3006").get(PAYMENT_TRANSACTION.VERIFICATION_STATUS).getLiteral());
    assertEquals("PENDING_PAYMENT", fx.orderStatus(s.order().id()));
  }

  @Test
  void orgThatDisconnected_intentExpiredWithAWarning_theRestOfTheBatchContinues() {
    Scene gone = scene();
    fx.orgPaymobService.disconnect(gone.org()); // retires gone's live intent (slice 2 rule)
    // Give it a fresh PENDING intent by hand, as if minted just before the disconnect committed.
    dsl.update(PAYMENT_INTENT)
        .set(PAYMENT_INTENT.STATUS, "PENDING")
        .where(PAYMENT_INTENT.ID.eq(gone.intent().getId()))
        .execute();
    Scene alive = scene();
    paymobHas(alive.intent(), 3007L);

    Summary summary = fx.inquiryService.sweep(100);

    assertEquals(2, summary.scanned());
    assertEquals(1, summary.expired());
    assertEquals(1, summary.settled());
    assertFalse(summary.aborted());
    assertEquals("EXPIRED", fx.intentStatus(gone.intent().getId()));
    assertEquals("PAID", fx.orderStatus(alive.order().id()));
  }

  @Test
  void orgConnectedBeforeV100_hasNoApiKey_cannotInquire_expiresOnlyPastTheDeadline() {
    Scene s = scene();
    dsl.update(ORG_PAYMOB_CONFIG)
        .setNull(ORG_PAYMOB_CONFIG.API_KEY_ENCRYPTED)
        .where(ORG_PAYMOB_CONFIG.ORG_ID.eq(s.org()))
        .execute();
    paymobHas(s.intent(), 3008L); // Paymob would say "settled" — but nobody can ask

    Summary first = fx.inquiryService.sweep(100);
    assertEquals(1, first.leftPending());
    assertTrue(fx.fakePaymob.inquiries.isEmpty(), "never asked without a key");
    assertEquals("PENDING", fx.intentStatus(s.intent().getId()));

    dsl.update(PAYMENT_INTENT)
        .set(PAYMENT_INTENT.EXPIRES_AT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
        .where(PAYMENT_INTENT.ID.eq(s.intent().getId()))
        .execute();
    Summary second = fx.inquiryService.sweep(100);
    assertEquals(1, second.expired());
    assertEquals("EXPIRED", fx.intentStatus(s.intent().getId()));
    assertEquals("PENDING_PAYMENT", fx.orderStatus(s.order().id()), "money is not invented");
  }

  @Test
  void paymobUnreachable_theBatchEnds_intentsStayPending_nothingWronglyExpired() {
    Scene s = scene();
    dsl.update(PAYMENT_INTENT)
        .set(PAYMENT_INTENT.EXPIRES_AT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
        .where(PAYMENT_INTENT.ID.eq(s.intent().getId()))
        .execute();
    fx.fakePaymob.unreachable = true;

    Summary summary = fx.inquiryService.sweep(100);

    assertTrue(summary.aborted());
    assertEquals(0, summary.expired(), "an unanswered inquiry is not 'no transaction'");
    assertEquals("PENDING", fx.intentStatus(s.intent().getId()));
  }

  @Test
  void inquiryObjectCarryingASignature_isVerified_aBadOneIsNotSettled() {
    Scene s = scene();
    ObjectNode cb = fx.callback(s.intent(), 3009L);
    ((ObjectNode) cb.get("obj")).put("hmac", "deadbeef");
    fx.fakePaymob.inquiryByPaymobOrder.put(s.intent().getPaymobOrderId(), fx.bareTransaction(cb));

    Summary bad = fx.inquiryService.sweep(100);
    assertEquals(1, bad.errors());
    assertEquals(0, fx.txnCount(s.org()));
    assertEquals("PENDING", fx.intentStatus(s.intent().getId()));

    PaymobCallback parsed = PaymobCallback.parse(fx.mapper, fx.body(cb));
    String good =
        PaymobSignature.sign(
            PaymobSignature.canonical(parsed.signedValues()), PaymobFixture.HMAC_SECRET);
    ((ObjectNode) cb.get("obj")).put("hmac", good);
    fx.fakePaymob.inquiryByPaymobOrder.put(s.intent().getPaymobOrderId(), fx.bareTransaction(cb));

    Summary ok = fx.inquiryService.sweep(100);
    assertEquals(1, ok.settled());
    assertEquals("PAID", fx.orderStatus(s.order().id()));
  }

  @Test
  void stuckCount_countsOnlyPendingIntentsPastTheirDeadline() {
    Scene s = scene();
    UUID other = fx.createOrg("other");
    var repo = new com.loai.inventory.repository.PaymentIntentRepositoryFactoryImpl().create(dsl);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    assertEquals(0, repo.countStuck(s.org(), now));
    dsl.update(PAYMENT_INTENT)
        .set(PAYMENT_INTENT.EXPIRES_AT, now.minusMinutes(1))
        .where(PAYMENT_INTENT.ID.eq(s.intent().getId()))
        .execute();
    assertEquals(1, repo.countStuck(s.org(), now));
    assertEquals(0, repo.countStuck(other, now));
  }

  /** The real client against a stub Paymob: token exchange, bearer inquiry, 404 → empty. */
  @Test
  void jdkClient_exchangesTheApiKey_thenInquiresWithBearer_404MeansNoTransaction()
      throws Exception {
    AtomicReference<String> seenTokenBody = new AtomicReference<>();
    AtomicReference<String> seenAuth = new AtomicReference<>();
    AtomicReference<String> seenInquiryBody = new AtomicReference<>();
    HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/api/auth/tokens",
        ex -> {
          seenTokenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          respond(ex, 201, "{\"token\":\"tok_stub_123\",\"profile\":{\"id\":1}}");
        });
    stub.createContext(
        "/api/ecommerce/orders/transaction_inquiry",
        ex -> {
          seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
          String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          seenInquiryBody.set(body);
          if (body.contains("\"order_id\":\"111\"")) {
            respond(ex, 200, "{\"id\":534775332,\"success\":true,\"amount_cents\":25000}");
          } else if (body.contains("\"order_id\":\"401\"")) {
            respond(ex, 401, "{\"detail\":\"Invalid token.\"}");
          } else {
            respond(ex, 404, "{\"detail\":\"Not found.\"}");
          }
        });
    stub.start();
    try {
      String base = "http://127.0.0.1:" + stub.getAddress().getPort();
      JdkPaymobClient client =
          new JdkPaymobClient(fx.mapper, Duration.ofSeconds(5), region -> base);
      OrgPaymobConfig config =
          new OrgPaymobConfigRepositoryFactoryImpl()
              .create(dsl)
              .findByOrgId(scene().org())
              .orElseThrow();

      String token = client.authenticate(config, "legacy-key-xyz");
      assertEquals("tok_stub_123", token);
      JsonNode tokenBody = fx.mapper.readTree(seenTokenBody.get());
      assertEquals("legacy-key-xyz", tokenBody.get("api_key").asText());

      assertTrue(client.inquireTransaction(config, token, "111", "ref").isPresent());
      assertEquals("Bearer tok_stub_123", seenAuth.get());
      assertTrue(seenInquiryBody.get().contains("\"order_id\":\"111\""));

      assertTrue(client.inquireTransaction(config, token, "222", "ref").isEmpty(), "404 → none");

      assertTrue(client.inquireTransaction(config, token, null, "by-ref").isEmpty());
      assertTrue(seenInquiryBody.get().contains("\"merchant_order_id\":\"by-ref\""));

      assertThrows(
          UpstreamFailureException.class,
          () -> client.inquireTransaction(config, token, "401", "ref"));
    } finally {
      stub.stop(0);
    }
  }

  private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String json)
      throws java.io.IOException {
    byte[] out = json.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(code, out.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(out);
    }
  }
}
