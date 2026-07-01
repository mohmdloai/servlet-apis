package com.loai.inventory.api.impersonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.AuthorizationException;
import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.ImpersonationTier;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.repository.ImpersonationEventRepositoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.RefreshTokenStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.jsonwebtoken.Claims;
import java.time.Instant;
import java.util.Base64;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;

/**
 * End-to-end coverage of {@link AuthService#impersonate}/{@link AuthService#stopImpersonating}
 * against a real database — the guard rails, the scoped-overlay token contents, and the Layer-1
 * audit ledger. Verifies the acceptance criteria in {@code stories/impersonate_user.md} that live
 * at the service layer (the read-only write gate and inventory_log stamping are covered in unit
 * tests).
 */
@Testcontainers
class ImpersonationServiceIT {

  @Container
  static final PostgreSQLContainer<?> PG =
      new PostgreSQLContainer<>("postgres:17")
          .withDatabaseName("inventorydb")
          .withUsername("postgres")
          .withPassword("postgres");

  @Container
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

  static HikariDataSource dataSource;
  static DSLContext dsl;
  static JwtUtil jwtUtil;
  static AuthService authService;
  static RefreshTokenStore refreshTokenStore;

  static final long TTL_MILLIS = 300_000L;

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

    jwtUtil = new JwtUtil(Base64.getEncoder().encodeToString(new byte[48]), 900_000L);
    refreshTokenStore =
        new RefreshTokenStore(new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379)));
    authService =
        new AuthService(
            new UserRepositoryImpl(dsl),
            refreshTokenStore,
            jwtUtil,
            new ImpersonationEventRepositoryImpl(dsl),
            TTL_MILLIS);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE impersonation_event, user_system_role, user_org_role, app_user, org"
            + " RESTART IDENTITY CASCADE");
  }

  // ───────────────────────── seeding helpers ─────────────────────────

  private UUID user(String email, boolean active) {
    UUID id = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO app_user(id,email,password_hash,actor_type,active,token_version)"
            + " VALUES (?,?,?,?::actor_type,?,?)",
        id,
        email,
        "x",
        "USER",
        active,
        0);
    return id;
  }

  private UUID org(String slug) {
    UUID id = UUID.randomUUID();
    dsl.execute("INSERT INTO org(id,name,slug) VALUES (?,?,?)", id, slug, slug);
    return id;
  }

  private void orgRole(UUID userId, UUID orgId, OrgRole role) {
    dsl.execute(
        "INSERT INTO user_org_role(user_id,org_id,role) VALUES (?,?,?::org_role)",
        userId,
        orgId,
        role.name());
  }

  private void systemRole(UUID userId, SystemRole role) {
    dsl.execute(
        "INSERT INTO user_system_role(user_id,role) VALUES (?,?::system_role)",
        userId,
        role.name());
  }

  private SecurityContext platformCaller(UUID id, SystemRole role) {
    return new SecurityContext(id, ActorType.USER, Set.of(role), Map.of(), Set.of(), 0);
  }

  private SecurityContext ownerCaller(UUID id, UUID orgId) {
    return new SecurityContext(
        id, ActorType.USER, Set.of(), Map.of(orgId, Set.of(OrgRole.OWNER)), Set.of(), 0);
  }

  private Environment env() {
    return new Environment(Instant.now(), "1.2.3.4", "junit");
  }

  private Claims decode(String token) {
    return jwtUtil.parseAndVerify(token);
  }

  private long eventCount(String event) {
    return dsl.fetchCount(DSL.table("impersonation_event"), DSL.field("event").eq(event));
  }

  // ───────────────────────── platform tier ─────────────────────────

  @Test
  void platformAdmin_mintsOverlay_actClaim_noSystemRoles_fullOrgRoles() {
    UUID admin = user("admin@x.io", true);
    systemRole(admin, SystemRole.ADMIN);
    UUID orgA = org("acme");
    UUID orgB = org("globex");
    UUID target = user("t@x.io", true);
    orgRole(target, orgA, OrgRole.VIEWER);
    orgRole(target, orgB, OrgRole.STAFF);

    AuthService.ImpersonationResult r =
        authService.impersonate(
            platformCaller(admin, SystemRole.ADMIN),
            target,
            ImpersonationTier.PLATFORM,
            null,
            "debugging",
            env());

    assertEquals(admin, r.impersonatorId());
    assertEquals(ImpersonationTier.PLATFORM, r.tier());
    assertFalse(r.readOnly());
    assertEquals(TTL_MILLIS / 1000, r.expiresIn());

    Claims c = decode(r.accessToken());
    assertEquals(target.toString(), c.getSubject());
    assertEquals(admin.toString(), c.get("act", String.class));
    assertEquals("PLATFORM", c.get("act_tier", String.class));
    assertNull(c.get("system_roles"), "overlay must never carry system_roles");
    assertNull(c.get("act_mode"), "ADMIN overlay is full-write");
    @SuppressWarnings("unchecked")
    Map<String, ?> orgRoles = c.get("org_roles", Map.class);
    assertEquals(Set.of(orgA.toString(), orgB.toString()), orgRoles.keySet());

    assertEquals(1, eventCount("START"));
  }

  @Test
  void impersonate_primesTargetTokenVersionCache() {
    // Regression: the overlay's sub is the target, and JwtAuthFilter validates against the target's
    // CACHED token_version, treating a cache miss as revoked. A target who never logged in has no
    // cached version — so without priming here, every overlay request 401s. (Found by end-to-end
    // adversarial testing; the service layer alone could not catch it.)
    UUID admin = user("admin@x.io", true);
    systemRole(admin, SystemRole.ADMIN);
    UUID target = user("t@x.io", true); // never logs in → no cached version beforehand
    assertTrue(refreshTokenStore.getCachedTokenVersion(target).isEmpty());

    authService.impersonate(
        platformCaller(admin, SystemRole.ADMIN),
        target,
        ImpersonationTier.PLATFORM,
        null,
        null,
        env());

    assertEquals(0, refreshTokenStore.getCachedTokenVersion(target).orElseThrow());
  }

  @Test
  void platformSupport_isReadOnly() {
    UUID support = user("s@x.io", true);
    systemRole(support, SystemRole.SUPPORT);
    UUID target = user("t@x.io", true);

    AuthService.ImpersonationResult r =
        authService.impersonate(
            platformCaller(support, SystemRole.SUPPORT),
            target,
            ImpersonationTier.PLATFORM,
            null,
            null,
            env());

    assertTrue(r.readOnly());
    assertEquals("READONLY", decode(r.accessToken()).get("act_mode", String.class));
  }

  @Test
  void platform_cannotImpersonateSystemAdmin() {
    UUID admin = user("admin@x.io", true);
    UUID target = user("t@x.io", true);
    systemRole(target, SystemRole.ADMIN);

    assertThrows(
        AuthorizationException.class,
        () ->
            authService.impersonate(
                platformCaller(admin, SystemRole.ADMIN),
                target,
                ImpersonationTier.PLATFORM,
                null,
                null,
                env()));
    assertEquals(0, eventCount("START"));
  }

  // ───────────────────────── org tier ─────────────────────────

  @Test
  void orgOwner_overlayScopedToOneOrg_noCrossTenantLeak() {
    UUID orgA = org("acme");
    UUID orgB = org("globex");
    UUID owner = user("owner@x.io", true);
    orgRole(owner, orgA, OrgRole.OWNER);
    UUID target = user("t@x.io", true);
    orgRole(target, orgA, OrgRole.STAFF);
    orgRole(target, orgB, OrgRole.STAFF); // target ALSO works at globex

    AuthService.ImpersonationResult r =
        authService.impersonate(
            ownerCaller(owner, orgA), target, ImpersonationTier.ORG, orgA, null, env());

    Claims c = decode(r.accessToken());
    assertEquals("ORG", c.get("act_tier", String.class));
    assertEquals(orgA.toString(), c.get("act_scope_org", String.class));
    @SuppressWarnings("unchecked")
    Map<String, ?> orgRoles = c.get("org_roles", Map.class);
    assertEquals(Set.of(orgA.toString()), orgRoles.keySet(), "overlay must not leak orgB");

    long orgEvents =
        dsl.fetchCount(
            DSL.table("impersonation_event"),
            DSL.field("tier").eq("ORG").and(DSL.field("scope_org_id").eq(orgA)));
    assertEquals(1, orgEvents);
  }

  @Test
  void orgOwner_cannotImpersonateAnotherOwner() {
    UUID orgA = org("acme");
    UUID owner = user("owner@x.io", true);
    orgRole(owner, orgA, OrgRole.OWNER);
    UUID target = user("t@x.io", true);
    orgRole(target, orgA, OrgRole.OWNER);

    assertThrows(
        AuthorizationException.class,
        () ->
            authService.impersonate(
                ownerCaller(owner, orgA), target, ImpersonationTier.ORG, orgA, null, env()));
  }

  @Test
  void orgTier_targetNotMember_throwsNotFound() {
    UUID orgA = org("acme");
    UUID owner = user("owner@x.io", true);
    orgRole(owner, orgA, OrgRole.OWNER);
    UUID target = user("t@x.io", true); // no role in orgA

    assertThrows(
        NotFoundException.class,
        () ->
            authService.impersonate(
                ownerCaller(owner, orgA), target, ImpersonationTier.ORG, orgA, null, env()));
  }

  @Test
  void orgTier_callerNotOwner_throwsAuthorization() {
    UUID orgA = org("acme");
    UUID manager = user("m@x.io", true);
    UUID target = user("t@x.io", true);
    orgRole(target, orgA, OrgRole.STAFF);
    SecurityContext managerCtx =
        new SecurityContext(
            manager, ActorType.USER, Set.of(), Map.of(orgA, Set.of(OrgRole.MANAGER)), Set.of(), 0);

    assertThrows(
        AuthorizationException.class,
        () ->
            authService.impersonate(managerCtx, target, ImpersonationTier.ORG, orgA, null, env()));
  }

  // ───────────────────────── common guard rails ─────────────────────────

  @Test
  void noNesting_throwsConflict() {
    UUID admin = user("admin@x.io", true);
    UUID target = user("t@x.io", true);
    SecurityContext alreadyImpersonating =
        new SecurityContext(
            admin,
            ActorType.USER,
            Set.of(SystemRole.ADMIN),
            Map.of(),
            Set.of(),
            0,
            UUID.randomUUID(), // act present
            ImpersonationTier.PLATFORM,
            null,
            false);

    assertThrows(
        ConflictException.class,
        () ->
            authService.impersonate(
                alreadyImpersonating, target, ImpersonationTier.PLATFORM, null, null, env()));
  }

  @Test
  void cannotImpersonateSelf() {
    UUID admin = user("admin@x.io", true);
    assertThrows(
        ValidationException.class,
        () ->
            authService.impersonate(
                platformCaller(admin, SystemRole.ADMIN),
                admin,
                ImpersonationTier.PLATFORM,
                null,
                null,
                env()));
  }

  @Test
  void disabledTarget_throwsAuthorization() {
    UUID admin = user("admin@x.io", true);
    UUID target = user("t@x.io", false);
    assertThrows(
        AuthorizationException.class,
        () ->
            authService.impersonate(
                platformCaller(admin, SystemRole.ADMIN),
                target,
                ImpersonationTier.PLATFORM,
                null,
                null,
                env()));
  }

  // ───────────────────────── stop ─────────────────────────

  @Test
  void stop_returnsDriverToken_withoutAct_writesStopEvent() {
    UUID admin = user("admin@x.io", true);
    systemRole(admin, SystemRole.ADMIN);
    UUID target = user("t@x.io", true);
    SecurityContext overlay =
        new SecurityContext(
            target,
            ActorType.USER,
            Set.of(),
            Map.of(),
            Set.of(),
            0,
            admin,
            ImpersonationTier.PLATFORM,
            null,
            false);

    AuthService.ImpersonationResult r = authService.stopImpersonating(overlay, env());

    assertNull(r.impersonatorId());
    Claims c = decode(r.accessToken());
    assertEquals(admin.toString(), c.getSubject());
    assertNull(c.get("act", String.class), "restored token has no act claim");
    // The driver's real ADMIN role is back on their own token.
    @SuppressWarnings("unchecked")
    java.util.List<String> sys = c.get("system_roles", java.util.List.class);
    assertTrue(sys.contains("ADMIN"));
    assertEquals(1, eventCount("STOP"));
  }

  @Test
  void stop_whenNotImpersonating_throwsValidation() {
    UUID user = user("u@x.io", true);
    SecurityContext normal =
        new SecurityContext(user, ActorType.USER, Set.of(), Map.of(), Set.of(), 0);
    assertThrows(ValidationException.class, () -> authService.stopImpersonating(normal, env()));
  }
}
