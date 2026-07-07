package com.loai.inventory.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.common.security.PasswordHasher;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.repository.ImpersonationEventRepositoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformAuditRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.RefreshTokenStore;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.UserAdminService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
 * End-to-end coverage of {@link UserAdminService} against a real database + Redis - the guard rails
 * (self-lockout, last-admin), the de-privilege revocation (a role change bumps {@code
 * token_version} so live tokens die), and the {@code platform_audit} trail every mutation must
 * leave.
 */
@Testcontainers
class UserAdminServiceIT {

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
  static UserAdminService service;
  static UserRepositoryImpl userRepo;

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

    JwtUtil jwtUtil = new JwtUtil(Base64.getEncoder().encodeToString(new byte[48]), 900_000L);
    RefreshTokenStore refreshTokenStore =
        new RefreshTokenStore(new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379)));
    AuthService authService =
        new AuthService(
            new UserRepositoryImpl(dsl),
            refreshTokenStore,
            jwtUtil,
            new ImpersonationEventRepositoryImpl(dsl),
            300_000L);
    PlatformAuditService audit =
        new PlatformAuditService(dsl, new PlatformAuditRepositoryFactoryImpl());
    service =
        new UserAdminService(
            dsl,
            new UserRepositoryFactoryImpl(),
            new OrgRepositoryFactoryImpl(),
            authService,
            audit);
    userRepo = new UserRepositoryImpl(dsl);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE platform_audit, impersonation_event, user_system_role, user_org_role, app_user,"
            + " org RESTART IDENTITY CASCADE");
  }

  private UUID user(String email) {
    UUID id = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO app_user(id,email,password_hash,actor_type,active,token_version)"
            + " VALUES (?,?,?,?::actor_type,?,?)",
        id,
        email,
        "x",
        "USER",
        true,
        0);
    return id;
  }

  private UUID userWithActive(String email, boolean active) {
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

  private void systemRole(UUID userId, SystemRole role) {
    dsl.execute(
        "INSERT INTO user_system_role(user_id,role) VALUES (?,?::system_role)",
        userId,
        role.name());
  }

  private UUID org(String slug) {
    UUID id = UUID.randomUUID();
    dsl.execute("INSERT INTO org(id,name,slug) VALUES (?,?,?)", id, slug, slug);
    return id;
  }

  private SecurityContext admin(UUID id) {
    return new SecurityContext(id, ActorType.USER, Set.of(SystemRole.ADMIN), Map.of(), Set.of(), 0);
  }

  private Environment env() {
    return new Environment(Instant.now(), "1.2.3.4", "junit");
  }

  private long auditCount(String action) {
    return dsl.fetchCount(DSL.table("platform_audit"), DSL.field("action").eq(action));
  }

  private boolean hasSystemRole(UUID userId, SystemRole role) {
    return userRepo.findSystemRoles(userId).contains(role);
  }

  @Test
  void createUser_persistsAndAudits() {
    UUID actor = user("admin@x.io");
    systemRole(actor, SystemRole.ADMIN);

    AppUser created =
        service.createUser(admin(actor), env(), "new@x.io", "secret123", ActorType.USER);

    assertTrue(userRepo.findByEmail("new@x.io").isPresent());
    assertTrue(created.isActive());
    assertEquals(1, auditCount("USER_CREATE"));
  }

  @Test
  void createUser_duplicateEmail_conflict() {
    UUID actor = user("admin@x.io");
    user("dupe@x.io");
    assertThrows(
        ConflictException.class,
        () -> service.createUser(admin(actor), env(), "dupe@x.io", "pw", ActorType.USER));
  }

  @Test
  void createUser_rejectsSystemActorType() {
    UUID actor = user("admin@x.io");
    assertThrows(
        ValidationException.class,
        () -> service.createUser(admin(actor), env(), "svc@x.io", "pw", ActorType.SYSTEM));
  }

  @Test
  void createUser_blankPassword_makesUnusableAccount() {
    UUID actor = user("admin@x.io");
    AppUser created = service.createUser(admin(actor), env(), "nopw@x.io", null, ActorType.USER);
    assertFalse(PasswordHasher.verify("", created.getPasswordHash()));
    assertFalse(PasswordHasher.verify("password", created.getPasswordHash()));
  }

  @Test
  void disable_bumpsTokenVersion_andAudits() {
    UUID actor = user("admin@x.io");
    systemRole(actor, SystemRole.ADMIN);
    UUID target = user("t@x.io");
    assertEquals(0, userRepo.getTokenVersion(target));

    service.setActive(admin(actor), env(), target, false);

    assertFalse(userRepo.findById(target).orElseThrow().isActive());
    assertEquals(1, userRepo.getTokenVersion(target), "disable must force logout-all");
    assertEquals(1, auditCount("USER_DISABLE"));
  }

  @Test
  void disable_self_blocked() {
    UUID actor = user("admin@x.io");
    systemRole(actor, SystemRole.ADMIN);
    assertThrows(
        ValidationException.class, () -> service.setActive(admin(actor), env(), actor, false));
    assertTrue(userRepo.findById(actor).orElseThrow().isActive());
  }

  @Test
  void disable_lastAdmin_blocked() {
    UUID actor = user("actor@x.io"); // the operator (context only)
    UUID soleAdmin = user("admin@x.io");
    systemRole(soleAdmin, SystemRole.ADMIN);
    assertThrows(
        ValidationException.class, () -> service.setActive(admin(actor), env(), soleAdmin, false));
    assertTrue(userRepo.findById(soleAdmin).orElseThrow().isActive());
  }

  @Test
  void disable_lastActiveAdmin_ignoresDisabledAdmin_blocked() {
    // A previously-disabled admin still carries the ADMIN role but must NOT count as a usable
    // admin:
    // otherwise disabling the sole *active* admin would sail through and lock the platform out.
    UUID actor = user("actor@x.io");
    UUID soleActiveAdmin = user("live-admin@x.io");
    systemRole(soleActiveAdmin, SystemRole.ADMIN);
    UUID disabledAdmin = userWithActive("dead-admin@x.io", false);
    systemRole(disabledAdmin, SystemRole.ADMIN);

    assertThrows(
        ValidationException.class,
        () -> service.setActive(admin(actor), env(), soleActiveAdmin, false));
    assertTrue(userRepo.findById(soleActiveAdmin).orElseThrow().isActive());
  }

  @Test
  void grantSystemRole_addsRole_noTokenBump_audits() {
    UUID actor = user("admin@x.io");
    systemRole(actor, SystemRole.ADMIN);
    UUID target = user("t@x.io");

    service.grantSystemRole(admin(actor), env(), target, SystemRole.SUPPORT);

    assertTrue(hasSystemRole(target, SystemRole.SUPPORT));
    assertEquals(0, userRepo.getTokenVersion(target), "a grant must not force logout");
    assertEquals(1, auditCount("SYSTEM_ROLE_GRANT"));
  }

  @Test
  void revokeSystemRole_removesRole_bumpsToken_audits() {
    UUID actor = user("admin@x.io");
    systemRole(actor, SystemRole.ADMIN);
    UUID target = user("t@x.io");
    systemRole(target, SystemRole.ADMIN); // now two admins → not the last

    service.revokeSystemRole(admin(actor), env(), target, SystemRole.ADMIN);

    assertFalse(hasSystemRole(target, SystemRole.ADMIN));
    assertEquals(1, userRepo.getTokenVersion(target), "de-privilege must force logout-all");
    assertEquals(1, auditCount("SYSTEM_ROLE_REVOKE"));
  }

  @Test
  void revokeSystemRole_ownAdmin_blocked() {
    UUID actor = user("admin@x.io");
    systemRole(actor, SystemRole.ADMIN);
    user("second-admin@x.io"); // ensure not the last admin so only the self-guard can fire
    systemRole(userRepo.findByEmail("second-admin@x.io").orElseThrow().getId(), SystemRole.ADMIN);

    assertThrows(
        ValidationException.class,
        () -> service.revokeSystemRole(admin(actor), env(), actor, SystemRole.ADMIN));
    assertTrue(hasSystemRole(actor, SystemRole.ADMIN));
  }

  @Test
  void revokeSystemRole_notHeld_isNoOpNoLogout() {
    // Revoking a role the user never had must be an idempotent no-op: no audit row, and crucially
    // no forced logout - otherwise a repeatable DELETE becomes an involuntary global-logout weapon.
    UUID actor = user("admin@x.io");
    systemRole(actor, SystemRole.ADMIN);
    UUID target = user("t@x.io");

    service.revokeSystemRole(admin(actor), env(), target, SystemRole.SUPPORT);

    assertEquals(0, userRepo.getTokenVersion(target), "a no-op revoke must not force logout");
    assertEquals(0, auditCount("SYSTEM_ROLE_REVOKE"));
  }

  @Test
  void revokeSystemRole_lastAdmin_blocked() {
    UUID actor = user("actor@x.io");
    UUID soleAdmin = user("admin@x.io");
    systemRole(soleAdmin, SystemRole.ADMIN);
    assertThrows(
        ValidationException.class,
        () -> service.revokeSystemRole(admin(actor), env(), soleAdmin, SystemRole.ADMIN));
    assertTrue(hasSystemRole(soleAdmin, SystemRole.ADMIN));
  }

  @Test
  void grantOrgRole_addsRole_noTokenBump() {
    UUID actor = user("admin@x.io");
    UUID target = user("t@x.io");
    UUID orgId = org("acme");

    service.grantOrgRole(admin(actor), env(), target, orgId, OrgRole.STAFF);

    assertTrue(
        userRepo.findOrgRoles(target).stream()
            .anyMatch(r -> r.getOrgId().equals(orgId) && r.getRole() == OrgRole.STAFF));
    assertEquals(0, userRepo.getTokenVersion(target));
    assertEquals(1, auditCount("ORG_ROLE_GRANT"));
  }

  @Test
  void grantOrgRole_unknownOrg_notFound() {
    UUID actor = user("admin@x.io");
    UUID target = user("t@x.io");
    assertThrows(
        NotFoundException.class,
        () -> service.grantOrgRole(admin(actor), env(), target, UUID.randomUUID(), OrgRole.STAFF));
  }

  @Test
  void revokeOrgRole_removesRole_bumpsToken_audits() {
    UUID actor = user("admin@x.io");
    UUID target = user("t@x.io");
    UUID orgId = org("acme");
    service.grantOrgRole(admin(actor), env(), target, orgId, OrgRole.STAFF);

    service.revokeOrgRole(admin(actor), env(), target, orgId, OrgRole.STAFF);

    assertTrue(userRepo.findOrgRoles(target).isEmpty());
    assertEquals(1, userRepo.getTokenVersion(target), "de-privilege must force logout-all");
    assertEquals(1, auditCount("ORG_ROLE_REVOKE"));
  }

  @Test
  void revokeOrgRole_notHeld_isNoOpNoLogout() {
    UUID actor = user("admin@x.io");
    UUID target = user("t@x.io");
    UUID orgId = org("acme");

    service.revokeOrgRole(admin(actor), env(), target, orgId, OrgRole.STAFF);

    assertEquals(0, userRepo.getTokenVersion(target), "a no-op revoke must not force logout");
    assertEquals(0, auditCount("ORG_ROLE_REVOKE"));
  }

  @Test
  void resetPassword_updatesHash_bumpsToken_audits() {
    UUID actor = user("admin@x.io");
    UUID target = user("t@x.io");

    service.resetPassword(admin(actor), env(), target, "brand-new-pw");

    AppUser reloaded = userRepo.findById(target).orElseThrow();
    assertTrue(PasswordHasher.verify("brand-new-pw", reloaded.getPasswordHash()));
    assertEquals(1, userRepo.getTokenVersion(target), "reset must invalidate existing sessions");
    assertEquals(1, auditCount("PASSWORD_RESET"));
  }

  @Test
  void resetPassword_blank_validation() {
    UUID actor = user("admin@x.io");
    UUID target = user("t@x.io");
    assertThrows(
        ValidationException.class, () -> service.resetPassword(admin(actor), env(), target, "  "));
  }

  @Test
  void get_returnsRolesAndSessionCount() {
    UUID target = user("t@x.io");
    systemRole(target, SystemRole.SUPPORT);
    UUID orgId = org("acme");
    UUID actor = user("admin@x.io");
    service.grantOrgRole(admin(actor), env(), target, orgId, OrgRole.VIEWER);

    UserAdminService.UserDetail detail = service.get(target);

    assertTrue(detail.systemRoles().contains(SystemRole.SUPPORT));
    assertEquals(1, detail.orgRoles().size());
    assertEquals(0, detail.activeSessionCount()); // never logged in
  }

  @Test
  void list_paginates() {
    for (int i = 0; i < 5; i++) {
      user("u" + i + "@x.io");
    }
    UserAdminService.UserPage first = service.list(0, 2, null);
    assertEquals(5, first.total());
    assertEquals(2, first.users().size());
  }

  @Test
  void revokeOrgRole_lastOwner_blocked() {
    // The platform plane must honour the same "org keeps ≥1 OWNER" invariant as the OWNER plane -
    // else a platform ADMIN could strip an org's only OWNER and leave it ownerless.
    UUID actor = user("admin@x.io");
    UUID owner = user("owner@x.io");
    UUID orgId = org("acme");
    service.grantOrgRole(admin(actor), env(), owner, orgId, OrgRole.OWNER);

    assertThrows(
        ConflictException.class,
        () -> service.revokeOrgRole(admin(actor), env(), owner, orgId, OrgRole.OWNER));

    assertTrue(
        userRepo.findOrgRoles(owner).stream()
            .anyMatch(r -> r.getOrgId().equals(orgId) && r.getRole() == OrgRole.OWNER),
        "the blocked revoke must leave the OWNER role intact");
    assertEquals(0, userRepo.getTokenVersion(owner), "a blocked revoke forces no logout");
  }

  @Test
  void revokeOrgRole_ownerNotLast_ok() {
    UUID actor = user("admin@x.io");
    UUID owner1 = user("o1@x.io");
    UUID owner2 = user("o2@x.io");
    UUID orgId = org("acme");
    service.grantOrgRole(admin(actor), env(), owner1, orgId, OrgRole.OWNER);
    service.grantOrgRole(admin(actor), env(), owner2, orgId, OrgRole.OWNER);

    service.revokeOrgRole(admin(actor), env(), owner1, orgId, OrgRole.OWNER);

    assertTrue(userRepo.findOrgRoles(owner1).isEmpty());
    assertEquals(1, userRepo.getTokenVersion(owner1), "de-privilege forces logout-all");
    assertEquals(1, auditCount("ORG_ROLE_REVOKE"));
  }

  @Test
  void disable_soleOwner_blocked() {
    // Disabling the sole OWNER is a second vector to the same ownerless state - a disabled owner
    // cannot log in, so the org is effectively ownerless.
    UUID actor = user("admin@x.io");
    UUID owner = user("owner@x.io");
    UUID orgId = org("acme");
    service.grantOrgRole(admin(actor), env(), owner, orgId, OrgRole.OWNER);

    assertThrows(
        ConflictException.class, () -> service.setActive(admin(actor), env(), owner, false));

    assertTrue(userRepo.findById(owner).orElseThrow().isActive());
    assertEquals(0, userRepo.getTokenVersion(owner));
  }

  @Test
  void disable_ownerNotSole_ok() {
    UUID actor = user("admin@x.io");
    UUID owner1 = user("o1@x.io");
    UUID owner2 = user("o2@x.io");
    UUID orgId = org("acme");
    service.grantOrgRole(admin(actor), env(), owner1, orgId, OrgRole.OWNER);
    service.grantOrgRole(admin(actor), env(), owner2, orgId, OrgRole.OWNER);

    service.setActive(admin(actor), env(), owner1, false);

    assertFalse(userRepo.findById(owner1).orElseThrow().isActive());
  }
}
