package com.loai.inventory.api.admin;

import static com.loai.inventory.repository.generated.Tables.APP_USER;
import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.handler.OrgAdminHandler;
import com.loai.inventory.api.servlet.handler.UserAdminHandler;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.AppUserTokenPurpose;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.OrgStatus;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.repository.AppUserMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.ImpersonationEventRepositoryImpl;
import com.loai.inventory.repository.OrgHealthRepositoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgTimelineRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformAuditRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.service.auth.AccountService;
import com.loai.inventory.service.auth.AuthMailer;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.CredentialTokenService;
import com.loai.inventory.service.auth.RefreshTokenStore;
import com.loai.inventory.service.email.EmailException;
import com.loai.inventory.service.email.EmailMessage;
import com.loai.inventory.service.email.EmailSender;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.loai.inventory.service.platform.OrgStatusService;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.PlatformOrgService;
import com.loai.inventory.service.platform.PlatformOrgTimelineService;
import com.loai.inventory.service.platform.UserAdminService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.mockito.Mockito;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;

/**
 * {@code stories/platform_resend_verification.md} — rescue a stuck signup, and stop the console
 * from bricking the tenant it is trying to rescue.
 *
 * <p>A PENDING org means exactly one thing: its owner never clicked the link. {@code
 * AccountService.verifyEmail} stamps {@code email_verified_at} and then calls {@code
 * activateRegistrationPendingOrgs}, so verifying the email <em>is</em> what activates the tenant.
 * The registration path here is the real {@link AccountService} rather than hand-inserted rows,
 * because the state this slice acts on is defined by how it is actually created.
 *
 * <p>The link that gets redeemed is the one that was <b>actually emailed</b>: {@link Outbox}
 * records every outbound message and the tests pull the token out of the URL in the body. A test
 * that re-mints its own token would pass even if the endpoint mailed nothing at all, which is the
 * precise failure this slice exists to prevent.
 */
@Testcontainers
class PlatformResendVerificationIT {

  static {
    System.setProperty("net.bytebuddy.experimental", "true");
  }

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

  private static final String SECURITY_CONTEXT_ATTR = "securityContext";
  private static final Pattern TOKEN_IN_URL = Pattern.compile("token=([A-Za-z0-9_\\-]+)");

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ObjectMapper mapper;
  static AccountService accountService;
  static UserAdminService userAdminService;
  static PlatformOrgService orgService;
  static UserAdminHandler userHandler;
  static OrgAdminHandler orgHandler;
  static UserRepositoryImpl userRepo;
  static Outbox outbox;
  static UUID operatorId;

  /**
   * Records what was sent, and can be told to fail the next send. Both halves matter: the success
   * tests redeem the link out of the recorded body, and {@link
   * #sendFailure_is502_andIsNotReportedAsSent} needs a provider that actually refuses.
   */
  static final class Outbox implements EmailSender {
    final List<EmailMessage> sent = new ArrayList<>();
    boolean failNext = false;

    @Override
    public void send(EmailMessage message) {
      if (failNext) {
        throw new EmailException("simulated provider outage");
      }
      sent.add(message);
    }

    void reset() {
      sent.clear();
      failNext = false;
    }

    /** The raw credential token out of the most recent message's link. */
    String lastToken() {
      assertFalse(sent.isEmpty(), "no email was sent");
      Matcher m = TOKEN_IN_URL.matcher(sent.get(sent.size() - 1).html());
      assertTrue(m.find(), "no token in the emailed link");
      return m.group(1);
    }
  }

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
    mapper = ObjectMapperProvider.build();
    userRepo = new UserRepositoryImpl(dsl);
    outbox = new Outbox();

    OrgRepositoryFactoryImpl orgRepoFactory = new OrgRepositoryFactoryImpl();
    UserRepositoryFactoryImpl userRepoFactory = new UserRepositoryFactoryImpl();
    JedisPool jedisPool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
    CredentialTokenService credentialTokenService =
        new CredentialTokenService(
            dsl,
            new AppUserMagicTokenRepositoryFactoryImpl(),
            "http://localhost:8080",
            Duration.ofMinutes(120),
            Duration.ofDays(7),
            Duration.ofHours(48));
    OrgStatusService orgStatus = new OrgStatusService(jedisPool, dsl, orgRepoFactory);
    PlatformAuditService audit =
        new PlatformAuditService(dsl, new PlatformAuditRepositoryFactoryImpl());
    AuthMailer authMailer = new AuthMailer(outbox);

