package com.loai.inventory.api.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loai.inventory.common.exception.NotFoundException;
import com.loai.inventory.domain.model.ActorType;
import com.loai.inventory.domain.model.Environment;
import com.loai.inventory.domain.model.OrgRole;
import com.loai.inventory.domain.model.SecurityContext;
import com.loai.inventory.domain.model.SystemRole;
import com.loai.inventory.repository.OrgHealthRepositoryImpl;
import com.loai.inventory.repository.OrgRepositoryFactoryImpl;
import com.loai.inventory.repository.PlatformAuditRepositoryFactoryImpl;
import com.loai.inventory.service.platform.OrgStatusService;
import com.loai.inventory.service.platform.PlatformAuditService;
import com.loai.inventory.service.platform.PlatformOrgService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
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
    service =
        new PlatformOrgService(
            dsl, orgRepoFactory, new OrgHealthRepositoryImpl(dsl), audit, orgStatus);
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

  // ───────────────────────── seeding helpers ─────────────────────────

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

  // ───────────────────────── list ─────────────────────────

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

  // ───────────────────────── detail + health ─────────────────────────

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

  // ───────────────────────── lifecycle ─────────────────────────

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
