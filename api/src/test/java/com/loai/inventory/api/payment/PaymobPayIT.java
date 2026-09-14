package com.loai.inventory.api.payment;

import static com.loai.inventory.repository.generated.Tables.PAYMENT_INTENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.UpstreamFailureException;
import com.loai.inventory.repository.generated.enums.OrderStatus;
import com.loai.inventory.service.PaymentIntentService.PayResult;
import com.loai.inventory.service.ReturnTarget;
import com.loai.inventory.service.paymob.JdkPaymobClient;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Epic slice 2, the pay side ({@code stories/paymob_card_checkout.md}, {@code POST
 * /api/public/orders/{token}/pay}): the 409s, intent reuse inside the TTL, a fresh intent once the
 * old one is spent — and, against a stub HTTP server, what {@link JdkPaymobClient} actually sends
 * Paymob and what it does with the answer. Plus the signed-in door's one difference ({@code
 * stories/paymob_portal_pay.md}): the {@link ReturnTarget} and nothing else.
 */
@Testcontainers
class PaymobPayIT {

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

  @Test
  void pay_onAPendingOrderOfAConnectedOrg_mintsOnePendingIntent_withTheCheckoutUrl() {
    UUID org = fx.createOrg("acme");
    fx.connect(org);
    UUID customer = fx.createCustomer(org, "Nadia Hassan", "nadia@example.test", "+201001234567");
    PaymobFixture.Order order = fx.seedPendingOrder(org, customer, "250.50");

    PayResult r =
        fx.intentService.pay(org, order.id(), customer, ReturnTarget.publicTracker("tok-abc"));

    assertFalse(r.reused());
    assertEquals(
        "https://accept.paymob.com/unifiedcheckout/?publicKey="
            + PaymobFixture.PUBLIC_KEY
            + "&clientSecret="
            + r.intent().getClientSecret(),
        r.checkoutUrl());
    assertTrue(r.checkoutUrl().contains("clientSecret=cs_"));
    assertTrue(r.expiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(19)));

    assertEquals(1, fx.intentCount(order.id()));
    org.jooq.Record row = fx.intentRow(r.intent().getId());
    assertEquals("PENDING", row.get(PAYMENT_INTENT.STATUS));
    assertEquals("paymob_card", row.get(PAYMENT_INTENT.PROVIDER).getLiteral());
    assertEquals(0, new java.math.BigDecimal("250.50").compareTo(row.get(PAYMENT_INTENT.AMOUNT)));
    assertEquals(r.intent().getId().toString(), row.get(PAYMENT_INTENT.SPECIAL_REFERENCE));
    assertNotNull(row.get(PAYMENT_INTENT.INTENTION_ID));
    assertNotNull(row.get(PAYMENT_INTENT.PAYMOB_ORDER_ID));

