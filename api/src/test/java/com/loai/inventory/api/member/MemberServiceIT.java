package com.loai.inventory.api.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.common.security.JwtUtil;
import com.loai.inventory.common.security.PasswordHasher;
import com.loai.inventory.domain.model.AppUser;
import com.loai.inventory.domain.model.OrgMember;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.repository.ImpersonationEventRepositoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryImpl;
import com.loai.inventory.service.MemberService;
import com.loai.inventory.service.auth.AuthService;
import com.loai.inventory.service.auth.RefreshTokenStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;

/**
 * End-to-end coverage of {@link MemberService} and {@link AuthService#changePassword} against a
 * real database + Redis — the roster read, add / set-role / remove, the last-owner guard, the
 * de-privilege logout-all on authority reduction, and the self password change. Story 09.
 */
@Testcontainers
class MemberServiceIT {

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
  static MemberService memberService;
  static AuthService authService;
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
    authService =
        new AuthService(
            new UserRepositoryImpl(dsl),
            refreshTokenStore,
            jwtUtil,
            new ImpersonationEventRepositoryImpl(dsl),
            300_000L);
    memberService = new MemberService(dsl, new UserRepositoryFactoryImpl(), authService);
    userRepo = new UserRepositoryImpl(dsl);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute("TRUNCATE user_system_role, user_org_role, app_user, org RESTART IDENTITY CASCADE");
  }

  // ───────────────────────── seeding helpers ─────────────────────────

  private UUID user(String email) {
    return userWithPassword(email, "x");
  }

  private UUID userWithPassword(String email, String rawPassword) {
    UUID id = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO app_user(id,email,password_hash,actor_type,active,token_version)"
            + " VALUES (?,?,?,?::actor_type,?,?)",
        id,
        email,
        rawPassword.equals("x") ? "x" : PasswordHasher.hash(rawPassword),
        "USER",
        true,
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
        "INSERT INTO user_org_role(user_id,org_id,role) VALUES (?,?,?::org_role)"
            + " ON CONFLICT DO NOTHING",
        userId,
        orgId,
        role.name());
  }

  // ───────────────────────── roster ─────────────────────────

  @Test
  void listMembers_aggregatesRoles_emailOrder() {
    UUID orgId = org("acme");
    UUID bob = user("bob@x.io");
    UUID alice = user("alice@x.io");
    orgRole(bob, orgId, OrgRole.STAFF);
    orgRole(alice, orgId, OrgRole.OWNER);
    orgRole(alice, orgId, OrgRole.VIEWER); // two roles → aggregated

    List<OrgMember> members = memberService.listMembers(orgId);

    assertEquals(2, members.size());
    assertEquals("alice@x.io", members.get(0).email(), "email ASC order");
    assertEquals(2, members.get(0).roles().size());
    assertTrue(members.get(0).roles().contains(OrgRole.OWNER));
    assertTrue(members.get(0).roles().contains(OrgRole.VIEWER));
    assertEquals("bob@x.io", members.get(1).email());
    assertEquals(1, members.get(1).roles().size());
  }

  @Test
  void listMembers_excludesOtherOrgs() {
    UUID orgA = org("a");
    UUID orgB = org("b");
    UUID u = user("u@x.io");
    orgRole(u, orgB, OrgRole.OWNER);
    assertTrue(memberService.listMembers(orgA).isEmpty());
  }

  // ───────────────────────── add ─────────────────────────

  @Test
  void addMember_grantsRole_noTokenBump() {
    UUID orgId = org("acme");
    UUID target = user("t@x.io");

    OrgMember m = memberService.addMember(orgId, "t@x.io", OrgRole.STAFF);

    assertTrue(m.roles().contains(OrgRole.STAFF));
    assertEquals(0, userRepo.getTokenVersion(target), "a grant must not force logout");
  }

  @Test
  void addMember_trimsEmail() {
    UUID orgId = org("acme");
    user("t@x.io");
    OrgMember m = memberService.addMember(orgId, "  t@x.io  ", OrgRole.VIEWER);
    assertEquals("t@x.io", m.email());
  }

  @Test
  void addMember_unknownEmail_notFound() {
    UUID orgId = org("acme");
    assertThrows(
        NotFoundException.class, () -> memberService.addMember(orgId, "ghost@x.io", OrgRole.STAFF));
  }

  @Test
  void addMember_alreadyMember_conflict() {
    UUID orgId = org("acme");
    UUID target = user("t@x.io");
    orgRole(target, orgId, OrgRole.VIEWER);
    assertThrows(
        ConflictException.class, () -> memberService.addMember(orgId, "t@x.io", OrgRole.STAFF));
  }

  @Test
  void addMember_blankEmail_validation() {
    UUID orgId = org("acme");
    assertThrows(
        ValidationException.class, () -> memberService.addMember(orgId, "  ", OrgRole.STAFF));
  }

  // ───────────────────────── set role ─────────────────────────

  @Test
  void setRole_replacesToSingleRole() {
    UUID orgId = org("acme");
    UUID target = user("t@x.io");
    orgRole(target, orgId, OrgRole.VIEWER);
    orgRole(target, orgId, OrgRole.STAFF); // multi-role start

    OrgMember m = memberService.setRole(orgId, target, OrgRole.MANAGER);

    assertEquals(1, m.roles().size(), "set-replaces collapses to one role");
    assertTrue(m.roles().contains(OrgRole.MANAGER));
  }

  @Test
  void setRole_promotion_noTokenBump() {
    UUID orgId = org("acme");
    UUID target = user("t@x.io");
    orgRole(target, orgId, OrgRole.STAFF);

    memberService.setRole(orgId, target, OrgRole.MANAGER);

    assertEquals(0, userRepo.getTokenVersion(target), "promotion is not a de-privilege");
  }

  @Test
  void setRole_demotion_bumpsToken() {
    UUID orgId = org("acme");
    UUID owner1 = user("owner1@x.io");
    UUID owner2 = user("owner2@x.io");
    orgRole(owner1, orgId, OrgRole.OWNER);
    orgRole(owner2, orgId, OrgRole.OWNER); // keep ≥1 owner after demotion

    memberService.setRole(orgId, owner2, OrgRole.STAFF);

    assertEquals(1, userRepo.getTokenVersion(owner2), "demotion must force logout-all");
    assertTrue(userRepo.findRolesInOrg(owner2, orgId).contains(OrgRole.STAFF));
  }

  @Test
  void setRole_demoteLastOwner_conflict() {
    UUID orgId = org("acme");
    UUID owner = user("owner@x.io");
    orgRole(owner, orgId, OrgRole.OWNER);

    assertThrows(
        ConflictException.class, () -> memberService.setRole(orgId, owner, OrgRole.MANAGER));
    assertTrue(userRepo.findRolesInOrg(owner, orgId).contains(OrgRole.OWNER), "rolled back");
  }

  @Test
  void setRole_nonMember_notFound() {
    UUID orgId = org("acme");
    UUID stranger = user("s@x.io");
    assertThrows(
        NotFoundException.class, () -> memberService.setRole(orgId, stranger, OrgRole.STAFF));
  }

  // ───────────────────────── remove ─────────────────────────

  @Test
  void removeMember_removesAllRoles_bumpsToken() {
    UUID orgId = org("acme");
    UUID target = user("t@x.io");
    orgRole(target, orgId, OrgRole.STAFF);

    memberService.removeMember(orgId, target);

    assertTrue(userRepo.findRolesInOrg(target, orgId).isEmpty());
    assertEquals(1, userRepo.getTokenVersion(target), "removal must force logout-all");
  }

  @Test
  void removeMember_lastOwner_conflict() {
    UUID orgId = org("acme");
    UUID owner = user("owner@x.io");
    orgRole(owner, orgId, OrgRole.OWNER);

    assertThrows(ConflictException.class, () -> memberService.removeMember(orgId, owner));
    assertTrue(userRepo.findRolesInOrg(owner, orgId).contains(OrgRole.OWNER), "rolled back");
  }

  @Test
  void removeMember_ownerWithAnotherOwner_ok() {
    UUID orgId = org("acme");
    UUID owner1 = user("owner1@x.io");
    UUID owner2 = user("owner2@x.io");
    orgRole(owner1, orgId, OrgRole.OWNER);
    orgRole(owner2, orgId, OrgRole.OWNER);

    memberService.removeMember(orgId, owner1);

    assertTrue(userRepo.findRolesInOrg(owner1, orgId).isEmpty());
    assertTrue(userRepo.findRolesInOrg(owner2, orgId).contains(OrgRole.OWNER));
  }

  @Test
  void removeMember_nonMember_notFound() {
    UUID orgId = org("acme");
    UUID stranger = user("s@x.io");
    assertThrows(NotFoundException.class, () -> memberService.removeMember(orgId, stranger));
  }

  // ───────────────────────── self password change ─────────────────────────

  @Test
  void changePassword_rotatesHash_bumpsToken() {
    UUID target = userWithPassword("t@x.io", "old-password");

    AuthService.LoginResult result =
        authService.changePassword(target, "old-password", "new-password", "junit", "1.2.3.4");

    AppUser reloaded = userRepo.findById(target).orElseThrow();
    assertTrue(PasswordHasher.verify("new-password", reloaded.getPasswordHash()));
    assertFalse(PasswordHasher.verify("old-password", reloaded.getPasswordHash()));
    assertEquals(1, userRepo.getTokenVersion(target), "change must revoke other sessions");
    assertTrue(result.accessToken() != null && result.refreshToken() != null, "re-cookied");
  }

  @Test
  void changePassword_wrongCurrent_validation() {
    UUID target = userWithPassword("t@x.io", "old-password");
    assertThrows(
        ValidationException.class,
        () -> authService.changePassword(target, "wrong", "new-password", "junit", "1.2.3.4"));
    assertEquals(0, userRepo.getTokenVersion(target), "no bump on failed change");
  }

  @Test
  void changePassword_weakNew_validation() {
    UUID target = userWithPassword("t@x.io", "old-password");
    assertThrows(
        ValidationException.class,
        () -> authService.changePassword(target, "old-password", "short", "junit", "1.2.3.4"));
  }

  @Test
  void changePassword_sameAsCurrent_validation() {
    UUID target = userWithPassword("t@x.io", "old-password");
    assertThrows(
        ValidationException.class,
        () ->
            authService.changePassword(target, "old-password", "old-password", "junit", "1.2.3.4"));
  }
}