    AuthService authService =
        new AuthService(
            userRepo,
            new RefreshTokenStore(jedisPool),
            new JwtUtil(Base64.getEncoder().encodeToString(new byte[48]), 900_000L),
            new ImpersonationEventRepositoryImpl(dsl),
            300_000L);

    accountService =
        new AccountService(
            dsl,
            userRepoFactory,
            orgRepoFactory,
            credentialTokenService,
            authMailer,
            authService,
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            orgStatus,
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));

    userAdminService =
        new UserAdminService(
            dsl,
            userRepoFactory,
            orgRepoFactory,
            authService,
            audit,
            credentialTokenService,
            authMailer);

    orgService =
        new PlatformOrgService(
            dsl,
            orgRepoFactory,
            userRepoFactory,
            new OrgHealthRepositoryImpl(dsl),
            audit,
            orgStatus,
            credentialTokenService,
            authMailer,
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));

    userHandler = new UserAdminHandler(userAdminService, authService, audit, mapper);
    orgHandler =
        new OrgAdminHandler(
            orgService,
            new PlatformOrgTimelineService(
                dsl, new OrgTimelineRepositoryFactoryImpl(), orgRepoFactory),
            mapper);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) {
      dataSource.close();
    }
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE platform_audit, impersonation_event, app_user_magic_token, user_system_role,"
            + " user_org_role, app_user, org RESTART IDENTITY CASCADE");
    outbox.reset();
    // A real row: platform_audit.actor_id is an FK to app_user, so an operator invented out of
    // UUID.randomUUID() cannot write the audit trail every mutation here is required to leave.
    // Born verified, like every admin-plane account.
    AppUser operator =
        userRepo.insert(
            new AppUser(null, "operator@platform.test", "x", ActorType.USER, true, 0, null, null));
    dsl.update(APP_USER)
        .set(APP_USER.EMAIL_VERIFIED_AT, OffsetDateTime.now())
        .where(APP_USER.ID.eq(operator.getId()))
        .execute();
    operatorId = operator.getId();
  }

  // ─── The state the console could not see ──────────────────────────────────────────────────

  /**
   * The polarity argument, pinned. {@code email_verified} must be <b>present and false</b>, not
   * merely falsy: a client branching on the absence of {@code email_verified_at} would read "never
   * verified" as "nothing to report" — inverting this codebase's universal "missing key = no news"
   * for the one field whose absence is the incident.
   */
  @Test
  void emailVerifiedIsPresentAndFalse_onUnverifiedUser() {
    AppUser owner = accountService.register("stuck@shop.test", "correct-horse", "Stuck Shop");

    JsonNode listRow = userRowFor(read(userHandler, admin(), "GET", "", null, 200), owner.getId());
    assertTrue(listRow.has("email_verified"), "email_verified must always be on the wire");
    assertFalse(listRow.get("email_verified").asBoolean(), "unverified account must report false");
    assertFalse(
        listRow.has("email_verified_at"), "the timestamp is omitted until there actually is one");

    JsonNode detail = read(userHandler, admin(), "GET", "/" + owner.getId(), null, 200);
    assertTrue(detail.has("email_verified"));
    assertFalse(detail.get("email_verified").asBoolean());
    assertFalse(detail.has("email_verified_at"));

    // And the other half: once verified, both fields say so.
    accountService.verifyEmail(outbox.lastToken(), OffsetDateTime.now(), "junit", "1.2.3.4");
    JsonNode after = read(userHandler, admin(), "GET", "/" + owner.getId(), null, 200);
    assertTrue(after.get("email_verified").asBoolean());
    assertNotNull(after.get("email_verified_at"));
  }

  /** The org detail could name four health counts and no human. Now it names who to act on. */
  @Test
  void orgDetailNamesItsOwners() {
    AppUser owner = accountService.register("founder@shop.test", "correct-horse", "Founder Shop");
    UUID orgId = soleOrgId();

    JsonNode body = read(orgHandler, admin(), "GET", "/" + orgId, null, 200);
    assertEquals("pending", body.get("status").asText());
    JsonNode owners = body.get("owners");
    assertEquals(1, owners.size(), "the pending tenant's single OWNER");
    assertEquals(owner.getId().toString(), owners.get(0).get("id").asText());
    assertEquals("founder@shop.test", owners.get(0).get("email").asText());
    assertFalse(
        owners.get(0).get("email_verified").asBoolean(),
        "this false is the entire diagnosis the page exists to show");
  }

  // ─── The action ───────────────────────────────────────────────────────────────────────────

  @Test
  void resendOnUnverifiedUser_sendsAndReports200() {
    AppUser owner = accountService.register("stuck@shop.test", "correct-horse", "Stuck Shop");
    outbox.reset(); // drop the registration mail; we assert on the resend's own

    JsonNode body =
        read(userHandler, admin(), "POST", "/" + owner.getId() + "/resend-verification", "{}", 200);

    assertEquals(
        "stuck@shop.test", body.get("email").asText(), "the operator must see the address");
    assertNotNull(body.get("expires_at"));
    assertEquals(1, outbox.sent.size(), "exactly one mail left the building");
    assertEquals("stuck@shop.test", outbox.sent.get(0).to());
    assertEquals(
        1,
        liveTokenCount(owner.getId()),
        "a fresh EMAIL_VERIFY token exists (the prior one was superseded)");
  }

  /** Only the latest emailed link redeems — the existing guarantee, which must survive. */
  @Test
  void resendSupersedesPriorToken() {
    AppUser owner = accountService.register("stuck@shop.test", "correct-horse", "Stuck Shop");
    String original = outbox.lastToken();

    read(userHandler, admin(), "POST", "/" + owner.getId() + "/resend-verification", "{}", 200);
    String resent = outbox.lastToken();
    assertFalse(original.equals(resent), "the resend must mint a new token, not re-mail the old");

    assertThrows(
        RuntimeException.class,
        () -> accountService.verifyEmail(original, OffsetDateTime.now(), "junit", "1.2.3.4"),
        "the superseded link must no longer redeem");
    assertNotNull(accountService.verifyEmail(resent, OffsetDateTime.now(), "junit", "1.2.3.4"));
  }

  /**
   * The slice's end-to-end claim, and the only test that proves the rescue is a rescue: register
   * (org PENDING) → admin resend → redeem the <em>emailed</em> link → the tenant is ACTIVE.
   */
  @Test
  void rescuedSignupActuallyActivatesTheOrg() {
    AppUser owner = accountService.register("founder@shop.test", "correct-horse", "Founder Shop");
    UUID orgId = soleOrgId();
    assertEquals(OrgStatus.PENDING, statusOf(orgId), "a fresh self-serve signup is PENDING");

    read(userHandler, admin(), "POST", "/" + owner.getId() + "/resend-verification", "{}", 200);
    accountService.verifyEmail(outbox.lastToken(), OffsetDateTime.now(), "junit", "1.2.3.4");

    assertEquals(OrgStatus.ACTIVE, statusOf(orgId), "the rescued signup's tenant must go live");
  }

  // ─── The refusals ─────────────────────────────────────────────────────────────────────────

  @Test
  void alreadyVerified_is409() {
    AppUser owner = accountService.register("done@shop.test", "correct-horse", "Done Shop");
    accountService.verifyEmail(outbox.lastToken(), OffsetDateTime.now(), "junit", "1.2.3.4");

    read(userHandler, admin(), "POST", "/" + owner.getId() + "/resend-verification", "{}", 409);
  }

  @Test
  void disabledUser_is409() {
    AppUser owner = accountService.register("off@shop.test", "correct-horse", "Off Shop");
    dsl.update(APP_USER).set(APP_USER.ACTIVE, false).where(APP_USER.ID.eq(owner.getId())).execute();
    outbox.reset(); // drop the registration mail; the assertion below is about the refusal only

    read(userHandler, admin(), "POST", "/" + owner.getId() + "/resend-verification", "{}", 409);
    assertTrue(
        outbox.sent.isEmpty(), "a disabled account must not be handed a link that logs it in");
  }

  @Test
  void unknownUser_is404() {
    read(userHandler, admin(), "POST", "/" + UUID.randomUUID() + "/resend-verification", "{}", 404);
  }

  /** An audit column you can point anywhere is worse than a null one. */
  @Test
  void orgIdNotOwnedByUser_is400() {
    AppUser owner = accountService.register("founder@shop.test", "correct-horse", "Founder Shop");
    accountService.register("other@shop.test", "correct-horse", "Other Shop");
    UUID foreignOrg = orgIdByName("Other Shop");

    read(
        userHandler,
        admin(),
        "POST",
        "/" + owner.getId() + "/resend-verification",
        "{\"org_id\":\"" + foreignOrg + "\"}",
        400);
  }

  /**
   * A delivery failure is a 502 that names it — never a 200 with a hopeful message. This is the
   * epic's honesty rule at its smallest scale: the whole value of the button is that its
   * confirmation is true.
   */
  @Test
  void sendFailure_is502_andIsNotReportedAsSent() {
    AppUser owner = accountService.register("stuck@shop.test", "correct-horse", "Stuck Shop");
    outbox.reset();
    outbox.failNext = true;

    JsonNode body =
        read(userHandler, admin(), "POST", "/" + owner.getId() + "/resend-verification", "{}", 502);

    assertFalse(body.has("email"), "a failed send must not answer in the success shape");
    assertTrue(outbox.sent.isEmpty(), "nothing was actually delivered");
    // The ledger still records the attempt, because the mint really did supersede any prior link.
    JsonNode entry = soleAuditDetail("EMAIL_VERIFY_RESEND");
    assertFalse(
        entry.get("delivered").asBoolean(),
        "the audit row must not imply a delivery that never happened");
  }

  /**
   * {@code org_id} present is what puts the rescue on that tenant's slice-4 timeline — the
   * per-tenant question V76's column exists to answer: who got this tenant unstuck, and when.
   */
  @Test
  void orgIdPresent_landsOnThatTenantsTimeline() {
    AppUser owner = accountService.register("founder@shop.test", "correct-horse", "Founder Shop");
    UUID orgId = soleOrgId();

    read(
        userHandler,
        admin(),
        "POST",
        "/" + owner.getId() + "/resend-verification",
        "{\"org_id\":\"" + orgId + "\"}",
        200);

    JsonNode timeline = read(orgHandler, admin(), "GET", "/" + orgId + "/timeline", null, 200);
    assertTrue(
        hasAction(timeline, "EMAIL_VERIFY_RESEND"),
        "the rescue must appear on the tenant it rescued");
  }

  /**
   * The null half, and the reason the parameter is validated rather than trusted: a resend with no
   * tenant named must not surface under some org the user happens to belong to. A resend targets a
   * person; a person is not a tenant event.
   */
  @Test
  void orgIdAbsent_isNotOnAnyTimeline() {
    AppUser owner = accountService.register("founder@shop.test", "correct-horse", "Founder Shop");
    UUID orgId = soleOrgId();

    read(userHandler, admin(), "POST", "/" + owner.getId() + "/resend-verification", "{}", 200);

    JsonNode timeline = read(orgHandler, admin(), "GET", "/" + orgId + "/timeline", null, 200);
    assertFalse(
        hasAction(timeline, "EMAIL_VERIFY_RESEND"),
        "an untargeted resend must not be attributed to a tenant");
  }

  // ─── Tiers ────────────────────────────────────────────────────────────────────────────────

  @Test
  void support_is403() {
    AppUser owner = accountService.register("stuck@shop.test", "correct-horse", "Stuck Shop");
    outbox.reset(); // drop the registration mail so the assertion below is about SUPPORT only
    read(
        userHandler,
        platform(SystemRole.SUPPORT),
        "POST",
        "/" + owner.getId() + "/resend-verification",
        "{}",
        403);
    assertTrue(outbox.sent.isEmpty(), "a read tier cannot send mail");
  }

  @Test
  void orgOwner_is403() {
    AppUser owner = accountService.register("stuck@shop.test", "correct-horse", "Stuck Shop");
    UUID orgId = soleOrgId();
    read(
        userHandler,
        orgOwnerOf(orgId),
        "POST",
        "/" + owner.getId() + "/resend-verification",
        "{}",
        403);
  }

  @Test
  void anonymous_is401() {
    AppUser owner = accountService.register("stuck@shop.test", "correct-horse", "Stuck Shop");
    read(userHandler, null, "POST", "/" + owner.getId() + "/resend-verification", "{}", 401);
  }

  // ─── The trap ─────────────────────────────────────────────────────────────────────────────

  @Test
  void grantIntoPendingOrg_is409() {
    accountService.register("founder@shop.test", "correct-horse", "Founder Shop");
    UUID orgId = soleOrgId();
    AppUser colleague = accountService.register("colleague@shop.test", "correct-horse", null);

    read(
        userHandler,
        admin(),
        "POST",
        "/" + colleague.getId() + "/org-roles",
        "{\"org_id\":\"" + orgId + "\",\"role\":\"VIEWER\"}",
        409);
  }

  /**
   * The regression proof, and the reason the guard above is worth having. With the guard bypassed
   * at the repository level — which is exactly what the API did before this slice — the owner's
   * verification click stops activating the tenant, permanently.
   *
   * <p>The second member must be a <b>different user</b>: the activation predicate's {@code NOT
   * EXISTS} is {@code user_id <> ownerId}, so a second role granted to the owner themselves does
   * not brick anything. A version of this test that granted to the owner would pass while
   * activation still worked and would prove nothing.
   */
  @Test
  void grantIntoPendingOrg_wouldHaveBrickedActivation() {
    AppUser owner = accountService.register("founder@shop.test", "correct-horse", "Founder Shop");
    UUID orgId = soleOrgId();
    AppUser colleague = accountService.register("colleague@shop.test", "correct-horse", null);

    // Bypass the 409 the API now returns, reproducing the pre-slice behaviour.
    userRepo.insertOrgRole(colleague.getId(), orgId, OrgRole.VIEWER);

    accountService.verifyEmail(
        tokenFor("founder@shop.test"), OffsetDateTime.now(), "junit", "1.2.3.4");

    assertNotNull(
        dsl.select(APP_USER.EMAIL_VERIFIED_AT)
            .from(APP_USER)
            .where(APP_USER.ID.eq(owner.getId()))
            .fetchOne(APP_USER.EMAIL_VERIFIED_AT),
        "the owner really did verify");
    assertEquals(
        OrgStatus.PENDING,
        statusOf(orgId),
        "a second member silently breaks activation — this is what the 409 prevents");
  }

  /** A suspended tenant is a different thing: staffing it ahead of reactivation is legitimate. */
  @Test
  void grantIntoSuspendedOrg_isAllowed() {
    accountService.register("founder@shop.test", "correct-horse", "Founder Shop");
    UUID orgId = soleOrgId();
    dsl.update(ORG)
        .set(ORG.SUSPENDED_AT, OffsetDateTime.now())
        .set(ORG.SUSPENDED_REASON, "spam")
        .where(ORG.ID.eq(orgId))
        .execute();
    AppUser colleague = accountService.register("colleague@shop.test", "correct-horse", null);

    read(
        userHandler,
        admin(),
        "POST",
        "/" + colleague.getId() + "/org-roles",
        "{\"org_id\":\"" + orgId + "\",\"role\":\"VIEWER\"}",
        204);
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────────────────────

  private OrgStatus statusOf(UUID orgId) {
    var r = dsl.select(ORG.ACTIVE, ORG.SUSPENDED_AT).from(ORG).where(ORG.ID.eq(orgId)).fetchOne();
    assertNotNull(r, "org " + orgId + " not found");
    return OrgStatus.of(r.value1(), r.value2());
  }

  private UUID soleOrgId() {
    List<UUID> ids = dsl.select(ORG.ID).from(ORG).fetch(ORG.ID);
    assertEquals(1, ids.size(), "expected exactly one org in the fixture");
    return ids.get(0);
  }

  /** By name, not slug — the slug is derived and uniquified, so it is not the test's business. */
  private UUID orgIdByName(String name) {
    UUID id = dsl.select(ORG.ID).from(ORG).where(ORG.NAME.eq(name)).fetchOne(ORG.ID);
    assertNotNull(id, "no org named " + name);
    return id;
  }

  private long liveTokenCount(UUID userId) {
    return dsl.fetchCount(
        DSL.table("app_user_magic_token"),
        DSL.condition("user_id = ?", userId)
            .and(DSL.condition("purpose = ?", AppUserTokenPurpose.EMAIL_VERIFY.name()))
            .and(DSL.condition("consumed_at is null"))
            .and(DSL.condition("expires_at > now()")));
  }

  /** Mint a fresh click for an address — the raw token can't be read back out of its hash. */
  private String tokenFor(String email) {
    UUID id =
        dsl.select(APP_USER.ID)
            .from(APP_USER)
            .where(APP_USER.EMAIL.eq(email))
            .fetchOne(APP_USER.ID);
    assertNotNull(id, "no user " + email);
    return new CredentialTokenService(
            dsl,
            new AppUserMagicTokenRepositoryFactoryImpl(),
            "http://localhost:8080",
            Duration.ofMinutes(120),
            Duration.ofDays(7),
            Duration.ofHours(48))
        .mintAutonomous(id, AppUserTokenPurpose.EMAIL_VERIFY, OffsetDateTime.now());
  }

  private JsonNode soleAuditDetail(String action) {
    String detail =
        dsl.fetchOne(
                "select detail::text from platform_audit where action = ? order by created_at desc"
                    + " limit 1",
                action)
            .get(0, String.class);
    assertNotNull(detail, "no audit row for " + action);
    try {
      return mapper.readTree(detail);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean hasAction(JsonNode timeline, String action) {
    for (JsonNode entry : timeline.get("data")) {
      if (action.equals(entry.path("action").asText())) {
        return true;
      }
    }
    return false;
  }

  /** By id, not position — the fixture's own operator account is also in this list. */
  private JsonNode userRowFor(JsonNode page, UUID userId) {
    for (JsonNode row : page.get("data")) {
      if (userId.toString().equals(row.path("id").asText())) {
        return row;
      }
    }
    throw new AssertionError("user " + userId + " not in the list page");
  }

  private JsonNode read(
      Object handler,
      SecurityContext ctx,
      String method,
      String remaining,
      String body,
      int expectedStatus) {
    try {
      Resp resp = new Resp();
      HttpServletRequest req = reqWith(ctx, body);
      if (handler instanceof UserAdminHandler h) {
        h.handle(method, req, resp.mock, remaining);
      } else {
        ((OrgAdminHandler) handler).handle(method, req, resp.mock, remaining);
      }
      JsonNode parsed = resp.body.size() == 0 ? mapper.createObjectNode() : bodyOf(resp);
      assertEquals(expectedStatus, resp.status, () -> "body was " + parsed);
      return parsed;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private JsonNode bodyOf(Resp resp) {
    try {
      return mapper.readTree(resp.body.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private SecurityContext admin() {
    return platform(SystemRole.ADMIN);
  }

  private SecurityContext platform(SystemRole role) {
    return new SecurityContext(operatorId, ActorType.USER, Set.of(role), Map.of(), Set.of(), 0);
  }

  private SecurityContext orgOwnerOf(UUID orgId) {
    return new SecurityContext(
        UUID.randomUUID(),
        ActorType.USER,
        Set.of(),
        Map.of(orgId, Set.of(OrgRole.OWNER)),
        Set.of(),
        0);
  }

  private HttpServletRequest reqWith(SecurityContext ctx, String body) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    when(req.getContentLength()).thenReturn(bytes.length);
    try {
      when(req.getInputStream()).thenReturn(servletStream(bytes));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return req;
  }

  private static ServletInputStream servletStream(byte[] bytes) {
    ByteArrayInputStream delegate = new ByteArrayInputStream(bytes);
    return new ServletInputStream() {
      @Override
      public int read() {
        return delegate.read();
      }

      @Override
      public boolean isFinished() {
        return delegate.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {}
    };
  }

  private static final class Resp {
    final HttpServletResponse mock;
    final ByteArrayOutputStream body = new ByteArrayOutputStream();
    int status = 200;

    Resp() throws IOException {
      mock = Mockito.mock(HttpServletResponse.class);
      Mockito.doAnswer(
              inv -> {
                status = inv.getArgument(0);
                return null;
              })
          .when(mock)
          .setStatus(Mockito.anyInt());
      when(mock.getOutputStream())
          .thenReturn(
              new ServletOutputStream() {
                @Override
                public void write(int b) {
                  body.write(b);
                }

                @Override
                public boolean isReady() {
                  return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {}
              });
    }
  }
}