    // What was asked of Paymob, in Paymob's units.
    var req = fx.fakePaymob.lastRequest;
    assertEquals(25050L, req.amountCents(), "piastres, exact");
    assertEquals("EGP", req.currency());
    assertEquals(PaymobFixture.INTEGRATION_ID, req.integrationId());
    assertEquals(r.intent().getId().toString(), req.specialReference());
    assertEquals(
        PaymobFixture.PUBLIC_API_URL + "/api/psp/paymob/" + org + "/webhook",
        req.notificationUrl());
    // The guest's return page: the branded tracker at the token, in the customer's locale (none
    // set → the org default, "ar" by V52) — byte for byte what the emails link to.
    assertEquals(
        PaymobFixture.PUBLIC_BASE_URL + "/ar/" + fx.orgSlug(org) + "/orders/tok-abc",
        req.redirectionUrl());
    assertEquals(20 * 60, req.expirationSeconds());
    assertEquals("Nadia", req.billing().firstName());
    assertEquals("Hassan", req.billing().lastName());
    assertEquals("+201001234567", req.billing().phoneNumber());
    assertEquals("nadia@example.test", req.billing().email());
    assertEquals(PaymobFixture.SECRET_KEY, fx.fakePaymob.lastSecretKey, "decrypted at use");
  }

  @Test
  void pay_twiceInsideTheTtl_oneIntentRow_identicalCheckoutUrl() {
    UUID org = fx.createOrg("acme");
    fx.connect(org);
    PaymobFixture.Order order = fx.seedPendingOrder(org, null, "100.00");

    PayResult first =
        fx.intentService.pay(org, order.id(), null, ReturnTarget.publicTracker("tok"));
    PayResult second =
        fx.intentService.pay(org, order.id(), null, ReturnTarget.publicTracker("tok"));

    assertFalse(first.reused());
    assertTrue(second.reused());
    assertEquals(first.checkoutUrl(), second.checkoutUrl());
    assertEquals(first.intent().getId(), second.intent().getId());
    assertEquals(1, fx.intentCount(order.id()));
  }

  @Test
  void pay_afterTheIntentExpired_orWasSpent_orTheAmountChanged_mintsANewOne() {
    UUID org = fx.createOrg("acme");
    fx.connect(org);
    PaymobFixture.Order order = fx.seedPendingOrder(org, null, "100.00");
    PayResult first =
        fx.intentService.pay(org, order.id(), null, ReturnTarget.publicTracker("tok"));

    // Expired.
    dsl.update(PAYMENT_INTENT)
        .set(PAYMENT_INTENT.EXPIRES_AT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
        .where(PAYMENT_INTENT.ID.eq(first.intent().getId()))
        .execute();
    PayResult second =
        fx.intentService.pay(org, order.id(), null, ReturnTarget.publicTracker("tok"));
    assertFalse(second.reused());
    assertNotEquals(first.intent().getId(), second.intent().getId());
    assertNotEquals(first.checkoutUrl(), second.checkoutUrl());
    assertEquals(2, fx.intentCount(order.id()));

    // Spent (a declined attempt).
    dsl.update(PAYMENT_INTENT)
        .set(PAYMENT_INTENT.STATUS, "FAILED")
        .where(PAYMENT_INTENT.ID.eq(second.intent().getId()))
        .execute();
    PayResult third =
        fx.intentService.pay(org, order.id(), null, ReturnTarget.publicTracker("tok"));
    assertFalse(third.reused());
    assertEquals(3, fx.intentCount(order.id()));

    // Repriced: a partial prepayment landed, the outstanding amount changed.
    dsl.execute("update sales_order set prepaid_amount = 40.00 where id = ?", order.id());
    PayResult fourth =
        fx.intentService.pay(org, order.id(), null, ReturnTarget.publicTracker("tok"));
    assertFalse(fourth.reused());
    assertEquals(0, new java.math.BigDecimal("60.00").compareTo(fourth.intent().getAmount()));
    assertEquals(4, fx.intentCount(order.id()));
  }

  /**
   * Found on the first live sandbox run: a live intent minted under integration A was handed back
   * by …/pay after the merchant reconnected with integration B — a checkout URL whose client secret
   * belongs to the old credentials. A reconnect (and a disconnect) retires the org's live intents;
   * the next tap mints afresh under the new integration.
   */
  @Test
  void reconnect_retiresTheLiveIntent_soTheNextPayMintsUnderTheNewIntegration() {
    UUID org = fx.createOrg("acme");
    fx.connect(org);
    PaymobFixture.Order order = fx.seedPendingOrder(org, null, "100.00");
    PayResult underA =
        fx.intentService.pay(org, order.id(), null, ReturnTarget.publicTracker("tok"));

    fx.orgPaymobService.connect(
        org,
        PaymobFixture.PUBLIC_KEY,
        PaymobFixture.SECRET_KEY,
        PaymobFixture.HMAC_SECRET,
        PaymobFixture.API_KEY,
        7777,
        "EGYPT");

    assertEquals("EXPIRED", fx.intentStatus(underA.intent().getId()));
    PayResult underB =
        fx.intentService.pay(org, order.id(), null, ReturnTarget.publicTracker("tok"));
    assertFalse(underB.reused());
    assertNotEquals(underA.intent().getId(), underB.intent().getId());
    assertEquals(7777, fx.fakePaymob.lastRequest.integrationId());

    // Disconnect retires the live one too; …/pay is then a 409 (no card channel).
    fx.orgPaymobService.disconnect(org);
    assertEquals("EXPIRED", fx.intentStatus(underB.intent().getId()));
    assertThrows(
        ConflictException.class,
        () -> fx.intentService.pay(org, order.id(), null, ReturnTarget.publicTracker("tok")));
  }

  @Test
  void pay_onAnOrderNotAwaitingPayment_is409() {
    UUID org = fx.createOrg("acme");
    fx.connect(org);
    PaymobFixture.Order paid = fx.seedOrder(org, null, "100.00", "100.00", OrderStatus.PAID);

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () -> fx.intentService.pay(org, paid.id(), null, ReturnTarget.publicTracker("tok")));
    assertEquals(409, e.getStatusCode());
    assertEquals(0, fx.intentCount(paid.id()));
  }

  @Test
  void pay_onAnOrgWithNoPaymobConfig_is409() {
    UUID org = fx.createOrg("acme"); // never connected
    PaymobFixture.Order order = fx.seedPendingOrder(org, null, "100.00");

    ConflictException e =
        assertThrows(
            ConflictException.class,
            () -> fx.intentService.pay(org, order.id(), null, ReturnTarget.publicTracker("tok")));
    assertEquals(409, e.getStatusCode());
    assertTrue(e.getMessage().contains("does not accept card"));
    assertEquals(0, fx.intentCount(order.id()));
  }

  @Test
  void pay_withNothingLeftToPay_is409() {
    UUID org = fx.createOrg("acme");
    fx.connect(org);
    PaymobFixture.Order order =
        fx.seedOrder(org, null, "100.00", "100.00", OrderStatus.PENDING_PAYMENT);

    assertThrows(
        ConflictException.class,
        () -> fx.intentService.pay(org, order.id(), null, ReturnTarget.publicTracker("tok")));
    assertEquals(0, fx.intentCount(order.id()));
  }

  /**
   * The signed-in door ({@code stories/paymob_portal_pay.md}): the intention differs from the
   * public door's in exactly one field — Paymob sends the customer back to their account order
   * page, not to a magic-link tracker that would drop them out of their account.
   */
  @Test
  void pay_fromThePortalDoor_returnsToTheAccountOrderPage_andNothingElseDiffers() {
    UUID org = fx.createOrg("acme");
    fx.connect(org);
    UUID customer = fx.createCustomer(org, "Nadia Hassan", "nadia@example.test", "+201001234567");
    PaymobFixture.Order order = fx.seedPendingOrder(org, customer, "250.50");

    PayResult viaPortal =
        fx.intentService.pay(org, order.id(), customer, ReturnTarget.portalOrder(order.number()));
    var portalReq = fx.fakePaymob.lastRequest;
    assertEquals(
        PaymobFixture.PUBLIC_BASE_URL
            + "/ar/"
            + fx.orgSlug(org)
            + "/account/orders/"
            + order.number(),
        portalReq.redirectionUrl());
    assertFalse(viaPortal.reused());
    assertEquals(1, fx.intentCount(order.id()));

    // Retire it and mint again through the public door: every other field is identical.
    dsl.update(PAYMENT_INTENT)
        .set(PAYMENT_INTENT.STATUS, "FAILED")
        .where(PAYMENT_INTENT.ID.eq(viaPortal.intent().getId()))
        .execute();
    PayResult viaPublic =
        fx.intentService.pay(org, order.id(), customer, ReturnTarget.publicTracker("tok-abc"));
    var publicReq = fx.fakePaymob.lastRequest;
    assertEquals(
        PaymobFixture.PUBLIC_BASE_URL + "/ar/" + fx.orgSlug(org) + "/orders/tok-abc",
        publicReq.redirectionUrl());
    assertEquals(portalReq.amountCents(), publicReq.amountCents());
    assertEquals(portalReq.currency(), publicReq.currency());
    assertEquals(portalReq.integrationId(), publicReq.integrationId());
    assertEquals(portalReq.notificationUrl(), publicReq.notificationUrl());
    assertEquals(portalReq.expirationSeconds(), publicReq.expirationSeconds());
    assertEquals(portalReq.billing(), publicReq.billing());
    assertEquals(portalReq.items(), publicReq.items());
    assertFalse(viaPublic.reused());
  }

  /** The customer's own locale wins over the org default on either door. */
  @Test
  void returnPage_followsTheCustomersLocale() {
    UUID org = fx.createOrg("acme");
    fx.connect(org);
    UUID customer = fx.createCustomer(org, "Omar", "omar@example.test", null);
    dsl.execute("update customer set locale = 'en' where id = ?", customer);
    PaymobFixture.Order order = fx.seedPendingOrder(org, customer, "10.00");

    fx.intentService.pay(org, order.id(), customer, ReturnTarget.portalOrder(order.number()));

    assertEquals(
        PaymobFixture.PUBLIC_BASE_URL
            + "/en/"
            + fx.orgSlug(org)
            + "/account/orders/"
            + order.number(),
        fx.fakePaymob.lastRequest.redirectionUrl());
  }

  /**
   * Reuse crosses planes, and that is documented rather than "fixed": the reuse rule is the live
   * PENDING intent for the same order and amount, so a customer who minted from the emailed tracker
   * and then taps pay on their account page gets the SAME checkout URL — whose return page is the
   * tracker. Minting a second intention only to change a return address would be a second
   * intention, which the reuse rule exists to avoid; both pages carry the return watcher.
   */
  @Test
  void reuse_crossesTheTwoDoors_sameUrl_returnPageOfTheFirstMint() {
    UUID org = fx.createOrg("acme");
    fx.connect(org);
    UUID customer = fx.createCustomer(org, "Nadia", "nadia@example.test", null);
    PaymobFixture.Order order = fx.seedPendingOrder(org, customer, "100.00");

    PayResult first =
        fx.intentService.pay(org, order.id(), customer, ReturnTarget.publicTracker("tok"));
    String firstReturn = fx.fakePaymob.lastRequest.redirectionUrl();
    PayResult second =
        fx.intentService.pay(org, order.id(), customer, ReturnTarget.portalOrder(order.number()));

    assertTrue(second.reused());
    assertEquals(first.checkoutUrl(), second.checkoutUrl());
    assertEquals(first.intent().getId(), second.intent().getId());
    assertEquals(1, fx.intentCount(order.id()));
    assertTrue(firstReturn.endsWith("/orders/tok"), firstReturn);
    // No second intention went to Paymob — the last request is still the first mint's.
    assertEquals(firstReturn, fx.fakePaymob.lastRequest.redirectionUrl());
  }

  /** The real client against a stub Paymob: header, body, and the answer's handling. */
  @Test
  void jdkClient_postsTheIntention_withTheMerchantsToken_andStoresPaymobsHandles()
      throws Exception {
    AtomicReference<String> seenAuth = new AtomicReference<>();
    AtomicReference<String> seenPath = new AtomicReference<>();
    AtomicReference<String> seenBody = new AtomicReference<>();
    AtomicInteger status = new AtomicInteger(201);
    HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext(
        "/",
        ex -> {
          seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
          seenPath.set(ex.getRequestURI().getPath());
          seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] out =
              ("{\"id\":\"int_stub_1\",\"intention_order_id\":424242,\"client_secret\":"
                      + "\"egy_csk_test_stub\",\"status\":\"intended\"}")
                  .getBytes(StandardCharsets.UTF_8);
          int code = status.get();
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(code, out.length);
          try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
          }
        });
    stub.start();
    try {
      String base = "http://127.0.0.1:" + stub.getAddress().getPort();
      PaymobFixture real =
          new PaymobFixture(
              dsl, new JdkPaymobClient(fx.mapper, Duration.ofSeconds(5), region -> base));
      UUID org = real.createOrg("acme");
      real.connect(org);
      UUID customer = real.createCustomer(org, "Omar", "omar@example.test", null);
      PaymobFixture.Order order = real.seedPendingOrder(org, customer, "99.99");

      PayResult r =
          real.intentService.pay(org, order.id(), customer, ReturnTarget.publicTracker("tok-real"));

      assertEquals("Token " + PaymobFixture.SECRET_KEY, seenAuth.get());
      assertEquals("/v1/intention/", seenPath.get());
      JsonNode body = fx.mapper.readTree(seenBody.get());
      assertEquals(9999, body.get("amount").asLong());
      assertEquals("EGP", body.get("currency").asText());
      assertEquals(PaymobFixture.INTEGRATION_ID, body.get("payment_methods").get(0).asInt());
      assertEquals(r.intent().getId().toString(), body.get("special_reference").asText());
      assertEquals(
          PaymobFixture.PUBLIC_API_URL + "/api/psp/paymob/" + org + "/webhook",
          body.get("notification_url").asText());
      assertTrue(body.get("redirection_url").asText().endsWith("/orders/tok-real"));
      assertEquals(1200, body.get("expiration").asLong());
      assertEquals(9999, body.get("items").get(0).get("amount").asLong());
      assertEquals(1, body.get("items").get(0).get("quantity").asInt());
      assertEquals("Omar", body.get("billing_data").get("first_name").asText());
      assertEquals("-", body.get("billing_data").get("last_name").asText());
      assertEquals(
          "+201000000000", body.get("billing_data").get("phone_number").asText(), "placeholder");
      assertEquals(r.intent().getId().toString(), body.get("extras").get("intent_id").asText());

      org.jooq.Record row = real.intentRow(r.intent().getId());
      assertEquals("int_stub_1", row.get(PAYMENT_INTENT.INTENTION_ID));
      assertEquals("424242", row.get(PAYMENT_INTENT.PAYMOB_ORDER_ID));
      assertEquals("egy_csk_test_stub", row.get(PAYMENT_INTENT.CLIENT_SECRET));
      assertEquals(
          "https://accept.paymob.com/unifiedcheckout/?publicKey="
              + PaymobFixture.PUBLIC_KEY
              + "&clientSecret=egy_csk_test_stub",
          r.checkoutUrl());

      // Paymob refuses (a wrong secret key, say): 502 to the shopper, and NO intent row — a
      // second tap mints afresh rather than reusing a row Paymob never accepted.
      status.set(401);
      PaymobFixture.Order other = real.seedPendingOrder(org, customer, "10.00");
      UpstreamFailureException e =
          assertThrows(
              UpstreamFailureException.class,
              () ->
                  real.intentService.pay(
                      org, other.id(), customer, ReturnTarget.publicTracker("tok-2")));
      assertEquals(502, e.getStatusCode());
      assertEquals(0, real.intentCount(other.id()));
    } finally {
      stub.stop(0);
    }
  }

  @Test
  void jdkClient_unreachableHost_is502_noRow() {
    // A closed port: connection refused → 502 in well under the timeout, nothing written.
    PaymobFixture real =
        new PaymobFixture(
            dsl,
            new JdkPaymobClient(fx.mapper, Duration.ofSeconds(3), region -> "http://127.0.0.1:1"));
    UUID org = real.createOrg("acme");
    real.connect(org);
    PaymobFixture.Order order = real.seedPendingOrder(org, null, "10.00");

    assertThrows(
        UpstreamFailureException.class,
        () -> real.intentService.pay(org, order.id(), null, ReturnTarget.publicTracker("tok")));
    assertEquals(0, real.intentCount(order.id()));
  }
}
