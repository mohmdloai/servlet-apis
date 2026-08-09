package com.loai.inventory.api.portal;

import static com.loai.inventory.repository.generated.Tables.CUSTOMER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.domain.model.Customer;
import com.loai.inventory.repository.CustomerRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.service.CustomerPortalService;
import com.loai.inventory.service.auth.CustomerAuthService;
import com.loai.inventory.service.auth.CustomerOtpStore;
import com.loai.inventory.service.auth.CustomerSessionStore;
import com.loai.inventory.service.email.EmailMessage;
import com.loai.inventory.service.email.EmailSender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Claims;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;

/**
 * The portal auth core end-to-end against real Postgres + Redis, driving {@link
 * CustomerAuthService} and its stores (no Tomcat). Covers the acceptance criteria of {@code
 * stories/portal_auth_core.md}: happy path + {@code email_verified_at} stamp (AC1), enumeration
 * uniformity (AC2), brute-force / single-use (AC3), session rotation / reuse-revoke / per-device
 * kill / logout-all (AC4), and {@code (org, customer)} scoping (AC6).
 */
@Testcontainers
class PortalAuthIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

  // 32 zero-bytes → a valid ≥32-byte secret after Base64 decode (the JwtUtil startup check).
  static final String CUSTOMER_SECRET = Base64.getEncoder().encodeToString(new byte[32]);
  static final long ACCESS_TTL_MILLIS = 15 * 60 * 1000L;
  static final Pattern SIX_DIGITS = Pattern.compile("\\b(\\d{6})\\b");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static JedisPool jedisPool;
  static JwtUtil customerJwtUtil;
  static CustomerOtpStore otpStore;
  static CustomerSessionStore sessionStore;
  static CustomerPortalService portalService;

  /** Captures the OTP emails the notification sweeper transmits. */
  static final class CapturingEmailSender implements EmailSender {
    final List<EmailMessage> captured = new ArrayList<>();

    @Override
    public void send(EmailMessage message) {
      captured.add(message);
    }
  }

  static CapturingEmailSender emailSender;
  static CustomerAuthService authService;

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

    jedisPool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
    customerJwtUtil = new JwtUtil(CUSTOMER_SECRET, ACCESS_TTL_MILLIS, "customer");
    otpStore = new CustomerOtpStore(jedisPool);
    sessionStore = new CustomerSessionStore(jedisPool, 30 * 24 * 3600);
    portalService =
        new CustomerPortalService(
            dsl,
            new CustomerRepositoryFactoryImpl(),
            new com.loai.inventory.repository.SalesOrderRepositoryFactoryImpl(),
            new com.loai.inventory.repository.SalesInvoiceRepositoryFactoryImpl(),
            new com.loai.inventory.repository.CustomerAddressRepositoryFactoryImpl(),
            new com.loai.inventory.repository.ProductListingRepositoryFactoryImpl(),
            new com.loai.inventory.repository.OrgRepositoryFactoryImpl(),
            new com.loai.inventory.repository.FulfillmentRepositoryFactoryImpl(),
            null);

    emailSender = new CapturingEmailSender();
    authService =
        new CustomerAuthService(
            dsl,
            new CustomerRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            otpStore,
            sessionStore,
            customerJwtUtil,
            emailSender,
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            /* perEmailSendLimit= */ 5,
            /* perEmailWindowSeconds= */ 60);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
    if (jedisPool != null) jedisPool.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE customer_magic_token, notification_delivery_email, notification_delivery_in_app,"
            + " notification_delivery, notification, customer, org RESTART IDENTITY CASCADE");
    try (var jedis = jedisPool.getResource()) {
      jedis.flushAll();
    }
    emailSender.captured.clear();
  }

  // AC1: happy path

  @Test
  void happyPath_requestVerifyMintsSessionAndStampsVerified() {
    String slug = createOrg();
    UUID customerId = createCustomer(slug, "nadia@acme.test");

    authService.requestCode(slug, "nadia@acme.test");
    String code = drainCode();
    assertNotNull(code, "a 6-digit code was emailed");

    CustomerAuthService.SessionResult session =
        authService.verifyCode(slug, "nadia@acme.test", code, "ua", "1.2.3.4");

    assertNotNull(session.accessToken());
    assertNotNull(session.refreshToken());
    assertEquals("nadia@acme.test", session.customer().getEmail());

    // Access token carries the right, minimal claims (no roles) — barriers proven in the isolation
    // test; here we assert identity + scope.
    Claims claims = customerJwtUtil.parseAndVerify(session.accessToken());
    assertEquals(customerId.toString(), claims.getSubject());
    assertTrue(claims.getAudience().contains("customer"));
    assertEquals(orgId(slug).toString(), claims.get("org_id", String.class));
    assertNull(claims.get("org_roles"), "customer token carries no org roles");
    assertNull(claims.get("system_roles"), "customer token carries no system roles");

    // Version primed → the filter's fail-closed check passes.
    int version = claims.get("token_version", Integer.class);
    assertTrue(authService.isTokenVersionValid(orgId(slug), customerId, version));

    // email_verified_at stamped.
    assertNotNull(
        dsl.select(CUSTOMER.EMAIL_VERIFIED_AT)
            .from(CUSTOMER)
            .where(CUSTOMER.ID.eq(customerId))
            .fetchOne(CUSTOMER.EMAIL_VERIFIED_AT),
        "first verify stamps email_verified_at");
  }

  // AC2: enumeration resistance

  @Test
  void unknownEmail_sendsNothing_butVerifyStillGenericFails() {
    String slug = createOrg();
    // No customer row for this address.
    authService.requestCode(slug, "ghost@nowhere.test");
    assertTrue(emailSender.captured.isEmpty(), "no email for an unknown address");

    assertThrows(
        AuthenticationException.class,
        () -> authService.verifyCode(slug, "ghost@nowhere.test", "000000", "ua", "ip"));
  }

  // AC3: brute force + single use

  @Test
  void sixthAttemptInvalidatesChallenge_evenWithTheRightCode() {
    String slug = createOrg();
    createCustomer(slug, "nadia@acme.test");
    authService.requestCode(slug, "nadia@acme.test");
    String code = drainCode();

    for (int i = 0; i < 5; i++) {
      assertThrows(
          AuthenticationException.class,
          () -> authService.verifyCode(slug, "nadia@acme.test", "111111", "ua", "ip"),
          "wrong attempt " + i);
    }
    // 6th attempt — even with the correct code — is rejected and the challenge is gone.
    assertThrows(
        AuthenticationException.class,
        () -> authService.verifyCode(slug, "nadia@acme.test", code, "ua", "ip"));
    assertThrows(
        AuthenticationException.class,
        () -> authService.verifyCode(slug, "nadia@acme.test", code, "ua", "ip"),
        "challenge invalidated — must re-request");
  }

  @Test
  void codeIsSingleUse() {
    String slug = createOrg();
    createCustomer(slug, "nadia@acme.test");
    authService.requestCode(slug, "nadia@acme.test");
    String code = drainCode();

    authService.verifyCode(slug, "nadia@acme.test", code, "ua", "ip"); // consumes it
    assertThrows(
        AuthenticationException.class,
        () -> authService.verifyCode(slug, "nadia@acme.test", code, "ua", "ip"),
        "the same code cannot be replayed");
  }

  // AC4: session security

  @Test
  void refreshRotates_andTheNewTokenKeepsWorking() {
    String slug = createOrg();
    createCustomer(slug, "nadia@acme.test");
    CustomerAuthService.SessionResult s1 = login(slug, "nadia@acme.test");

    CustomerAuthService.SessionResult s2 = authService.refresh(s1.refreshToken(), "1.2.3.4");
    assertNotNull(s2.refreshToken());
    assertFalse(s1.refreshToken().equals(s2.refreshToken()), "refresh rotates the token");

    // The rotated-current token still works — rotation alone revokes nothing.
    assertNotNull(authService.refresh(s2.refreshToken(), "1.2.3.4"));
  }

  /**
   * D2: reuse of a rotated-away token is proof that two holders exist, so the whole family dies —
   * including the token the *first* presenter (in a theft, the attacker) just minted. Previously
   * this only logged and 401'd, which left the thief's token live and made reuse detection a no-op.
   *
   * <p>Proof, though, only once the concurrent-refresh grace window has closed — hence the key
   * surgery below, which does deterministically what the {@code crt:rotated-recent:} TTL does ten
   * seconds later.
   */
  @Test
  void reuseOfRotatedToken_burnsTheWholeFamily() {
    String slug = createOrg();
    createCustomer(slug, "nadia@acme.test");
    CustomerAuthService.SessionResult s1 = login(slug, "nadia@acme.test");
    UUID fam =
        UUID.fromString(customerJwtUtil.parseAndVerify(s1.accessToken()).get("fam", String.class));

    // The attacker refreshes first: s1 → s2.
    CustomerAuthService.SessionResult s2 = authService.refresh(s1.refreshToken(), "9.9.9.9");

    elapseGraceWindow(s1.refreshToken());

    // The real customer then presents the stale s1.
    assertThrows(
        AuthenticationException.class, () -> authService.refresh(s1.refreshToken(), "1.2.3.4"));

    // s2 must be dead too, and the family's outstanding access tokens denylisted.
    assertThrows(
        AuthenticationException.class,
        () -> authService.refresh(s2.refreshToken(), "9.9.9.9"),
        "the family is burned on proven reuse — the attacker's fresh token included");
    assertTrue(authService.isDeviceRevoked(fam), "the family's access tokens are killed too");
  }

  /**
   * The benign twin, and why the window exists: the portal's server-side bounce route can refresh
   * while the page's own client is refreshing from the same cookie jar. The loser 401s — but the
   * winner's seconds-old token has to keep working, or a shopper is signed out of every device by a
   * race that involved no attacker at all.
   */
  @Test
  void concurrentRefresh_401sTheLoserButLeavesTheWinnersSessionAlive() {
    String slug = createOrg();
    createCustomer(slug, "nadia@acme.test");
    CustomerAuthService.SessionResult s1 = login(slug, "nadia@acme.test");
    UUID fam =
        UUID.fromString(customerJwtUtil.parseAndVerify(s1.accessToken()).get("fam", String.class));

    CustomerAuthService.SessionResult s2 = authService.refresh(s1.refreshToken(), "1.2.3.4");

    // Inside the real window — no sleep, no key surgery.
    assertThrows(
        AuthenticationException.class, () -> authService.refresh(s1.refreshToken(), "1.2.3.4"));

    assertNotNull(
        authService.refresh(s2.refreshToken(), "1.2.3.4"),
        "the winner's fresh token must survive the loser's 401");
    assertFalse(authService.isDeviceRevoked(fam), "a benign race must not denylist the device");
  }

  /** Expire the grace marker for this token — the deterministic stand-in for waiting it out. */
  private static void elapseGraceWindow(String rawRefreshToken) {
    try (var jedis = jedisPool.getResource()) {
      jedis.del("crt:rotated-recent:" + CustomerSessionStore.hashRefresh(rawRefreshToken));
    }
  }

  /** An unknown refresh token is not evidence — it 401s and must revoke nothing. */
  @Test
  void unknownRefreshToken_401sButLeavesTheSessionAlone() {
    String slug = createOrg();
    createCustomer(slug, "nadia@acme.test");
    CustomerAuthService.SessionResult s = login(slug, "nadia@acme.test");

    assertThrows(
        AuthenticationException.class,
        () -> authService.refresh(UUID.randomUUID().toString(), "1.2.3.4"));

    assertNotNull(authService.refresh(s.refreshToken(), "1.2.3.4"), "the live session survives");
  }

  @Test
  void logout_killsThisDeviceAccessImmediately() {
    String slug = createOrg();
    createCustomer(slug, "nadia@acme.test");
    CustomerAuthService.SessionResult s = login(slug, "nadia@acme.test");
    UUID fam =
        UUID.fromString(customerJwtUtil.parseAndVerify(s.accessToken()).get("fam", String.class));

    assertFalse(authService.isDeviceRevoked(fam));
    authService.logout(s.refreshToken());
    assertTrue(authService.isDeviceRevoked(fam), "logout denylists the device's access token");
    assertThrows(
        AuthenticationException.class, () -> authService.refresh(s.refreshToken(), "1.2.3.4"));
  }

  @Test
  void logoutAll_bumpsVersionSoOldAccessTokensAreRevoked() {
    String slug = createOrg();
    UUID customerId = createCustomer(slug, "nadia@acme.test");
    CustomerAuthService.SessionResult s = login(slug, "nadia@acme.test");
    int version =
        customerJwtUtil.parseAndVerify(s.accessToken()).get("token_version", Integer.class);
    assertTrue(authService.isTokenVersionValid(orgId(slug), customerId, version));

    authService.logoutAll(orgId(slug), customerId);

    assertFalse(
        authService.isTokenVersionValid(orgId(slug), customerId, version),
        "logout-all bumps the version — the old token is revoked");
  }

  @Test
  void revokeSession_killsOnlyThatDevice() {
    String slug = createOrg();
    UUID customerId = createCustomer(slug, "nadia@acme.test");
    CustomerAuthService.SessionResult phone = login(slug, "nadia@acme.test");
    CustomerAuthService.SessionResult laptop = login(slug, "nadia@acme.test");
    UUID phoneFam = familyOf(phone);
    UUID laptopFam = familyOf(laptop);
    assertEquals(2, authService.listSessions(orgId(slug), customerId).size());

    authService.revokeSession(orgId(slug), customerId, phoneFam);

    // The revoked device is dead on both axes — its access token is denylisted now, and its
    // refresh no longer rotates.
    assertTrue(authService.isDeviceRevoked(phoneFam));
    assertThrows(
        AuthenticationException.class, () -> authService.refresh(phone.refreshToken(), "1.2.3.4"));

    // The other device is untouched: this is a per-device revoke, not logout-all.
    assertFalse(authService.isDeviceRevoked(laptopFam));
    assertNotNull(authService.refresh(laptop.refreshToken(), "1.2.3.4"));
    assertEquals(1, authService.listSessions(orgId(slug), customerId).size());
  }

  @Test
  void revokeSession_leavesTokenVersionAlone() {
    String slug = createOrg();
    UUID customerId = createCustomer(slug, "nadia@acme.test");
    CustomerAuthService.SessionResult s = login(slug, "nadia@acme.test");
    int version =
        customerJwtUtil.parseAndVerify(s.accessToken()).get("token_version", Integer.class);

    authService.revokeSession(orgId(slug), customerId, familyOf(s));

    // The distinction from logout-all: the version survives, so sibling devices keep validating.
    assertTrue(authService.isTokenVersionValid(orgId(slug), customerId, version));
  }

  @Test
  void revokeSession_unknownFamily_is404() {
    String slug = createOrg();
    UUID customerId = createCustomer(slug, "nadia@acme.test");
    login(slug, "nadia@acme.test");

    assertThrows(
        NotFoundException.class,
        () -> authService.revokeSession(orgId(slug), customerId, UUID.randomUUID()));
  }

  @Test
  void revokeSession_anotherCustomersFamily_is404AndRevokesNothing() {
    String slug = createOrg();
    UUID mine = createCustomer(slug, "nadia@acme.test");
    createCustomer(slug, "omar@acme.test");
    CustomerAuthService.SessionResult theirs = login(slug, "omar@acme.test");
    UUID theirFam = familyOf(theirs);

    // Guessing a family id you don't own must not sign that person out.
    assertThrows(
        NotFoundException.class, () -> authService.revokeSession(orgId(slug), mine, theirFam));
    assertFalse(authService.isDeviceRevoked(theirFam));
    assertNotNull(authService.refresh(theirs.refreshToken(), "1.2.3.4"));
  }

  // AC6: scoping

  @Test
  void profileReadIsOrgScoped_customerOfOrgAUnreachableViaOrgB() {
    String slugA = createOrg();
    String slugB = createOrg();
    UUID custA = createCustomer(slugA, "a@acme.test");

    // Correct org resolves; the foreign org id does not.
    Customer read = portalService.me(orgId(slugA), custA);
    assertEquals("a@acme.test", read.getEmail());
    assertThrows(NotFoundException.class, () -> portalService.me(orgId(slugB), custA));
  }

  // helpers

  private CustomerAuthService.SessionResult login(String slug, String email) {
    authService.requestCode(slug, email);
    String code = drainCode();
    return authService.verifyCode(slug, email, code, "ua", "1.2.3.4");
  }

  /** Pull the 6-digit code out of the most recently-sent email (sent synchronously), or null. */
  private String drainCode() {
    if (emailSender.captured.isEmpty()) {
      return null;
    }
    String html = emailSender.captured.get(emailSender.captured.size() - 1).html();
    Matcher m = SIX_DIGITS.matcher(html);
    return m.find() ? m.group(1) : null;
  }

  /** The session family baked into a login's access token — the handle the device list exposes. */
  private UUID familyOf(CustomerAuthService.SessionResult s) {
    return UUID.fromString(
        customerJwtUtil.parseAndVerify(s.accessToken()).get("fam", String.class));
  }

  private String createOrg() {
    UUID id = UUID.randomUUID();
    String slug = "store-" + id;
    dsl.insertInto(ORG).set(ORG.ID, id).set(ORG.NAME, "Store").set(ORG.SLUG, slug).execute();
    return slug;
  }

  private UUID orgId(String slug) {
    return dsl.select(ORG.ID).from(ORG).where(ORG.SLUG.eq(slug)).fetchOne(ORG.ID);
  }

  private UUID createCustomer(String slug, String email) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(CUSTOMER)
        .set(CUSTOMER.ID, id)
        .set(CUSTOMER.ORG_ID, orgId(slug))
        .set(CUSTOMER.NAME, "Nadia")
        .set(CUSTOMER.EMAIL, email)
        .execute();
    return id;
  }
}
