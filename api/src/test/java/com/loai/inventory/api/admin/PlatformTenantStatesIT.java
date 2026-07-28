package com.loai.inventory.api.admin;

import static com.loai.inventory.repository.generated.Tables.ORG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loai.inventory.api.config.ObjectMapperProvider;
import com.loai.inventory.api.servlet.handler.OrgAdminHandler;
import com.loai.inventory.api.servlet.handler.OverviewAdminHandler;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.repository.AppUserMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.ImpersonationEventRepositoryImpl;
import com.loai.inventory.repository.OrgHealthRepositoryImpl;
import com.loai.inventory.repository.OrgMilestoneRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformAuditRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformStatsRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.service.auth.AccountService;
import com.loai.inventory.service.auth.AuthMailer;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.CredentialTokenService;
import com.loai.inventory.service.auth.RefreshTokenStore;
import com.loai.inventory.service.platform.OrgMilestoneService;
import com.loai.inventory.service.platform.OrgStatusService;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.PlatformOrgService;
import com.loai.inventory.service.platform.PlatformOverviewService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * {@code stories/platform_tenant_states.md} — a tenant pending verification is not a suspended
 * tenant.
 *
 * <p>{@code active = false} used to be two states under one label: an org a platform ADMIN
 * suspended, and an org born inactive at self-serve registration whose owner never clicked the
 * verification link. The console reported both as "suspended", so a person who had not yet opened
 * an email tripped an operational alarm — and on a platform with open registration that tile trends
 * toward mostly-abandoned signups.
 *
 * <p>Both surfaces are driven here (the overview tiles and the org list's {@code ?status=} filter)
 * because the whole design is that they consume one definition of the rule and therefore cannot
 * disagree. The registration path is the real {@link AccountService}, not a hand-inserted row: the
 * bug was observed on a live backend, so the fixture reproduces how the state is actually created.
 */
@Testcontainers
class PlatformTenantStatesIT {

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

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static ObjectMapper mapper;
  static PlatformOrgService orgService;
  static AccountService accountService;
  static OrgAdminHandler orgHandler;
  static OverviewAdminHandler overviewHandler;

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

    OrgRepositoryFactoryImpl orgRepoFactory = new OrgRepositoryFactoryImpl();
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

    orgService =
        new PlatformOrgService(
            dsl,
            orgRepoFactory,
            new UserRepositoryFactoryImpl(),
            new OrgHealthRepositoryImpl(dsl),
            new PlatformAuditService(dsl, new PlatformAuditRepositoryFactoryImpl()),
            orgStatus,
            credentialTokenService,
            new AuthMailer(msg -> {}),
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));

    AuthService authService =
        new AuthService(
            new UserRepositoryImpl(dsl),
            new RefreshTokenStore(jedisPool),
            new JwtUtil(Base64.getEncoder().encodeToString(new byte[48]), 900_000L),
            new ImpersonationEventRepositoryImpl(dsl),
            300_000L);
    accountService =
        new AccountService(
            dsl,
            new UserRepositoryFactoryImpl(),
            orgRepoFactory,
            credentialTokenService,
            new AuthMailer(msg -> {}),
            authService,
            com.loai.inventory.api.support.TestWiring.permissiveEmailGate(),
            orgStatus,
            new OrgMilestoneService(new OrgMilestoneRepositoryFactoryImpl()));

    orgHandler =
        new OrgAdminHandler(
            orgService,
            new com.loai.inventory.service.platform.PlatformOrgTimelineService(
                dsl,
                new com.loai.inventory.repository.OrgTimelineRepositoryFactoryImpl(),
                orgRepoFactory),
            mapper);
    overviewHandler =
        new OverviewAdminHandler(
            new PlatformOverviewService(
                dsl,
                new PlatformStatsRepositoryFactoryImpl(),
                false,
                List.of(),
                null,
                Instant.now()),
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
        "TRUNCATE platform_audit, app_user_magic_token, user_system_role, user_org_role,"
            + " app_user, org RESTART IDENTITY CASCADE");
  }

  // The partition

  /**
   * The whole design in one assertion: the three statuses partition the table. Every org is in
   * exactly one, the tiles sum to the total, and no org answers to two filters.
   */
  @Test
  void everyOrgIsInExactlyOneStatus() {
    createOrg("live-1", true);
    createOrg("live-2", true);
    UUID pending = createOrg("unverified", false);
    UUID banned = createOrg("abusive", true);
    orgService.suspend(admin(), env(), banned, "abuse");

    JsonNode tenants = overview().path("tenants");
    assertEquals(4, tenants.path("total").asLong());
    assertEquals(
        tenants.path("total").asLong(),
        tenants.path("active").asLong()
            + tenants.path("suspended").asLong()
            + tenants.path("pending").asLong());

    Set<String> active = idsOf(list("active"));
    Set<String> pendingIds = idsOf(list("pending"));
    Set<String> suspendedIds = idsOf(list("suspended"));

    assertEquals(Set.of(pending.toString()), pendingIds);
    assertEquals(Set.of(banned.toString()), suspendedIds);
    assertEquals(2, active.size());
    // Disjoint: nothing appears under two filters.
    assertTrue(java.util.Collections.disjoint(active, pendingIds));
    assertTrue(java.util.Collections.disjoint(active, suspendedIds));
    assertTrue(java.util.Collections.disjoint(pendingIds, suspendedIds));
    // Exhaustive: the union is every org the unfiltered list returns.
    Set<String> all = idsOf(list(null));
    assertEquals(4, all.size());
    Set<String> union = new java.util.HashSet<>(active);
    union.addAll(pendingIds);
    union.addAll(suspendedIds);
    assertEquals(all, union);
  }

  // The bug

  /**
   * The observation this slice exists for: register an org through the real self-serve path and it
   * reads {@code pending}, never {@code suspended} — on the tile <em>and</em> on the list it drills
   * into. Before the fix this rendered {@code active: 0, suspended: 1}.
   */
  @Test
  void aPendingOrgIsNotCountedAsSuspended() {
    accountService.register("founder@shop.test", "correct-horse", "Founder Shop");

    JsonNode tenants = overview().path("tenants");
    assertEquals(1, tenants.path("total").asLong());
    assertEquals(1, tenants.path("pending").asLong());
    assertEquals(0, tenants.path("suspended").asLong());
    assertEquals(0, tenants.path("active").asLong());

    JsonNode pending = list("pending");
    assertEquals(1, pending.path("total").asLong());
    assertEquals("Founder Shop", pending.path("data").get(0).path("name").asText());
    assertEquals("pending", pending.path("data").get(0).path("status").asText());
    // …and the alarm state is empty, which is the point.
    assertEquals(0, list("suspended").path("total").asLong());
  }

  /**
   * The list row names the state and no longer carries the boolean a client could re-derive from.
   */
  @Test
  void theListRowNamesTheStateAndDropsTheBoolean() {
    UUID suspended = createOrg("banned", true);
    orgService.suspend(admin(), env(), suspended, "chargebacks");
    createOrg("pending-org", false);
    createOrg("live", true);

    for (JsonNode row : list(null).path("data")) {
      assertFalse(row.has("active"), "the boolean is gone, not kept as a compatibility field");
      assertTrue(
          Set.of("active", "pending", "suspended").contains(row.path("status").asText()),
          "unexpected status: " + row.path("status").asText());
    }
  }

  // The write-only gap, closed

  /**
   * {@code POST .../suspend {reason}} has stamped the note since the lifecycle slice shipped and
   * nothing ever read it back. The detail page can now say why and since when.
   */
  @Test
  void suspendedOrgReadsBackItsReasonAndTimestamp() {
    UUID orgId = createOrg("acme", true);
    orgService.suspend(admin(), env(), orgId, "payment fraud investigation");

    JsonNode detail = detail(orgId);

    assertEquals("suspended", detail.path("status").asText());
    assertEquals("payment fraud investigation", detail.path("suspended_reason").asText());
    assertTrue(detail.hasNonNull("suspended_at"));
    assertNotNull(OffsetDateTime.parse(detail.path("suspended_at").asText()));
  }

  /** A pending org carries neither field — Jackson omits nulls, so the keys are simply absent. */
  @Test
  void aPendingOrgCarriesNoSuspensionStory() {
    UUID orgId = createOrg("unverified", false);

    JsonNode detail = detail(orgId);

    assertEquals("pending", detail.path("status").asText());
    assertFalse(detail.has("suspended_at"));
    assertFalse(detail.has("suspended_reason"));
  }

  @Test
  void reactivateClearsTheSuspensionAndReturnsToActive() {
    UUID orgId = createOrg("acme", true);
    orgService.suspend(admin(), env(), orgId, "mistake");
    assertEquals("suspended", detail(orgId).path("status").asText());

    orgService.reactivate(admin(), env(), orgId);

    JsonNode detail = detail(orgId);
    assertEquals("active", detail.path("status").asText());
    assertFalse(detail.has("suspended_at"));
    assertFalse(detail.has("suspended_reason"));
    assertEquals(1, overview().path("tenants").path("active").asLong());
    assertEquals(0, overview().path("tenants").path("pending").asLong());
  }

  // The edge that makes the partition real

  /**
   * Suspending a tenant that never verified is meaningful (an abusive signup), and it wins
   * permanently: {@code setSuspension} stamps {@code suspended_at}, and {@code
   * activateRegistrationPendingOrgs} requires that stamp to be NULL — so the owner later clicking
   * their verification link correctly does <em>not</em> resurrect the org. An admin suspension
   * outranks a verification click.
   */
  @Test
  void suspendingAPendingOrgReadsAsSuspended_andLaterVerificationDoesNotReactivateIt() {
    AppUser owner = accountService.register("abuser@shop.test", "correct-horse", "Abusive Shop");
    UUID orgId = soleOrgOf(owner.getId());
    assertEquals("pending", detail(orgId).path("status").asText());

    orgService.suspend(admin(), env(), orgId, "abusive signup");
    assertEquals("suspended", detail(orgId).path("status").asText());

    // The owner now clicks the link they were emailed at registration.
    accountService.verifyEmail(
        verifyTokenFor(owner.getId()), OffsetDateTime.now(), "junit", "127.0.0.1");

    assertEquals("suspended", detail(orgId).path("status").asText());
    JsonNode tenants = overview().path("tenants");
    assertEquals(1, tenants.path("suspended").asLong());
    assertEquals(0, tenants.path("active").asLong());
    assertEquals(0, tenants.path("pending").asLong());
  }

  /** The ordinary path: verification activates a pending org, and it moves tile to tile. */
  @Test
  void verifyingActivatesAPendingOrg() {
    AppUser owner = accountService.register("founder@shop.test", "correct-horse", "Founder Shop");
    assertEquals(1, overview().path("tenants").path("pending").asLong());

    accountService.verifyEmail(
        verifyTokenFor(owner.getId()), OffsetDateTime.now(), "junit", "127.0.0.1");

    JsonNode tenants = overview().path("tenants");
    assertEquals(1, tenants.path("active").asLong());
    assertEquals(0, tenants.path("pending").asLong());
    assertEquals(0, tenants.path("suspended").asLong());
  }

  // Parity + the filter contract

  /**
   * The pin: for every status, the list's {@code total} equals the overview tile. Both sides
   * consume {@code OrgStatus}, so this holds by construction — which is exactly what the previous
   * slice could not say about a state that did not exist.
   */
  @Test
  void listTotalsEqualTheOverviewTiles() {
    createOrg("live-1", true);
    createOrg("live-2", true);
    createOrg("live-3", true);
    createOrg("unverified-1", false);
    createOrg("unverified-2", false);
    orgService.suspend(admin(), env(), createOrg("banned", true), "abuse");

    JsonNode tenants = overview().path("tenants");
    for (String status : List.of("active", "pending", "suspended")) {
      assertEquals(
          tenants.path(status).asLong(),
          list(status).path("total").asLong(),
          "tile and list disagree for status=" + status);
    }
    // Seeded values, so the parity is not all-zero agreement.
    assertEquals(3, tenants.path("active").asLong());
    assertEquals(2, tenants.path("pending").asLong());
    assertEquals(1, tenants.path("suspended").asLong());
  }

  @Test
  void unknownStatusIs400NamingAllThree() {
    Resp resp = invoke(orgHandler, "GET", platform(SystemRole.ADMIN), "", "wobbly");

    assertEquals(400, resp.status);
    String message = json(resp).path("message").asText();
    assertTrue(message.contains("active"), message);
    assertTrue(message.contains("pending"), message);
    assertTrue(message.contains("suspended"), message);
  }

  // plumbing

  private JsonNode overview() {
    return json(invoke(overviewHandler, "GET", platform(SystemRole.ADMIN), "", null));
  }

  private JsonNode list(String status) {
    Resp resp = invoke(orgHandler, "GET", platform(SystemRole.ADMIN), "", status);
    assertEquals(200, resp.status, () -> "body was " + resp.body);
    return json(resp);
  }

  private JsonNode detail(UUID orgId) {
    Resp resp = invoke(orgHandler, "GET", platform(SystemRole.ADMIN), "/" + orgId, null);
    assertEquals(200, resp.status, () -> "body was " + resp.body);
    return json(resp);
  }

  private Set<String> idsOf(JsonNode page) {
    Set<String> ids = new java.util.HashSet<>();
    page.path("data").forEach(row -> ids.add(row.path("id").asText()));
    return ids;
  }

  private UUID createOrg(String slug, boolean active) {
    UUID id = UUID.randomUUID();
    dsl.insertInto(ORG)
        .set(ORG.ID, id)
        .set(ORG.NAME, slug)
        .set(ORG.SLUG, slug)
        .set(ORG.ACTIVE, active)
        .execute();
    return id;
  }

  private UUID soleOrgOf(UUID ownerId) {
    return dsl.select(DSL.field("org_id", UUID.class))
        .from("user_org_role")
        .where("user_id = ?", ownerId)
        .fetchOne(0, UUID.class);
  }

  /** The raw EMAIL_VERIFY token can't be read back from the hash, so mint the click a fresh one. */
  private String verifyTokenFor(UUID userId) {
    return new CredentialTokenService(
            dsl,
            new AppUserMagicTokenRepositoryFactoryImpl(),
            "http://localhost:8080",
            Duration.ofMinutes(120),
            Duration.ofDays(7),
            Duration.ofHours(48))
        .mint(
            dsl,
            userId,
            com.loai.inventory.domain.model.AppUserTokenPurpose.EMAIL_VERIFY,
            OffsetDateTime.now());
  }

  private SecurityContext admin() {
    UUID id = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO app_user(id,email,password_hash,actor_type,active,token_version)"
            + " VALUES (?,?,?,?::actor_type,?,?)",
        id,
        "admin-" + id + "@x.io",
        "x",
        "USER",
        true,
        0);
    return new SecurityContext(id, ActorType.USER, Set.of(SystemRole.ADMIN), Map.of(), Set.of(), 0);
  }

  private SecurityContext platform(SystemRole role) {
    return new SecurityContext(
        UUID.randomUUID(), ActorType.USER, Set.of(role), Map.of(), Set.of(), 0);
  }

  private JsonNode json(Resp resp) {
    try {
      return mapper.readTree(resp.body.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private Resp invoke(
      Object handler, String method, SecurityContext ctx, String remaining, String status) {
    try {
      Resp resp = new Resp();
      HttpServletRequest req = reqWith(ctx, status);
      if (handler instanceof OrgAdminHandler h) {
        h.handle(method, req, resp.mock, remaining);
      } else {
        ((OverviewAdminHandler) handler).handle(method, req, resp.mock, remaining);
      }
      return resp;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private HttpServletRequest reqWith(SecurityContext ctx, String status) {
    HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
    when(req.getAttribute(SECURITY_CONTEXT_ATTR)).thenReturn(ctx);
    when(req.getParameter("status")).thenReturn(status);
    return req;
  }

  private static com.loai.inventory.domain.model.Environment env() {
    return new com.loai.inventory.domain.model.Environment(Instant.now(), "1.2.3.4", "junit");
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
