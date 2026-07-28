package com.loai.inventory.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.AuthenticationException;
import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.common.security.PasswordHasher;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.AppUserTokenPurpose;
import com.loai.inventory.repository.AppUserMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.ImpersonationEventRepositoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.service.auth.AccountService;
import com.loai.inventory.service.auth.AuthMailer;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.CredentialTokenService;
import com.loai.inventory.service.auth.RefreshTokenStore;
import com.loai.inventory.service.email.EmailMessage;
import com.loai.inventory.service.email.EmailSender;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.OffsetDateTime;
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
 * End-to-end coverage of {@link AccountService} against a real database + Redis: self-service
 * registration, the forgot-password → reset round-trip (including no-enumeration, single-use, and
 * expiry), and invite activation. These are the backend gaps that make provisioning and self-serve
 * onboarding usable (stories/11_st_platform_admin_console.md).
 */
@Testcontainers
class AccountServiceIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

  private static final Pattern TOKEN_IN_URL = Pattern.compile("token=([^\"&\\s]+)");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static JedisPool jedisPool;
  static AccountService accountService;
  static AuthService authService;
  static CredentialTokenService credentialTokenService;
  static CapturingEmailSender emailSender;

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

    jedisPool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
    RefreshTokenStore refreshTokenStore = new RefreshTokenStore(jedisPool);
    JwtUtil jwtUtil = new JwtUtil(Base64.getEncoder().encodeToString(new byte[48]), 900_000L);
    authService =
        new AuthService(
            new UserRepositoryImpl(dsl),
            refreshTokenStore,
            jwtUtil,
            new ImpersonationEventRepositoryImpl(dsl),
            300_000L);
    credentialTokenService =
        new CredentialTokenService(
            dsl,
            new AppUserMagicTokenRepositoryFactoryImpl(),
            "http://localhost:8080",
            Duration.ofMinutes(120),
            Duration.ofDays(7),
            Duration.ofHours(48));
    emailSender = new CapturingEmailSender();
    accountService =
        new AccountService(
            dsl,
            new UserRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            credentialTokenService,
            new AuthMailer(emailSender),
            authService,
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            new com.loai.inventory.service.platform.OrgStatusService(
                jedisPool, dsl, new OrgRepositoryFactoryImpl()),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE app_user_magic_token, user_org_role, user_system_role, org, app_user"
            + " RESTART IDENTITY CASCADE");
    emailSender.messages.clear();
  }

  // Registration — verify-to-activate (story 88)

  @Test
  void register_createsUnverifiedAccount_sendsLink_blocksLogin() {
    AppUser created = accountService.register("new@x.io", "password1", null);
    assertEquals("new@x.io", created.getEmail());
    assertNull(created.getEmailVerifiedAt(), "self-serve registration is born unverified");
    assertEquals(1, emailSender.messages.size(), "the verification link was emailed");
    assertEquals(
        1,
        dsl.fetchCount(
            DSL.table("app_user_magic_token"), DSL.condition("purpose = ?", "EMAIL_VERIFY")));

    // Correct password before verification → 403 (the reserved status the frontend keys on)...
    assertThrows(
        AuthorizationException.class,
        () -> authService.login("new@x.io", "password1", "junit", "1.2.3.4"));
    // ...but a wrong password is still the plain 401 — the gate runs AFTER the password check,
    // so the 403 is never a password oracle.
    assertThrows(
        AuthenticationException.class,
        () -> authService.login("new@x.io", "wrong-pass1", "junit", "1.2.3.4"));
  }

  @Test
  void verifyEmail_roundTrip_stampsSignsInAndIsOneShot() {
    accountService.register("verify@x.io", "password1", null);
    String token = extractToken(emailSender.messages.get(0).html());

    AuthService.LoginResult result =
        accountService.verifyEmail(token, OffsetDateTime.now(), "junit", "1.2.3.4");
    assertNotNull(result.accessToken(), "the click signs the user in");
    assertNotNull(result.user().getEmailVerifiedAt());

    // Normal logins now succeed, and the link is one-shot.
    assertNotNull(authService.login("verify@x.io", "password1", "junit", "1.2.3.4"));
    assertThrows(
        ValidationException.class,
        () -> accountService.verifyEmail(token, OffsetDateTime.now(), "junit", "1.2.3.4"));
  }

  @Test
  void verifyEmail_expiredToken_rejected() {
    UUID userId = seedUser("staleverify@x.io", "password1", true);
    String raw = "expired-verify-token";
    dsl.execute(
        "INSERT INTO app_user_magic_token(id,user_id,token_hash,purpose,expires_at)"
            + " VALUES (?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        userId,
        RefreshTokenStore.hashToken(raw),
        "EMAIL_VERIFY",
        OffsetDateTime.now().minusHours(1).toString());
    assertThrows(
        ValidationException.class,
        () -> accountService.verifyEmail(raw, OffsetDateTime.now(), "junit", "1.2.3.4"));
  }

  @Test
  void resendVerification_uniformNoSend_forUnknownVerifiedAndDisabled() {
    // Unknown email → nothing.
    accountService.resendVerification("ghost@x.io", OffsetDateTime.now());
    // Already-verified account → nothing.
    UUID verified = seedUser("done@x.io", "password1", true);
    dsl.execute("UPDATE app_user SET email_verified_at = now() WHERE id = ?", verified);
    accountService.resendVerification("done@x.io", OffsetDateTime.now());
    // Disabled account → nothing.
    seedUser("off@x.io", "password1", false);
    accountService.resendVerification("off@x.io", OffsetDateTime.now());

    assertTrue(emailSender.messages.isEmpty(), "no resend leaks account state");
    assertEquals(0, dsl.fetchCount(DSL.table("app_user_magic_token")));
  }

  @Test
  void resendVerification_supersedesThePriorLink() {
    accountService.register("again@x.io", "password1", null);
    String first = extractToken(emailSender.messages.get(0).html());

    accountService.resendVerification("again@x.io", OffsetDateTime.now());
    assertEquals(2, emailSender.messages.size());
    String second = extractToken(emailSender.messages.get(1).html());

    // Only the latest link redeems: the superseded one is dead, the fresh one works.
    assertThrows(
        ValidationException.class,
        () -> accountService.verifyEmail(first, OffsetDateTime.now(), "junit", "1.2.3.4"));
    assertNotNull(
        accountService.verifyEmail(second, OffsetDateTime.now(), "junit", "1.2.3.4").accessToken());
  }

  @Test
  void register_withOrg_makesOwner_bornInactive() {
    AppUser created = accountService.register("owner@x.io", "password1", "My Shop");
    UUID userId = created.getId();
    assertEquals(1, dsl.fetchCount(DSL.table("org")));
    UUID orgId = dsl.select(DSL.field("id", UUID.class)).from("org").fetchOne(0, UUID.class);
    // role is the org_role enum — cast the bind so the comparison type-checks.
    assertEquals(
        1,
        dsl.fetchCount(
            DSL.table("user_org_role"),
            DSL.condition(
                "user_id = ? and org_id = ? and role = ?::org_role", userId, orgId, "OWNER")));
    // Story 89: born INACTIVE (no live public storefront before inbox proof), not suspended.
    assertEquals(
        1,
        dsl.fetchCount(
            DSL.table("org"),
            DSL.condition("id = ? and active = false and suspended_at is null", orgId)));
  }

  @Test
  void verifyEmail_activatesTheRegistrationOrg_andInvalidatesTheMirror() {
    accountService.register("shopkeeper@x.io", "password1", "Go Live Shop");
    UUID orgId = dsl.select(DSL.field("id", UUID.class)).from("org").fetchOne(0, UUID.class);
    // Seed a stale "inactive" mirror entry — activation must drop it, not wait out the TTL.
    try (var jedis = jedisPool.getResource()) {
      jedis.set("org:active:" + orgId, "0");
    }
    String token = extractToken(emailSender.messages.get(0).html());

    accountService.verifyEmail(token, OffsetDateTime.now(), "junit", "1.2.3.4");

    assertEquals(
        1, dsl.fetchCount(DSL.table("org"), DSL.condition("id = ? and active = true", orgId)));
    try (var jedis = jedisPool.getResource()) {
      assertEquals(null, jedis.get("org:active:" + orgId), "the status mirror was invalidated");
    }
  }

  @Test
  void verifyEmail_neverResurrectsAnAdminSuspendedOrg() {
    accountService.register("suspended@x.io", "password1", "Sketchy Shop");
    UUID orgId = dsl.select(DSL.field("id", UUID.class)).from("org").fetchOne(0, UUID.class);
    // Admin suspends before the owner verifies: suspended_at stamps the state as admin-owned.
    dsl.execute(
        "UPDATE org SET suspended_at = now(), suspended_reason = 'spam' WHERE id = ?", orgId);
    String token = extractToken(emailSender.messages.get(0).html());

    accountService.verifyEmail(token, OffsetDateTime.now(), "junit", "1.2.3.4");

    assertEquals(
        1,
        dsl.fetchCount(
            DSL.table("org"),
            DSL.condition("id = ? and active = false and suspended_at is not null", orgId)),
        "a verify click must never undo an admin suspension");
  }

  @Test
  void register_duplicateEmail_conflict() {
    accountService.register("dupe@x.io", "password1", null);
    assertThrows(
        ConflictException.class, () -> accountService.register("dupe@x.io", "password1", null));
  }

  @Test
  void register_weakPassword_rejected() {
    assertThrows(
        ValidationException.class, () -> accountService.register("weak@x.io", "short", null));
  }

  @Test
  void register_invalidEmail_rejected() {
    assertThrows(
        ValidationException.class,
        () -> accountService.register("not-an-email", "password1", null));
  }

  // Forgot / reset

  @Test
  void forgotThenReset_changesPassword_singleUse() {
    seedUser("reset@x.io", "oldpass12", true);
    accountService.requestPasswordReset("reset@x.io", OffsetDateTime.now());

    assertEquals(1, emailSender.messages.size(), "an existing account gets a reset email");
    String token = extractToken(emailSender.messages.get(0).html());

    AuthService.LoginResult result =
        accountService.resetPassword(token, "newpass12", OffsetDateTime.now(), "junit", "1.2.3.4");
    assertNotNull(result.accessToken());

    // New password works; old password no longer does.
    assertNotNull(authService.login("reset@x.io", "newpass12", "junit", "1.2.3.4"));
    assertThrows(
        AuthenticationException.class,
        () -> authService.login("reset@x.io", "oldpass12", "junit", "1.2.3.4"));

    // The token is one-shot: a second redeem is rejected.
    assertThrows(
        ValidationException.class,
        () ->
            accountService.resetPassword(
                token, "another12", OffsetDateTime.now(), "junit", "1.2.3.4"));
  }

  @Test
  void forgot_unknownEmail_noEmail_noToken() {
    accountService.requestPasswordReset("ghost@x.io", OffsetDateTime.now());
    assertTrue(emailSender.messages.isEmpty(), "no email for an unknown account");
    assertEquals(0, dsl.fetchCount(DSL.table("app_user_magic_token")));
  }

  @Test
  void forgot_disabledAccount_noToken() {
    seedUser("disabled@x.io", "oldpass12", false);
    accountService.requestPasswordReset("disabled@x.io", OffsetDateTime.now());
    assertTrue(emailSender.messages.isEmpty());
    assertEquals(0, dsl.fetchCount(DSL.table("app_user_magic_token")));
  }

  @Test
  void reset_invalidToken_rejected() {
    assertThrows(
        ValidationException.class,
        () ->
            accountService.resetPassword(
                "not-a-real-token", "password1", OffsetDateTime.now(), "junit", "1.2.3.4"));
  }

  @Test
  void reset_expiredToken_rejected() {
    UUID userId = seedUser("stale@x.io", "oldpass12", true);
    String raw = "expired-raw-token";
    dsl.execute(
        "INSERT INTO app_user_magic_token(id,user_id,token_hash,purpose,expires_at)"
            + " VALUES (?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        userId,
        RefreshTokenStore.hashToken(raw),
        "PASSWORD_RESET",
        OffsetDateTime.now().minusHours(1).toString());
    assertThrows(
        ValidationException.class,
        () ->
            accountService.resetPassword(
                raw, "password1", OffsetDateTime.now(), "junit", "1.2.3.4"));
  }

  // Invite activation

  @Test
  void activate_setsPasswordAndLogsIn() {
    UUID userId = seedUser("invited@x.io", "!unusable", true);
    String raw =
        credentialTokenService.mintAutonomous(
            userId, AppUserTokenPurpose.INVITE, OffsetDateTime.now());

    AuthService.LoginResult result =
        accountService.activate(raw, "chosenpw12", OffsetDateTime.now(), "junit", "1.2.3.4");
    assertNotNull(result.accessToken());
    assertNotNull(authService.login("invited@x.io", "chosenpw12", "junit", "1.2.3.4"));
  }

  @Test
  void activate_wrongPurposeToken_rejected() {
    UUID userId = seedUser("mix@x.io", "oldpass12", true);
    // A PASSWORD_RESET token must not be redeemable at the activate (INVITE) endpoint.
    String raw =
        credentialTokenService.mintAutonomous(
            userId, AppUserTokenPurpose.PASSWORD_RESET, OffsetDateTime.now());
    assertThrows(
        ValidationException.class,
        () -> accountService.activate(raw, "password1", OffsetDateTime.now(), "junit", "1.2.3.4"));
  }

  // Self-heal + purge (story 88)

  @Test
  void passwordReset_selfHeals_verification() {
    // seedUser leaves email_verified_at NULL — completing a reset proves the inbox and stamps it.
    seedUser("heal@x.io", "oldpass12", true);
    accountService.requestPasswordReset("heal@x.io", OffsetDateTime.now());
    String token = extractToken(emailSender.messages.get(0).html());
    accountService.resetPassword(token, "newpass12", OffsetDateTime.now(), "junit", "1.2.3.4");

    assertNotNull(
        dsl.select(DSL.field("email_verified_at"))
            .from("app_user")
            .where(DSL.condition("email = ?", "heal@x.io"))
            .fetchOne(0),
        "redeeming an emailed reset link proves the inbox");
    assertNotNull(authService.login("heal@x.io", "newpass12", "junit", "1.2.3.4"));
  }

  @Test
  void purge_deletesStaleUnverified_withSoleMemberOrg_sparesTheRest() {
    // Stale unverified + sole-member org + no live token → purged, org and all.
    UUID stale = seedUser("stale@x.io", "password1", true);
    backdate(stale, 8);
    UUID orgId = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO org(id,name,slug,active) VALUES (?,?,?,true)",
        orgId,
        "Ghost Shop",
        "ghost-shop");
    dsl.execute(
        "INSERT INTO user_org_role(user_id,org_id,role) VALUES (?,?,?::org_role)",
        stale,
        orgId,
        "OWNER");

    // Stale unverified but holding a LIVE verify token → shielded.
    UUID shielded = seedUser("shielded@x.io", "password1", true);
    backdate(shielded, 8);
    credentialTokenService.mintAutonomous(
        shielded, AppUserTokenPurpose.EMAIL_VERIFY, OffsetDateTime.now());

    // Young unverified → not yet eligible.
    seedUser("young@x.io", "password1", true);

    // Stale but verified → never touched.
    UUID done = seedUser("veteran@x.io", "password1", true);
    backdate(done, 30);
    dsl.execute("UPDATE app_user SET email_verified_at = now() WHERE id = ?", done);

    int purged = accountService.purgeUnverified(OffsetDateTime.now().minusDays(7), 100);

    assertEquals(1, purged);
    assertEquals(
        0, dsl.fetchCount(DSL.table("app_user"), DSL.condition("email = ?", "stale@x.io")));
    assertEquals(0, dsl.fetchCount(DSL.table("org")), "the sole-member org went with its owner");
    assertEquals(3, dsl.fetchCount(DSL.table("app_user")), "shielded, young, and veteran survive");
  }

  @Test
  void purge_neverCascadesIntoAMultiMemberOrg() {
    UUID stale = seedUser("leaver@x.io", "password1", true);
    backdate(stale, 8);
    UUID mate = seedUser("mate@x.io", "password1", true);
    dsl.execute("UPDATE app_user SET email_verified_at = now() WHERE id = ?", mate);
    UUID orgId = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO org(id,name,slug,active) VALUES (?,?,?,true)", orgId, "Shared", "shared");
    dsl.execute(
        "INSERT INTO user_org_role(user_id,org_id,role) VALUES (?,?,?::org_role)",
        stale,
        orgId,
        "OWNER");
    dsl.execute(
        "INSERT INTO user_org_role(user_id,org_id,role) VALUES (?,?,?::org_role)",
        mate,
        orgId,
        "MANAGER");

    int purged = accountService.purgeUnverified(OffsetDateTime.now().minusDays(7), 100);

    assertEquals(1, purged, "the stale user still purges");
    assertEquals(1, dsl.fetchCount(DSL.table("org")), "a multi-member org is never deleted");
    assertEquals(
        1,
        dsl.fetchCount(DSL.table("user_org_role")),
        "only the purged user's role went (FK cascade); the mate keeps theirs");
  }

  // helpers

  private void backdate(UUID userId, int days) {
    dsl.execute(
        "UPDATE app_user SET created_at = now() - make_interval(days => ?) WHERE id = ?",
        days,
        userId);
  }

  private UUID seedUser(String email, String rawPassword, boolean active) {
    UUID id = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO app_user(id,email,password_hash,actor_type,active,token_version)"
            + " VALUES (?,?,?,?::actor_type,?,?)",
        id,
        email,
        PasswordHasher.hash(rawPassword),
        "USER",
        active,
        0);
    return id;
  }

  private static String extractToken(String html) {
    Matcher m = TOKEN_IN_URL.matcher(html);
    assertTrue(m.find(), "email HTML should carry a token link");
    return m.group(1);
  }

  private static final class CapturingEmailSender implements EmailSender {
    final List<EmailMessage> messages = new ArrayList<>();

    @Override
    public void send(EmailMessage message) {
      messages.add(message);
    }
  }
}
