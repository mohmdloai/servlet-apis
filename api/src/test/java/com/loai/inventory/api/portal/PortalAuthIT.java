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
  void refreshRotates_andReuseOfOldTokenIsRejected() {
    String slug = createOrg();
    createCustomer(slug, "nadia@acme.test");
    CustomerAuthService.SessionResult s1 = login(slug, "nadia@acme.test");

    CustomerAuthService.SessionResult s2 = authService.refresh(s1.refreshToken(), "1.2.3.4");
    assertNotNull(s2.refreshToken());
    assertFalse(s1.refreshToken().equals(s2.refreshToken()), "refresh rotates the token");

    // Reusing the rotated-away token is a hard 401.
    assertThrows(
        AuthenticationException.class, () -> authService.refresh(s1.refreshToken(), "1.2.3.4"));
    // The rotated-current token still works.
    assertNotNull(authService.refresh(s2.refreshToken(), "1.2.3.4"));
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
