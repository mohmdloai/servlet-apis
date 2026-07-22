package com.loai.inventory.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.ConflictException;
import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.common.exception.ValidationException;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.repository.AppUserMagicTokenRepositoryFactoryImpl;
import com.loai.inventory.repository.OrgHealthRepositoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformAuditRepositoryFactoryImpl;
import com.loai.inventory.repository.UserRepositoryFactoryImpl;
import com.loai.inventory.service.auth.AuthMailer;
import com.loai.inventory.service.auth.CredentialTokenService;
import com.loai.inventory.service.platform.OrgStatusService;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.PlatformOrgService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 * End-to-end coverage of {@link PlatformOrgService} against a real database - the cross-org list
 * (pagination, status filter, distinct member counts) and the per-org operational rollup. The
 * console is deliberately role-agnostic at the service layer: a platform operator sees every org
 * regardless of the roles they hold (authz is enforced at the handler, covered separately).
 */
@Testcontainers
class PlatformOrgServiceIT {

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
  static PlatformOrgService service;
  static com.loai.inventory.repository.UserRepositoryImpl userRepo;

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

    OrgRepositoryFactoryImpl orgRepoFactory = new OrgRepositoryFactoryImpl();
    JedisPool jedisPool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
    PlatformAuditService audit =
        new PlatformAuditService(dsl, new PlatformAuditRepositoryFactoryImpl());
    OrgStatusService orgStatus = new OrgStatusService(jedisPool, dsl, orgRepoFactory);
    CredentialTokenService credentialTokenService =
        new CredentialTokenService(
            dsl,
            new AppUserMagicTokenRepositoryFactoryImpl(),
            "http://localhost:8080",
            Duration.ofMinutes(120),
            Duration.ofDays(7),
            Duration.ofHours(48));
    AuthMailer authMailer = new AuthMailer(msg -> {}); // no-op sender; the IT asserts the token row
    service =
        new PlatformOrgService(
            dsl,
            orgRepoFactory,
            new UserRepositoryFactoryImpl(),
            new OrgHealthRepositoryImpl(dsl),
            audit,
            orgStatus,
            credentialTokenService,
            authMailer);
    userRepo = new com.loai.inventory.repository.UserRepositoryImpl(dsl);
  }

  @AfterAll
  static void stopInfra() {
    if (dataSource != null) dataSource.close();
  }

  @BeforeEach
  void reset() {
    dsl.execute(
        "TRUNCATE platform_audit, payment, payment_transaction, sales_order, user_system_role,"
            + " user_org_role, app_user, org RESTART IDENTITY CASCADE");
  }

  private UUID org(String slug, boolean active) {
    UUID id = UUID.randomUUID();
    dsl.execute("INSERT INTO org(id,name,slug,active) VALUES (?,?,?,?)", id, slug, slug, active);
    return id;
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

  private void orgRole(UUID userId, UUID orgId, OrgRole role) {
    dsl.execute(
        "INSERT INTO user_org_role(user_id,org_id,role) VALUES (?,?,?::org_role)",
        userId,
        orgId,
        role.name());
  }

  private void pendingOrder(UUID orgId) {
    dsl.execute(
        "INSERT INTO sales_order(id,org_id,order_number,channel,status,subtotal,tax_total,"
            + "discount_total,grand_total,currency,prepaid_amount)"
            + " VALUES (?,?,?,?::order_channel,?::order_status,?,?,?,?,?,?)",
        UUID.randomUUID(),
        orgId,
        "SO-" + UUID.randomUUID(),
        "ONLINE",
        "PENDING_PAYMENT",
        new BigDecimal("100.00"),
        new BigDecimal("0.00"),
        new BigDecimal("0.00"),
        new BigDecimal("100.00"),
        "EGP",
        new BigDecimal("0.00"));
  }

  private void payment(UUID orgId, String status, String unallocated) {
    UUID txId = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO payment_transaction(id,org_id,provider,provider_ref,direction,amount,"
            + "currency,verification_status,occurred_at)"
            + " VALUES (?,?,?::payment_provider,?,?::payment_direction,?,?,?::payment_verification_status,now())",
        txId,
        orgId,
        "instapay_manual",
        "ref-" + txId,
        "CREDIT",
        new BigDecimal("100.00"),
        "EGP",
        "VERIFIED");
    dsl.execute(
        "INSERT INTO payment(id,org_id,payment_transaction_id,amount,currency,unallocated_amount,"
            + "status,received_at,refunded_amount)"
            + " VALUES (?,?,?,?,?,?,?::payment_status,now(),?)",
        UUID.randomUUID(),
        orgId,
        txId,
        new BigDecimal("100.00"),
        "EGP",
        new BigDecimal(unallocated),
        status,
        new BigDecimal("0.00"));
  }

  @Test
  void list_returnsEveryOrg_evenWithNoCallerRole() {
    org("acme", true);
    org("globex", true);
    org("initech", true);

    PlatformOrgService.OrgPage page = service.list(0, 20, null);

    assertEquals(3, page.total());
    assertEquals(3, page.items().size());
  }

  @Test
  void list_paginates() {
    for (int i = 0; i < 5; i++) {
      org("org-" + i, true);
    }

    PlatformOrgService.OrgPage first = service.list(0, 2, null);
    assertEquals(5, first.total());
    assertEquals(2, first.items().size());

    PlatformOrgService.OrgPage last = service.list(2, 2, null); // offset 4 → 1 remaining
    assertEquals(5, last.total());
    assertEquals(1, last.items().size());
  }

  @Test
  void list_filtersByStatus() {
    org("live-1", true);
    org("live-2", true);
    org("dead", false);

    assertEquals(2, service.list(0, 20, Boolean.TRUE).total());
    assertEquals(1, service.list(0, 20, Boolean.FALSE).total());
    assertEquals(3, service.list(0, 20, null).total());
  }

  @Test
  void list_countsDistinctMembers() {
    UUID orgId = org("acme", true);
    UUID alice = user("alice@x.io");
    UUID bob = user("bob@x.io");
    orgRole(alice, orgId, OrgRole.OWNER);
    orgRole(alice, orgId, OrgRole.MANAGER); // same user, two roles → still one member
    orgRole(bob, orgId, OrgRole.STAFF);

    PlatformOrgService.OrgListItem item =
        service.list(0, 20, null).items().stream()
            .filter(i -> i.org().getId().equals(orgId))
            .findFirst()
            .orElseThrow();

    assertEquals(2, item.memberCount());
  }

  @Test
  void getWithHealth_rollsUpOrgScopedAggregates() {
    UUID orgId = org("acme", true);
    UUID alice = user("alice@x.io");
    orgRole(alice, orgId, OrgRole.OWNER);

    // Noise in another org must not bleed into this org's rollup.
    UUID other = org("globex", true);
    pendingOrder(other);
    payment(other, "DISPUTED", "0.00");

    pendingOrder(orgId);
    payment(orgId, "DISPUTED", "0.00"); // an open dispute, fully allocated
    payment(orgId, "RECEIVED", "50.00"); // carries an unallocated balance

    PlatformOrgService.OrgWithHealth detail = service.getWithHealth(orgId);

    assertEquals("acme", detail.org().getSlug());
    assertEquals(1, detail.health().memberCount());
    assertEquals(1, detail.health().pendingPaymentOrders());
    assertEquals(1, detail.health().openDisputes());
    assertEquals(1, detail.health().unallocatedPayments());
  }

  @Test
  void getWithHealth_emptyOrg_allZero() {
    UUID orgId = org("acme", true);
    PlatformOrgService.OrgWithHealth detail = service.getWithHealth(orgId);
    assertEquals(0, detail.health().memberCount());
    assertEquals(0, detail.health().pendingPaymentOrders());
    assertEquals(0, detail.health().openDisputes());
    assertEquals(0, detail.health().unallocatedPayments());
  }

  @Test
  void getWithHealth_unknownOrg_throwsNotFound() {
    assertThrows(NotFoundException.class, () -> service.getWithHealth(UUID.randomUUID()));
  }

  @Test
  void list_clampsSizeToMax() {
    org("acme", true);
    PlatformOrgService.OrgPage page = service.list(0, 10_000, null);
    assertTrue(page.size() <= PlatformOrgService.MAX_PAGE_SIZE);
  }

  @Test
  void suspend_deactivates_stampsReason_audits() {
    UUID orgId = org("acme", true);

    var updated = service.suspend(admin(), env(), orgId, "fraud investigation");

    assertFalse(updated.isActive());
    assertFalse(
        dsl.select(DSL.field("active"))
            .from("org")
            .where("id = ?", orgId)
            .fetchOne(0, Boolean.class));
    assertEquals(
        "fraud investigation",
        dsl.select(DSL.field("suspended_reason"))
            .from("org")
            .where("id = ?", orgId)
            .fetchOne(0, String.class));
    assertEquals(1, auditCount("ORG_SUSPEND"));
  }

  @Test
  void reactivate_restores_clearsStamp_audits() {
    UUID orgId = org("acme", true);
    service.suspend(admin(), env(), orgId, "temp");

    var updated = service.reactivate(admin(), env(), orgId);

    assertTrue(updated.isActive());
    assertTrue(
        dsl.select(DSL.field("active"))
            .from("org")
            .where("id = ?", orgId)
            .fetchOne(0, Boolean.class));
    org.junit.jupiter.api.Assertions.assertNull(
        dsl.select(DSL.field("suspended_reason"))
            .from("org")
            .where("id = ?", orgId)
            .fetchOne(0, String.class));
    assertEquals(1, auditCount("ORG_REACTIVATE"));
  }

  @Test
  void suspend_unknownOrg_throwsNotFound() {
    assertThrows(
        NotFoundException.class, () -> service.suspend(admin(), env(), UUID.randomUUID(), null));
  }

  @Test
  void provision_newOwnerEmail_mintsUser_createsOrg_grantsOwner_audits() {
    PlatformOrgService.ProvisionResult result =
        service.provision(admin(), env(), "Acme Inc", "acme", "client@x.io");

    assertTrue(result.ownerMinted());
    assertEquals("acme", result.org().getSlug());
    assertTrue(result.org().isActive());
    // org persisted
    assertEquals(1, dsl.fetchCount(DSL.table("org"), DSL.field("slug").eq("acme")));
    // owner minted and granted OWNER in the org
    UUID ownerId = result.owner().getId();
    assertTrue(userRepo.findByEmail("client@x.io").isPresent());
    assertTrue(
        userRepo.findOrgRoles(ownerId).stream()
            .anyMatch(
                r -> r.getOrgId().equals(result.org().getId()) && r.getRole() == OrgRole.OWNER));
    assertEquals(1, auditCount("ORG_CREATE"));
    assertEquals(1, auditCount("USER_CREATE"));
    // A minted owner gets a first-password INVITE token (the account is otherwise unreachable).
    assertEquals(
        1,
        dsl.fetchCount(
            DSL.table("app_user_magic_token"),
            DSL.field("user_id")
                .eq(ownerId)
                .and(DSL.field("purpose").eq("INVITE"))
                .and(DSL.field("consumed_at").isNull())));
  }

  @Test
  void provision_existingOwnerEmail_attaches_notMinted() {
    UUID existing = user("client@x.io");

    PlatformOrgService.ProvisionResult result =
        service.provision(admin(), env(), "Acme", "acme", "client@x.io");

    assertFalse(result.ownerMinted());
    assertEquals(existing, result.owner().getId());
    assertEquals(0, auditCount("USER_CREATE"));
    assertEquals(1, auditCount("ORG_CREATE"));
    assertTrue(
        userRepo.findOrgRoles(existing).stream()
            .anyMatch(
                r -> r.getOrgId().equals(result.org().getId()) && r.getRole() == OrgRole.OWNER));
  }

  @Test
  void provision_duplicateSlug_conflict_rollsBackMint() {
    org("acme", true);

    assertThrows(
        ConflictException.class,
        () -> service.provision(admin(), env(), "Acme", "acme", "client@x.io"));

    // the mint + org-create + grant are one transaction, so the conflict leaves no orphan user
    assertEquals(0, dsl.fetchCount(DSL.table("app_user"), DSL.field("email").eq("client@x.io")));
  }

  @Test
  void provision_invalidSlug_validation() {
    assertThrows(
        ValidationException.class,
        () -> service.provision(admin(), env(), "Acme", "A", "client@x.io"));
  }

  @Test
  void provision_blankOwnerEmail_validation() {
    assertThrows(
        ValidationException.class, () -> service.provision(admin(), env(), "Acme", "acme", "  "));
  }

  @Test
  void updateOrg_editsNameAndPolicy_audits() {
    UUID orgId = org("acme", true);

    var updated =
        service.updateOrg(admin(), env(), orgId, "Acme Renamed", new BigDecimal("500.00"), 60);

    assertEquals("Acme Renamed", updated.getName());
    assertEquals(0, new BigDecimal("500.00").compareTo(updated.getRefundApprovalThreshold()));
    assertEquals(60, updated.getOrderTtlMinutes());
    assertEquals(1, auditCount("ORG_UPDATE"));
  }

  @Test
  void updateOrg_nullPolicy_leavesKnobsUnchanged() {
    UUID orgId = org("acme", true);
    service.updateOrg(admin(), env(), orgId, "Acme", new BigDecimal("300"), 90);

    var again = service.updateOrg(admin(), env(), orgId, "Acme2", null, null);

    assertEquals("Acme2", again.getName());
    assertEquals(0, new BigDecimal("300").compareTo(again.getRefundApprovalThreshold()));
    assertEquals(90, again.getOrderTtlMinutes());
  }

  @Test
  void updateOrg_unknownOrg_notFound() {
    assertThrows(
        NotFoundException.class,
        () -> service.updateOrg(admin(), env(), UUID.randomUUID(), "X", null, null));
  }

  @Test
  void updateOrg_ttlOutOfRange_validation() {
    UUID orgId = org("acme", true);
    assertThrows(
        ValidationException.class, () -> service.updateOrg(admin(), env(), orgId, "Acme", null, 5));
  }

  // ───────── C2: SEO metadata parity on the admin plane (PATCH /api/admin/orgs) ─────────

  @Test
  void updateOrg_setsSeoMetadata_merges_clears_audits() {
    UUID orgId = org("acme", true);
    String ogKey = orgId + "/og/share.png";

    service.updateOrg(
        admin(),
        env(),
        orgId,
        "Acme",
        null,
        null,
        null,
        new com.loai.inventory.service.OrgService.SeoMetadata("Title", "Desc", ogKey));

    assertEquals(
        "Title",
        dsl.select(DSL.field("meta_title"))
            .from("org")
            .where("id = ?", orgId)
            .fetchOne(0, String.class));
    assertEquals(
        ogKey,
        dsl.select(DSL.field("og_image_object_key"))
            .from("org")
            .where("id = ?", orgId)
            .fetchOne(0, String.class));

    // Blank clears; null leaves unchanged.
    service.updateOrg(
        admin(),
        env(),
        orgId,
        "Acme",
        null,
        null,
        null,
        new com.loai.inventory.service.OrgService.SeoMetadata("", null, null));
    org.junit.jupiter.api.Assertions.assertNull(
        dsl.select(DSL.field("meta_title"))
            .from("org")
            .where("id = ?", orgId)
            .fetchOne(0, String.class));
    assertEquals(
        ogKey,
        dsl.select(DSL.field("og_image_object_key"))
            .from("org")
            .where("id = ?", orgId)
            .fetchOne(0, String.class));
  }

  @Test
  void updateOrg_overLengthMetaTitle_validation() {
    UUID orgId = org("acme", true);
    String tooLong = "x".repeat(71);
    assertThrows(
        ValidationException.class,
        () ->
            service.updateOrg(
                admin(),
                env(),
                orgId,
                "Acme",
                null,
                null,
                null,
                new com.loai.inventory.service.OrgService.SeoMetadata(tooLong, null, null)));
  }

  @Test
  void updateOrg_foreignOgImageKey_validation() {
    UUID orgId = org("acme", true);
    String foreign = UUID.randomUUID() + "/og/x.png";
    assertThrows(
        ValidationException.class,
        () ->
            service.updateOrg(
                admin(),
                env(),
                orgId,
                "Acme",
                null,
                null,
                null,
                new com.loai.inventory.service.OrgService.SeoMetadata(null, null, foreign)));
  }

  private SecurityContext admin() {
    // The actor must be a real user - platform_audit.actor_id is a FK into app_user.
    UUID id = user("admin-" + UUID.randomUUID() + "@x.io");
    return new SecurityContext(id, ActorType.USER, Set.of(SystemRole.ADMIN), Map.of(), Set.of(), 0);
  }

  private Environment env() {
    return new Environment(Instant.now(), "1.2.3.4", "junit");
  }

  private long auditCount(String action) {
    return dsl.fetchCount(DSL.table("platform_audit"), DSL.field("action").eq(action));
  }
}
