package com.loai.inventory.api.notification;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.PUSH_SUBSCRIPTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.crypto.P256;
import com.loai.inventory.common.exception.ServiceUnavailableException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.security.VapidKeys;
import com.loai.inventory.domain.model.PushSubscription;
import com.loai.inventory.repository.PushSubscriptionRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.repository.generated.enums.ActorType;
import com.loai.inventory.service.PushSubscriptionService;
import com.loai.inventory.service.push.WebPushConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
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
 * The device's side of the Web Push contract ({@code /api/me/push-subscriptions}, V96): subscribe,
 * re-subscribe, an endpoint changing hands, the validation floor, the 503 when the server has no
 * key pair, the oracle-free unsubscribe, and which rows count as <em>live</em>.
 */
@Testcontainers
class PushSubscriptionIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static PushSubscriptionService service;
  static PushSubscriptionService disabled;
  static UserRepositoryImpl users;

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
    cfg.setMaximumPoolSize(4);
    cfg.setConnectionInitSql("SET search_path TO inventorydb");
    dataSource = new HikariDataSource(cfg);
    dsl = DSL.using(dataSource, SQLDialect.POSTGRES);

    users = new UserRepositoryImpl(dsl);
    service =
        new PushSubscriptionService(
            dsl,
            new PushSubscriptionRepositoryFactoryImpl(),
            new UserRepositoryFactoryImpl(),
            new WebPushConfig(VapidKeys.of(P256.generate()), "mailto:ops@x.test"));
    disabled =
        new PushSubscriptionService(
            dsl,
            new PushSubscriptionRepositoryFactoryImpl(),
            new UserRepositoryFactoryImpl(),
            WebPushConfig.disabled());
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void fresh() {
    dsl.execute("TRUNCATE push_subscription, user_org_role, app_user RESTART IDENTITY CASCADE");
  }

  @Test
  void subscribe_isFreshThenIdempotent_refreshingKeysAndTokenGeneration() {
    UUID user = createUser();
    String endpoint = endpoint();
    String p1 = point();
    String p2 = point();

    PushSubscriptionService.SubscribeResult first =
        service.subscribe(user, endpoint, p1, auth(), "Pixel");
    assertTrue(first.created());
    assertEquals(0, first.subscription().tokenVersionAtSubscribe());

    users.incrementTokenVersion(user);
    PushSubscriptionService.SubscribeResult second =
        service.subscribe(user, endpoint, p2, auth(), "Pixel (Chrome 130)");

    assertFalse(second.created());
    assertEquals(first.subscription().id(), second.subscription().id(), "same row");
    assertEquals(p2, second.subscription().p256dh(), "keys refreshed");
    assertEquals(
        1, second.subscription().tokenVersionAtSubscribe(), "re-minted in the new generation");
    assertEquals("Pixel (Chrome 130)", second.subscription().userAgent());
    assertEquals(1, dsl.fetchCount(PUSH_SUBSCRIPTION));
  }

  @Test
  void anEndpointHeldByAnotherAccount_isMovedToTheCaller() {
    UUID alice = createUser();
    UUID bob = createUser();
    String endpoint = endpoint();
    service.subscribe(alice, endpoint, point(), auth(), "shared laptop");

    PushSubscriptionService.SubscribeResult moved =
        service.subscribe(bob, endpoint, point(), auth(), "shared laptop");

    assertFalse(moved.created());
    assertEquals(bob, moved.subscription().userId());
    assertTrue(
        service.listLive(alice).isEmpty(), "the browser belongs to whoever is signed in now");
    assertEquals(1, service.listLive(bob).size());
  }

  @Test
  void validation_endpointMustBeHttps_keysMustDecodeToAPointAndSixteenBytes() {
    UUID user = createUser();
    assertThrows(
        ValidationException.class, () -> service.subscribe(user, null, point(), auth(), null));
    assertThrows(
        ValidationException.class,
        () -> service.subscribe(user, "http://push.example/x", point(), auth(), null));
    assertThrows(
        ValidationException.class,
        () -> service.subscribe(user, "not a url", point(), auth(), null));
    // 64 bytes: not an uncompressed point
    assertThrows(
        ValidationException.class,
        () -> service.subscribe(user, endpoint(), b64(new byte[64]), auth(), null));
    // 65 bytes but off the curve
    byte[] off = P256.encodePoint((ECPublicKey) P256.generate().getPublic());
    off[20] ^= 0x7f;
    assertThrows(
        ValidationException.class,
        () -> service.subscribe(user, endpoint(), b64(off), auth(), null));
    assertThrows(
        ValidationException.class,
        () -> service.subscribe(user, endpoint(), point(), b64(new byte[15]), null));
    assertThrows(
        ValidationException.class, () -> service.subscribe(user, endpoint(), point(), "***", null));
    assertEquals(0, dsl.fetchCount(PUSH_SUBSCRIPTION), "nothing half-stored");
  }

  @Test
  void standardBase64Keys_areAcceptedAndStoredAsBase64Url() {
    UUID user = createUser();
    byte[] raw = P256.encodePoint((ECPublicKey) P256.generate().getPublic());
    String standard = Base64.getEncoder().encodeToString(raw); // +, /, = padding

    PushSubscription saved =
        service.subscribe(user, endpoint(), standard, auth(), null).subscription();

    assertEquals(b64(raw), saved.p256dh(), "normalized to the wire form the sender expects");
  }

  @Test
  void withNoKeyPair_subscribingIs503_andConfigSaysDisabled() {
    UUID user = createUser();
    assertFalse(disabled.config().enabled());
    assertThrows(
        ServiceUnavailableException.class,
        () -> disabled.subscribe(user, endpoint(), point(), auth(), null));
    assertTrue(service.config().enabled());
    assertEquals(65, Base64.getUrlDecoder().decode(service.config().publicKeyBase64Url()).length);
  }

  @Test
  void unsubscribe_isIdempotentAndOwnOnly_withNoOracle() {
    UUID alice = createUser();
    UUID bob = createUser();
    String endpoint = endpoint();
    service.subscribe(alice, endpoint, point(), auth(), null);

    service.unsubscribe(bob, endpoint); // not bob's — silently nothing
    assertEquals(1, dsl.fetchCount(PUSH_SUBSCRIPTION), "a foreign endpoint is untouched");

    service.unsubscribe(alice, endpoint);
    service.unsubscribe(alice, endpoint); // replay
    service.unsubscribe(alice, "https://push.example/never-existed");
    assertEquals(0, dsl.fetchCount(PUSH_SUBSCRIPTION));
    assertThrows(ValidationException.class, () -> service.unsubscribe(alice, " "));
  }

  @Test
  void liveMeans_activeUserAndCurrentTokenGeneration_theRowOutlivesEither() {
    UUID user = createUser();
    service.subscribe(user, endpoint(), point(), auth(), "phone");
    assertEquals(1, service.listLive(user).size());

    users.incrementTokenVersion(user); // logout-all / password change / de-privilege / disable
    assertTrue(service.listLive(user).isEmpty(), "silenced");
    assertEquals(1, dsl.fetchCount(PUSH_SUBSCRIPTION), "…but kept for the next re-subscribe");

    // Re-subscribing from the same browser revives it in place — no duplicate row.
    service.subscribe(user, firstEndpoint(), point(), auth(), "phone");
    assertEquals(1, service.listLive(user).size());
    assertEquals(1, dsl.fetchCount(PUSH_SUBSCRIPTION));

    users.setActive(user, false);
    assertTrue(service.listLive(user).isEmpty(), "a disabled account has no live device");
    users.setActive(user, true);
    assertEquals(1, service.listLive(user).size());
  }

  @Test
  void deletingTheUser_cascadesTheSubscriptions() {
    UUID user = createUser();
    service.subscribe(user, endpoint(), point(), auth(), null);
    service.subscribe(user, endpoint(), point(), auth(), null);
    assertEquals(2, dsl.fetchCount(PUSH_SUBSCRIPTION));

    users.deleteUser(user);

    assertEquals(0, dsl.fetchCount(PUSH_SUBSCRIPTION));
  }

  @Test
  void theListing_isOrderedOldestFirst_andCarriesNoCapabilityFieldsTheDtoWouldNeed() {
    UUID user = createUser();
    service.subscribe(user, endpoint(), point(), auth(), "first");
    service.subscribe(user, endpoint(), point(), auth(), "second");

    List<PushSubscription> live = service.listLive(user);

    assertEquals(
        List.of("first", "second"), live.stream().map(PushSubscription::userAgent).toList());
    assertNotEquals(live.get(0).endpoint(), live.get(1).endpoint());
  }

  // helpers

  private String firstEndpoint() {
    return dsl.select(PUSH_SUBSCRIPTION.ENDPOINT).from(PUSH_SUBSCRIPTION).fetchOne(0, String.class);
  }

  private static String endpoint() {
    return "https://push.example/send/" + UUID.randomUUID();
  }

  private static String point() {
    KeyPair browser = P256.generate();
    return b64(P256.encodePoint((ECPublicKey) browser.getPublic()));
  }

  private static String auth() {
    byte[] raw = new byte[16];
    new SecureRandom().nextBytes(raw);
    return b64(raw);
  }

  private static String b64(byte[] raw) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private UUID createUser() {
    UUID id = UUID.randomUUID();
    dsl.insertInto(APP_USER)
        .set(APP_USER.ID, id)
        .set(APP_USER.EMAIL, id + "@acme.test")
        .set(APP_USER.PASSWORD_HASH, "x")
        .set(APP_USER.ACTOR_TYPE, ActorType.USER)
        .execute();
    return id;
  }
}
